use std::collections::{hash_map::Entry, HashMap};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use reqwest::multipart::{Form, Part};
use reqwest::redirect::Policy;
use reqwest::{Client, Method};
use serde::{Deserialize, Serialize};
use tauri::ipc::Channel;
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::tungstenite::Message;
use url::Url;

use crate::discovery::DiscoveryService;
use crate::endpoint::normalize_origin;

const API_BASE_PATH: &str = "/api/v1";
const MAX_REQUEST_BYTES: usize = 256 * 1024 * 1024;
const MAX_RESPONSE_BYTES: usize = 128 * 1024 * 1024;
const MAX_SOCKET_MESSAGE_BYTES: usize = 2 * 1024 * 1024;

#[derive(Clone, Default)]
pub struct NativeTransportState {
    requests: Arc<Mutex<HashMap<String, oneshot::Sender<()>>>>,
    sockets: Arc<Mutex<HashMap<String, mpsc::UnboundedSender<SocketCommand>>>>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeHttpRequest {
    request_id: String,
    origin: String,
    method: String,
    path: String,
    #[serde(default)]
    headers: HashMap<String, String>,
    body: Option<NativeRequestBody>,
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
enum NativeRequestBody {
    Text { value: String },
    Binary { base64: String },
    Multipart { parts: Vec<NativeMultipartPart> },
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NativeMultipartPart {
    name: String,
    value: Option<String>,
    base64: Option<String>,
    file_name: Option<String>,
    content_type: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeHttpResponse {
    status: u16,
    headers: HashMap<String, String>,
    body_base64: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeSocketRequest {
    socket_id: String,
    origin: String,
    path: String,
}

#[derive(Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum NativeSocketEvent {
    Open,
    Message { data: String },
    Error { message: String },
    Close { code: Option<u16>, reason: String },
}

enum SocketCommand {
    Text(String),
    Close,
}

#[tauri::command(rename_all = "camelCase")]
pub async fn node_http_request(
    request: NativeHttpRequest,
    state: tauri::State<'_, NativeTransportState>,
    discovery: tauri::State<'_, Arc<DiscoveryService>>,
) -> Result<NativeHttpResponse, String> {
    validate_identifier(&request.request_id, "request")?;
    let origin = allowed_origin(&request.origin, &discovery)?;
    let url = validated_api_url(&origin, &request.path)?;
    let method = validated_method(&request.method)?;
    let headers = validated_headers(request.headers)?;
    let client = native_client()?;
    let mut builder = client.request(method, url).headers(headers);
    if let Some(body) = request.body {
        builder = apply_body(builder, body)?;
    }

    let cancel = register_request(&state, &request.request_id)?;
    let response = tokio::select! {
        result = execute_request(builder) => result,
        _ = cancel => Err("NODE_REQUEST_CANCELLED".to_string()),
    };
    finish_request(&state, &request.request_id);
    response
}

#[tauri::command(rename_all = "camelCase")]
pub fn cancel_node_request(
    request_id: String,
    state: tauri::State<'_, NativeTransportState>,
) -> Result<(), String> {
    validate_identifier(&request_id, "request")?;
    if let Some(cancel) = state
        .requests
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .remove(&request_id)
    {
        let _ = cancel.send(());
    }
    Ok(())
}

#[tauri::command(rename_all = "camelCase")]
pub async fn open_node_socket(
    request: NativeSocketRequest,
    on_event: Channel<NativeSocketEvent>,
    state: tauri::State<'_, NativeTransportState>,
    discovery: tauri::State<'_, Arc<DiscoveryService>>,
) -> Result<(), String> {
    validate_identifier(&request.socket_id, "socket")?;
    let origin = allowed_origin(&request.origin, &discovery)?;
    let url = validated_socket_url(&origin, &request.path)?;
    let (commands, receiver) = mpsc::unbounded_channel();
    {
        let mut sockets = state
            .sockets
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if sockets.contains_key(&request.socket_id) {
            return Err("socket id is already active".to_string());
        }
        sockets.insert(request.socket_id.clone(), commands);
    }

    let transport = state.inner().clone();
    let socket_id = request.socket_id;
    tauri::async_runtime::spawn(async move {
        run_socket(url, receiver, &on_event).await;
        transport
            .sockets
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(&socket_id);
    });
    Ok(())
}

#[tauri::command(rename_all = "camelCase")]
pub fn send_node_socket(
    socket_id: String,
    data: String,
    state: tauri::State<'_, NativeTransportState>,
) -> Result<(), String> {
    validate_identifier(&socket_id, "socket")?;
    if data.len() > MAX_SOCKET_MESSAGE_BYTES {
        return Err("socket message is too large".to_string());
    }
    socket_sender(&state, &socket_id)?
        .send(SocketCommand::Text(data))
        .map_err(|_| "socket is closed".to_string())
}

#[tauri::command(rename_all = "camelCase")]
pub fn close_node_socket(
    socket_id: String,
    state: tauri::State<'_, NativeTransportState>,
) -> Result<(), String> {
    validate_identifier(&socket_id, "socket")?;
    if let Some(sender) = state
        .sockets
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .remove(&socket_id)
    {
        let _ = sender.send(SocketCommand::Close);
    }
    Ok(())
}

fn native_client() -> Result<Client, String> {
    Client::builder()
        .redirect(Policy::none())
        .no_proxy()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(30))
        .user_agent(concat!("MeshX-Desktop/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|error| format!("failed to initialize native node transport: {error}"))
}

fn allowed_origin(origin: &str, discovery: &DiscoveryService) -> Result<String, String> {
    let normalized = normalize_origin(origin)?;
    if !discovery.allows_origin(&normalized) {
        return Err("node origin has not completed the native handshake".to_string());
    }
    Ok(normalized)
}

fn validated_api_url(origin: &str, path: &str) -> Result<Url, String> {
    if path.len() > 4_096
        || !path.starts_with(API_BASE_PATH)
        || !(path == API_BASE_PATH || path.starts_with("/api/v1/"))
        || path.contains('\\')
        || path.contains('#')
    {
        return Err("node API path is invalid".to_string());
    }
    let url = Url::parse(&format!("{origin}{path}"))
        .map_err(|_| "node API path is invalid".to_string())?;
    if url.origin().ascii_serialization() != origin
        || !(url.path() == API_BASE_PATH || url.path().starts_with("/api/v1/"))
    {
        return Err("node API path changed origin".to_string());
    }
    Ok(url)
}

fn validated_socket_url(origin: &str, path: &str) -> Result<Url, String> {
    if path != "/ws/chat" {
        return Err("node WebSocket path is not supported".to_string());
    }
    let mut url = Url::parse(origin).map_err(|_| "node origin is invalid".to_string())?;
    url.set_scheme(if url.scheme() == "https" { "wss" } else { "ws" })
        .map_err(|_| "node WebSocket scheme is invalid".to_string())?;
    url.set_path(path);
    Ok(url)
}

fn validated_method(value: &str) -> Result<Method, String> {
    match value.to_ascii_uppercase().as_str() {
        "GET" => Ok(Method::GET),
        "POST" => Ok(Method::POST),
        "PUT" => Ok(Method::PUT),
        "PATCH" => Ok(Method::PATCH),
        "DELETE" => Ok(Method::DELETE),
        _ => Err("node request method is not allowed".to_string()),
    }
}

fn validated_headers(values: HashMap<String, String>) -> Result<HeaderMap, String> {
    let mut headers = HeaderMap::new();
    for (name, value) in values {
        let lower = name.to_ascii_lowercase();
        if !matches!(
            lower.as_str(),
            "accept" | "authorization" | "content-type" | "x-request-id"
        ) {
            return Err(format!("node request header is not allowed: {lower}"));
        }
        let name = HeaderName::from_bytes(lower.as_bytes())
            .map_err(|_| "node request header name is invalid".to_string())?;
        let value = HeaderValue::from_str(&value)
            .map_err(|_| "node request header value is invalid".to_string())?;
        headers.insert(name, value);
    }
    Ok(headers)
}

fn apply_body(
    builder: reqwest::RequestBuilder,
    body: NativeRequestBody,
) -> Result<reqwest::RequestBuilder, String> {
    match body {
        NativeRequestBody::Text { value } => {
            if value.len() > MAX_REQUEST_BYTES {
                return Err("node request body is too large".to_string());
            }
            Ok(builder.body(value))
        }
        NativeRequestBody::Binary { base64 } => {
            let bytes = decode_body(&base64)?;
            Ok(builder.body(bytes))
        }
        NativeRequestBody::Multipart { parts } => {
            if parts.is_empty() || parts.len() > 32 {
                return Err("multipart body has an invalid part count".to_string());
            }
            let mut form = Form::new();
            let mut total = 0usize;
            for value in parts {
                validate_part_name(&value.name)?;
                let part = match (value.value, value.base64) {
                    (Some(text), None) => {
                        total = total.saturating_add(text.len());
                        Part::text(text)
                    }
                    (None, Some(encoded)) => {
                        let bytes = decode_body(&encoded)?;
                        total = total.saturating_add(bytes.len());
                        let mut part = Part::bytes(bytes);
                        if let Some(file_name) = value.file_name {
                            part = part.file_name(validated_file_name(file_name)?);
                        }
                        if let Some(content_type) = value.content_type {
                            part = part
                                .mime_str(&content_type)
                                .map_err(|_| "multipart content type is invalid".to_string())?;
                        }
                        part
                    }
                    _ => return Err("multipart part must contain exactly one value".to_string()),
                };
                if total > MAX_REQUEST_BYTES {
                    return Err("node request body is too large".to_string());
                }
                form = form.part(value.name, part);
            }
            Ok(builder.multipart(form))
        }
    }
}

fn decode_body(value: &str) -> Result<Vec<u8>, String> {
    if value.len() > MAX_REQUEST_BYTES.saturating_mul(2) {
        return Err("node request body is too large".to_string());
    }
    let bytes = BASE64
        .decode(value)
        .map_err(|_| "node request body is not valid base64".to_string())?;
    if bytes.len() > MAX_REQUEST_BYTES {
        return Err("node request body is too large".to_string());
    }
    Ok(bytes)
}

fn validate_part_name(value: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    {
        return Err("multipart part name is invalid".to_string());
    }
    Ok(())
}

fn validated_file_name(value: String) -> Result<String, String> {
    if value.is_empty()
        || value.len() > 255
        || value
            .chars()
            .any(|character| character.is_control() || matches!(character, '/' | '\\'))
    {
        return Err("multipart file name is invalid".to_string());
    }
    Ok(value)
}

async fn execute_request(builder: reqwest::RequestBuilder) -> Result<NativeHttpResponse, String> {
    let response = builder
        .send()
        .await
        .map_err(|error| network_error(&error, "node request"))?;
    let status = response.status().as_u16();
    let headers = response
        .headers()
        .iter()
        .filter_map(|(name, value)| {
            value
                .to_str()
                .ok()
                .map(|value| (name.to_string(), value.to_string()))
        })
        .collect();
    if response
        .content_length()
        .is_some_and(|size| size > MAX_RESPONSE_BYTES as u64)
    {
        return Err("node response is too large".to_string());
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|error| network_error(&error, "node response"))?;
    if bytes.len() > MAX_RESPONSE_BYTES {
        return Err("node response is too large".to_string());
    }
    Ok(NativeHttpResponse {
        status,
        headers,
        body_base64: BASE64.encode(bytes),
    })
}

fn register_request(
    state: &NativeTransportState,
    request_id: &str,
) -> Result<oneshot::Receiver<()>, String> {
    let (sender, receiver) = oneshot::channel();
    let mut requests = state
        .requests
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    match requests.entry(request_id.to_string()) {
        Entry::Occupied(_) => Err("request id is already active".to_string()),
        Entry::Vacant(entry) => {
            entry.insert(sender);
            Ok(receiver)
        }
    }
}

fn finish_request(state: &NativeTransportState, request_id: &str) {
    state
        .requests
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .remove(request_id);
}

fn socket_sender(
    state: &NativeTransportState,
    socket_id: &str,
) -> Result<mpsc::UnboundedSender<SocketCommand>, String> {
    state
        .sockets
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .get(socket_id)
        .cloned()
        .ok_or_else(|| "socket is not active".to_string())
}

async fn run_socket(
    url: Url,
    mut commands: mpsc::UnboundedReceiver<SocketCommand>,
    on_event: &Channel<NativeSocketEvent>,
) {
    let connection = tokio::time::timeout(
        Duration::from_secs(12),
        tokio_tungstenite::connect_async(url.as_str()),
    )
    .await;
    let stream = match connection {
        Ok(Ok((stream, _response))) => stream,
        Ok(Err(_)) => {
            emit_socket_error(on_event, "unable to connect to the selected node");
            emit_socket_close(on_event, None, "connection failed");
            return;
        }
        Err(_) => {
            emit_socket_error(on_event, "node WebSocket connection timed out");
            emit_socket_close(on_event, None, "connection timed out");
            return;
        }
    };
    let _ = on_event.send(NativeSocketEvent::Open);
    let (mut writer, mut reader) = stream.split();
    loop {
        tokio::select! {
            command = commands.recv() => match command {
                Some(SocketCommand::Text(data)) => {
                    if writer.send(Message::Text(data.into())).await.is_err() {
                        emit_socket_error(on_event, "unable to send WebSocket message");
                        break;
                    }
                }
                Some(SocketCommand::Close) | None => {
                    let _ = writer.send(Message::Close(None)).await;
                    break;
                }
            },
            incoming = reader.next() => match incoming {
                Some(Ok(Message::Text(data))) => {
                    if data.len() > MAX_SOCKET_MESSAGE_BYTES {
                        emit_socket_error(on_event, "node WebSocket message is too large");
                        break;
                    }
                    let _ = on_event.send(NativeSocketEvent::Message { data: data.to_string() });
                }
                Some(Ok(Message::Binary(_))) => {
                    emit_socket_error(on_event, "binary WebSocket messages are not supported");
                    break;
                }
                Some(Ok(Message::Ping(payload))) => {
                    if writer.send(Message::Pong(payload)).await.is_err() {
                        break;
                    }
                }
                Some(Ok(Message::Pong(_))) | Some(Ok(Message::Frame(_))) => {}
                Some(Ok(Message::Close(frame))) => {
                    let (code, reason) = frame
                        .map(|frame| (Some(u16::from(frame.code)), frame.reason.to_string()))
                        .unwrap_or((None, String::new()));
                    emit_socket_close(on_event, code, &reason);
                    return;
                }
                Some(Err(_)) => {
                    emit_socket_error(on_event, "node WebSocket connection failed");
                    break;
                }
                None => break,
            }
        }
    }
    emit_socket_close(on_event, None, "connection closed");
}

fn emit_socket_error(channel: &Channel<NativeSocketEvent>, message: &str) {
    let _ = channel.send(NativeSocketEvent::Error {
        message: message.to_string(),
    });
}

fn emit_socket_close(channel: &Channel<NativeSocketEvent>, code: Option<u16>, reason: &str) {
    let _ = channel.send(NativeSocketEvent::Close {
        code,
        reason: reason.to_string(),
    });
}

fn validate_identifier(value: &str, kind: &str) -> Result<(), String> {
    if !(8..=96).contains(&value.len())
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    {
        return Err(format!("{kind} id is invalid"));
    }
    Ok(())
}

fn network_error(error: &reqwest::Error, subject: &str) -> String {
    if error.is_timeout() {
        format!("{subject} timed out")
    } else {
        format!("unable to complete {subject}")
    }
}

#[cfg(test)]
mod tests {
    use super::{
        register_request, validate_identifier, validated_api_url, validated_file_name,
        validated_method, validated_socket_url, NativeTransportState,
    };
    use tokio::sync::oneshot::error::TryRecvError;

    #[test]
    fn accepts_only_the_v1_api_scope() {
        assert!(validated_api_url("http://192.168.1.8:8080", "/api/v1/chat/history?q=1").is_ok());
        assert!(validated_api_url("http://192.168.1.8:8080", "/api/v10/admin").is_err());
        assert!(validated_api_url("http://192.168.1.8:8080", "/api/v1/../../actuator").is_err());
        assert!(validated_api_url("http://192.168.1.8:8080", "//other/api/v1").is_err());
    }

    #[test]
    fn constrains_methods_socket_paths_and_identifiers() {
        assert!(validated_method("PATCH").is_ok());
        assert!(validated_method("TRACE").is_err());
        assert!(validated_socket_url("https://chat.local", "/ws/chat").is_ok());
        assert!(validated_socket_url("https://chat.local", "/other").is_err());
        assert!(validate_identifier("socket_123456", "socket").is_ok());
        assert!(validate_identifier("../socket", "socket").is_err());
    }

    #[test]
    fn rejects_unsafe_multipart_file_names() {
        assert!(validated_file_name("report.pdf".to_string()).is_ok());
        assert!(validated_file_name("../report.pdf".to_string()).is_err());
        assert!(validated_file_name("folder/report.pdf".to_string()).is_err());
    }

    #[test]
    fn duplicate_request_id_preserves_the_original_request() {
        let state = NativeTransportState::default();
        let request_id = "request_123456";
        let mut original = register_request(&state, request_id).expect("register original request");

        assert_eq!(
            register_request(&state, request_id).unwrap_err(),
            "request id is already active"
        );
        assert_eq!(original.try_recv(), Err(TryRecvError::Empty));

        let cancel = state
            .requests
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(request_id)
            .expect("original request remains registered");
        cancel.send(()).expect("cancel original request");
        assert_eq!(original.try_recv(), Ok(()));
    }
}
