use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use reqwest::redirect::Policy;
use reqwest::{Client, Response};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::endpoint::normalize_origin;

const API_BASE_PATH: &str = "/api/v1";

#[derive(Clone)]
struct NativeSession {
    client: Client,
    api_base_path: String,
}

#[derive(Default)]
pub struct NativeAuthState {
    clients: Mutex<HashMap<String, NativeSession>>,
}

#[derive(Deserialize)]
struct ApiResult {
    code: i64,
    #[serde(default)]
    msg: String,
    #[serde(default)]
    data: Value,
}

impl NativeAuthState {
    fn client_for(&self, origin: &str) -> Result<NativeSession, String> {
        self.clients
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(origin)
            .cloned()
            .ok_or_else(|| "no native refresh session exists for this node".to_string())
    }

    fn replace_client(&self, origin: String, session: NativeSession) {
        self.clients
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .insert(origin, session);
    }

    fn remove_client(&self, origin: &str) {
        self.clients
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(origin);
    }
}

#[tauri::command(rename_all = "camelCase")]
pub async fn desktop_login(
    origin: String,
    username: String,
    password: String,
    device_name: String,
    api_base_path: Option<String>,
    state: tauri::State<'_, NativeAuthState>,
) -> Result<Value, String> {
    let origin = normalize_origin(&origin)?;
    let api_base_path = validated_api_base_path(api_base_path.as_deref())?;
    validate_login_input(&username, &password, &device_name)?;
    let client = build_client()?;
    let response = client
        .post(format!("{origin}{api_base_path}/auth/login"))
        .json(&json!({
            "username": username,
            "password": password,
            "deviceType": "desktop",
            "deviceName": normalized_device_name(&device_name),
        }))
        .send()
        .await
        .map_err(network_error)?;
    let result = parse_auth_response(response).await?;
    state.replace_client(
        origin,
        NativeSession {
            client,
            api_base_path,
        },
    );
    Ok(result)
}

#[tauri::command(rename_all = "camelCase")]
pub async fn desktop_refresh(
    origin: String,
    device_name: String,
    api_base_path: Option<String>,
    state: tauri::State<'_, NativeAuthState>,
) -> Result<Value, String> {
    let origin = normalize_origin(&origin)?;
    if device_name.len() > 100 {
        return Err("device name is too long".to_string());
    }
    let session = state.client_for(&origin)?;
    let api_base_path = resolve_session_path(&session, api_base_path.as_deref())?;
    let response = session
        .client
        .post(format!("{origin}{api_base_path}/auth/refresh"))
        .json(&json!({
            "deviceType": "desktop",
            "deviceName": normalized_device_name(&device_name),
        }))
        .send()
        .await
        .map_err(network_error)?;
    parse_auth_response(response).await
}

#[tauri::command(rename_all = "camelCase")]
pub async fn desktop_logout(
    origin: String,
    access_token: Option<String>,
    api_base_path: Option<String>,
    state: tauri::State<'_, NativeAuthState>,
) -> Result<(), String> {
    let origin = normalize_origin(&origin)?;
    if let Some(access_token) = access_token.as_ref() {
        if access_token.len() > 8_192 || access_token.bytes().any(|byte| byte.is_ascii_control()) {
            return Err("access token is invalid".to_string());
        }
    }
    let session = match state.client_for(&origin) {
        Ok(session) => session,
        Err(_) => return Ok(()),
    };
    let api_base_path = resolve_session_path(&session, api_base_path.as_deref())?;
    let result = async {
        let mut request = session
            .client
            .post(format!("{origin}{api_base_path}/auth/logout"));
        if let Some(access_token) = access_token.filter(|token| !token.is_empty()) {
            request = request.bearer_auth(access_token);
        }
        let response = request.send().await.map_err(network_error)?;
        parse_unit_response(response).await
    }
    .await;
    state.remove_client(&origin);
    result
}

fn build_client() -> Result<Client, String> {
    Client::builder()
        .cookie_store(true)
        .redirect(Policy::none())
        .no_proxy()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(10))
        .user_agent("MeshX-Desktop/3.0.0")
        .build()
        .map_err(|error| format!("failed to initialize native authentication: {error}"))
}

fn validated_api_base_path(value: Option<&str>) -> Result<String, String> {
    let value = value.unwrap_or(API_BASE_PATH);
    if value == API_BASE_PATH {
        Ok(value.to_string())
    } else {
        Err("node API base path is not supported".to_string())
    }
}

fn resolve_session_path(
    session: &NativeSession,
    requested: Option<&str>,
) -> Result<String, String> {
    let requested = validated_api_base_path(requested)?;
    if requested == session.api_base_path {
        Ok(requested)
    } else {
        Err("native authentication path changed for this node".to_string())
    }
}

async fn parse_auth_response(response: Response) -> Result<Value, String> {
    let mut result = parse_result(response).await?;
    if let Value::Object(data) = &mut result.data {
        data.remove("refreshToken");
    }
    if result.data.is_null() {
        return Err("authentication response did not contain session data".to_string());
    }
    Ok(result.data)
}

async fn parse_unit_response(response: Response) -> Result<(), String> {
    parse_result(response).await.map(|_| ())
}

async fn parse_result(response: Response) -> Result<ApiResult, String> {
    let status = response.status();
    let result = response
        .json::<ApiResult>()
        .await
        .map_err(|_| format!("node returned an invalid authentication response ({status})"))?;
    if result.code != 200 {
        return Err(if result.msg.trim().is_empty() {
            format!("authentication request failed with code {}", result.code)
        } else {
            result.msg
        });
    }
    Ok(result)
}

fn validate_login_input(username: &str, password: &str, device_name: &str) -> Result<(), String> {
    if username.trim().is_empty() || username.len() > 100 {
        return Err("username is empty or too long".to_string());
    }
    if password.is_empty() || password.len() > 1_024 {
        return Err("password is empty or too long".to_string());
    }
    if device_name.len() > 100 {
        return Err("device name is too long".to_string());
    }
    Ok(())
}

fn normalized_device_name(value: &str) -> String {
    let clean: String = value
        .chars()
        .filter(|character| !character.is_control())
        .take(100)
        .collect();
    if clean.trim().is_empty() {
        "MeshX Desktop".to_string()
    } else {
        clean
    }
}

fn network_error(error: reqwest::Error) -> String {
    if error.is_timeout() {
        "node authentication request timed out".to_string()
    } else {
        "unable to connect to the selected node".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::{normalized_device_name, validate_login_input, validated_api_base_path};

    #[test]
    fn sanitizes_device_names() {
        assert_eq!(normalized_device_name("\n"), "MeshX Desktop");
        assert_eq!(normalized_device_name("Office\u{0000} PC"), "Office PC");
    }

    #[test]
    fn validates_secret_input_without_echoing_it() {
        assert!(validate_login_input("", "password", "desktop").is_err());
        assert!(validate_login_input("user", "", "desktop").is_err());
        assert!(validate_login_input("user", "password", "desktop").is_ok());
    }

    #[test]
    fn accepts_only_the_validated_v1_api_base_path() {
        assert_eq!(
            validated_api_base_path(None).unwrap(),
            "/api/v1".to_string()
        );
        assert!(validated_api_base_path(Some("/api/v2")).is_err());
        assert!(validated_api_base_path(Some("https://example.test/api/v1")).is_err());
    }
}
