use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use reqwest::redirect::Policy;
use reqwest::{Client, Response};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::device_identity::{
    verify_active_credential, ControlTrustAnchor, DeviceIdentity, DeviceIdentityState,
};
use crate::discovery::DiscoveryService;
use crate::endpoint::normalize_origin;
use crate::node_runtime::{NodeRuntimeContext, NodeRuntimeIdentityKey, NodeRuntimeState};
use crate::revocation::RevocationState;

const API_BASE_PATH: &str = "/api/v1";

#[derive(Clone)]
struct NativeSession {
    session_id: u64,
    client: Client,
    api_base_path: String,
    runtime_key: Option<NodeRuntimeIdentityKey>,
}

struct CompletedDeviceIdentity {
    auth: Value,
    runtime_key: Option<NodeRuntimeIdentityKey>,
}

#[derive(Default)]
pub struct NativeAuthState {
    clients: Mutex<HashMap<String, NativeSession>>,
    next_session_id: AtomicU64,
    device_identity: DeviceIdentityState,
    revocations: RevocationState,
    runtime: Option<Arc<NodeRuntimeState>>,
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
    pub fn new(revocation_cache_path: std::path::PathBuf, runtime: Arc<NodeRuntimeState>) -> Self {
        Self {
            clients: Mutex::new(HashMap::new()),
            next_session_id: AtomicU64::new(1),
            device_identity: DeviceIdentityState,
            revocations: RevocationState::new(revocation_cache_path),
            runtime: Some(runtime),
        }
    }

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

    fn next_session_id(&self) -> u64 {
        self.next_session_id.fetch_add(1, Ordering::Relaxed)
    }

    fn remove_client(&self, origin: &str) -> Option<NativeSession> {
        self.clients
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(origin)
    }

    fn remove_client_if_matches(
        &self,
        origin: &str,
        expected_session_id: u64,
    ) -> Option<NativeSession> {
        let mut clients = self
            .clients
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if clients
            .get(origin)
            .is_some_and(|session| session.session_id == expected_session_id)
        {
            clients.remove(origin)
        } else {
            None
        }
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
    discovery: tauri::State<'_, Arc<DiscoveryService>>,
) -> Result<Value, String> {
    let origin = normalize_origin(&origin)?;
    require_allowed_origin(&origin, &discovery)?;
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
    let completed = complete_device_identity(
        &origin,
        &api_base_path,
        &device_name,
        &client,
        &discovery,
        &state,
        result,
    )
    .await?;
    state.replace_client(
        origin,
        NativeSession {
            session_id: state.next_session_id(),
            client,
            api_base_path,
            runtime_key: completed.runtime_key,
        },
    );
    Ok(completed.auth)
}

#[tauri::command(rename_all = "camelCase")]
pub async fn desktop_refresh(
    origin: String,
    device_name: String,
    api_base_path: Option<String>,
    state: tauri::State<'_, NativeAuthState>,
    discovery: tauri::State<'_, Arc<DiscoveryService>>,
) -> Result<Value, String> {
    let origin = normalize_origin(&origin)?;
    require_allowed_origin(&origin, &discovery)?;
    if device_name.len() > 100 {
        return Err("device name is too long".to_string());
    }
    let session = state.client_for(&origin)?;
    let api_base_path = resolve_session_path(&session, api_base_path.as_deref())?;
    let completion = async {
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
        let result = parse_auth_response(response).await?;
        complete_device_identity(
            &origin,
            &api_base_path,
            &device_name,
            &session.client,
            &discovery,
            &state,
            result,
        )
        .await
    }
    .await;
    match completion {
        Ok(completed) => {
            state.replace_client(
                origin,
                NativeSession {
                    session_id: state.next_session_id(),
                    client: session.client,
                    api_base_path,
                    runtime_key: completed.runtime_key,
                },
            );
            Ok(completed.auth)
        }
        Err(error) => {
            let cleanup =
                remove_session_and_deactivate(&state, &origin, Some(session.session_id)).await;
            match cleanup {
                Ok(()) => Err(error),
                Err(cleanup_error) => Err(format!("{error}; {cleanup_error}")),
            }
        }
    }
}

#[tauri::command(rename_all = "camelCase")]
pub async fn desktop_logout(
    origin: String,
    access_token: Option<String>,
    api_base_path: Option<String>,
    state: tauri::State<'_, NativeAuthState>,
    discovery: tauri::State<'_, Arc<DiscoveryService>>,
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
    // Clearing local session state must keep working even after the node
    // dropped off the discovery allow-list (for example when a crashed node
    // was pruned). Only the network logout request is gated on the allow-list.
    if require_allowed_origin(&origin, &discovery).is_err() {
        return clear_session_without_network(&state, &origin, session.session_id).await;
    }
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
    let cleanup = remove_session_and_deactivate(&state, &origin, Some(session.session_id)).await;
    match (result, cleanup) {
        (Err(error), _) => Err(error),
        (Ok(()), cleanup) => cleanup,
    }
}

fn build_client() -> Result<Client, String> {
    Client::builder()
        .cookie_store(true)
        .redirect(Policy::none())
        .no_proxy()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(10))
        .user_agent(concat!("MeshX-Desktop/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|error| format!("failed to initialize native authentication: {error}"))
}

async fn clear_session_without_network(
    state: &NativeAuthState,
    origin: &str,
    expected_session_id: u64,
) -> Result<(), String> {
    remove_session_and_deactivate(state, origin, Some(expected_session_id)).await
}

async fn remove_session_and_deactivate(
    state: &NativeAuthState,
    origin: &str,
    expected_session_id: Option<u64>,
) -> Result<(), String> {
    let removed = match expected_session_id {
        Some(session_id) => state.remove_client_if_matches(origin, session_id),
        None => state.remove_client(origin),
    };
    let Some(session) = removed else {
        return Ok(());
    };
    let (Some(runtime), Some(runtime_key)) = (&state.runtime, session.runtime_key) else {
        return Ok(());
    };
    runtime.deactivate(runtime_key).await
}

async fn complete_device_identity(
    origin: &str,
    api_base_path: &str,
    device_name: &str,
    client: &Client,
    discovery: &DiscoveryService,
    security_state: &NativeAuthState,
    mut auth: Value,
) -> Result<CompletedDeviceIdentity, String> {
    let trust = match discovery.control_trust_anchor(origin) {
        Ok(trust) => trust,
        Err(error) => {
            best_effort_logout(client, origin, api_base_path, auth_access_token(&auth).ok()).await;
            return Err(error);
        }
    };
    let Some(trust) = trust else {
        return Ok(CompletedDeviceIdentity {
            auth,
            runtime_key: None,
        });
    };
    let access_token = match auth_access_token(&auth) {
        Ok(token) => token.to_string(),
        Err(error) => {
            best_effort_logout(client, origin, api_base_path, None).await;
            return Err(error);
        }
    };
    let identity = match security_state
        .device_identity
        .identity_for(trust.control_id.clone())
        .await
    {
        Ok(identity) => identity,
        Err(error) => {
            best_effort_logout(client, origin, api_base_path, Some(&access_token)).await;
            return Err(error);
        }
    };
    let registration = async {
        let device = register_active_device(
            origin,
            device_name,
            client,
            &access_token,
            &identity,
            &trust,
        )
        .await?;
        let revocation_version = security_state
            .revocations
            .sync(origin, client, &access_token, &trust, &identity, &device)
            .await?;
        Ok::<_, String>((device, revocation_version))
    }
    .await;

    let (device, revocation_version) = match registration {
        Ok(result) => result,
        Err(error) => {
            best_effort_logout(client, origin, api_base_path, Some(&access_token)).await;
            return Err(error);
        }
    };
    if !auth.is_object() {
        best_effort_logout(client, origin, api_base_path, Some(&access_token)).await;
        return Err("authentication response did not contain session data".to_string());
    }
    let context = match NodeRuntimeContext::from_verified_session(
        &auth,
        &device,
        &trust,
        &identity,
        revocation_version,
    ) {
        Ok(context) => context,
        Err(error) => {
            best_effort_logout(client, origin, api_base_path, Some(&access_token)).await;
            return Err(error);
        }
    };
    let runtime_key = context.key.clone();
    let runtime = match security_state.runtime.as_ref() {
        Some(runtime) => runtime,
        None => {
            best_effort_logout(client, origin, api_base_path, Some(&access_token)).await;
            return Err("Node Runtime is unavailable".to_string());
        }
    };
    let runtime_status = match runtime.activate(context).await {
        Ok(status) => status,
        Err(error) => {
            best_effort_logout(client, origin, api_base_path, Some(&access_token)).await;
            return Err(error);
        }
    };
    let fields = auth
        .as_object_mut()
        .expect("authentication object was checked");
    fields.insert("organizationId".to_string(), json!(trust.organization_id));
    fields.insert("revocationVersion".to_string(), json!(revocation_version));
    fields.insert("device".to_string(), device);
    fields.insert("nodeRuntime".to_string(), json!(runtime_status));
    Ok(CompletedDeviceIdentity {
        auth,
        runtime_key: Some(runtime_key),
    })
}

async fn register_active_device(
    origin: &str,
    device_name: &str,
    client: &Client,
    access_token: &str,
    identity: &DeviceIdentity,
    trust: &ControlTrustAnchor,
) -> Result<Value, String> {
    let response = client
        .post(format!("{origin}/api/v2/control/devices/registrations"))
        .bearer_auth(access_token)
        .json(&json!({
            "deviceKey": &identity.device_key,
            "platform": "DESKTOP",
            "displayName": normalized_device_name(device_name),
            "appVersion": env!("CARGO_PKG_VERSION"),
            "capabilities": ["CHAT", "FILE_TRANSFER", "NATIVE_NODE"],
            "algorithm": "ED25519",
            "publicKey": &identity.public_key,
        }))
        .send()
        .await
        .map_err(network_error)?;
    let device = parse_result(response, "device registration").await?.data;
    if device.is_null() {
        return Err("device registration response did not contain a device".to_string());
    }
    verify_active_credential(&device, identity, trust)?;
    Ok(device)
}

fn auth_access_token(auth: &Value) -> Result<&str, String> {
    let token = auth
        .get("token")
        .and_then(Value::as_str)
        .ok_or_else(|| "authentication response did not contain an access token".to_string())?;
    if token.is_empty() || token.len() > 8_192 || token.bytes().any(|byte| byte.is_ascii_control())
    {
        return Err("authentication response contained an invalid access token".to_string());
    }
    Ok(token)
}

async fn best_effort_logout(
    client: &Client,
    origin: &str,
    api_base_path: &str,
    access_token: Option<&str>,
) {
    let mut request = client.post(format!("{origin}{api_base_path}/auth/logout"));
    if let Some(access_token) = access_token {
        request = request.bearer_auth(access_token);
    }
    let _ = request.send().await;
}

fn require_allowed_origin(origin: &str, discovery: &DiscoveryService) -> Result<(), String> {
    if discovery.allows_origin(origin) {
        Ok(())
    } else {
        Err("node origin has not completed the native handshake".to_string())
    }
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
    let result = parse_result(response, "authentication").await?;
    sanitize_auth_data(result.data)
}

fn sanitize_auth_data(mut data: Value) -> Result<Value, String> {
    if let Value::Object(fields) = &mut data {
        fields.remove("refreshToken");
    }
    if data.is_null() {
        return Err("authentication response did not contain session data".to_string());
    }
    Ok(data)
}

async fn parse_unit_response(response: Response) -> Result<(), String> {
    parse_result(response, "authentication").await.map(|_| ())
}

async fn parse_result(response: Response, operation: &str) -> Result<ApiResult, String> {
    let status = response.status();
    let result = response
        .json::<ApiResult>()
        .await
        .map_err(|_| format!("node returned an invalid {operation} response ({status})"))?;
    if result.code != 200 {
        return Err(if result.msg.trim().is_empty() {
            format!("{operation} request failed with code {}", result.code)
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
    use super::{
        build_client, clear_session_without_network, normalized_device_name,
        register_active_device, remove_session_and_deactivate, sanitize_auth_data,
        validate_login_input, validated_api_base_path, NativeAuthState, NativeSession,
        API_BASE_PATH,
    };
    use crate::device_identity::{ControlTrustAnchor, DeviceIdentity};
    use crate::node_runtime::{NodeRuntimeContext, NodeRuntimeIdentityKey, NodeRuntimeState};
    use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
    use base64::Engine;
    use ed25519_dalek::pkcs8::EncodePublicKey;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::{json, Value};
    use sha2::{Digest, Sha256};
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

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

    #[test]
    fn strips_refresh_token_before_exposing_auth_data_to_js() {
        let sanitized = sanitize_auth_data(json!({
            "token": "access-token",
            "refreshToken": "secret-refresh-token",
            "expiresIn": 3600,
            "userId": 42,
        }))
        .unwrap();
        assert!(sanitized.get("refreshToken").is_none());
        assert_eq!(sanitized.get("token"), Some(&json!("access-token")));
        assert_eq!(sanitized.get("expiresIn"), Some(&json!(3600)));
        assert_eq!(sanitized.get("userId"), Some(&json!(42)));
    }

    #[test]
    fn rejects_auth_responses_without_session_data() {
        assert!(sanitize_auth_data(Value::Null).is_err());
    }

    #[tokio::test]
    async fn registration_posts_only_public_identity_and_accepts_a_signed_active_device() {
        let identity = test_identity([7_u8; 32]);
        let control_signing_key = SigningKey::from_bytes(&[9_u8; 32]);
        let control_identity = test_identity([9_u8; 32]);
        let trust = ControlTrustAnchor {
            control_id: "control-test".to_string(),
            organization_id: "org-test".to_string(),
            public_key: control_identity.public_key,
            fingerprint: control_identity.fingerprint,
        };
        let payload = json!({
            "type": "MESHX_DEVICE_CERTIFICATE",
            "version": 1,
            "credentialId": "cred-test",
            "organizationId": trust.organization_id.clone(),
            "controlId": trust.control_id.clone(),
            "deviceId": 42,
            "deviceKey": identity.device_key.clone(),
            "memberId": 7,
            "algorithm": "ED25519",
            "publicKeyFingerprint": identity.fingerprint.clone(),
            "issuedAt": "2026-08-11T10:00:00",
            "expiresAt": "2026-11-09T10:00:00",
            "maxOfflineHours": 72,
        })
        .to_string();
        let signature =
            URL_SAFE_NO_PAD.encode(control_signing_key.sign(payload.as_bytes()).to_bytes());
        let response_device = json!({
            "id": 42,
            "deviceKey": identity.device_key.clone(),
            "status": "ACTIVE",
            "credential": {
                "credentialId": "cred-test",
                "algorithm": "ED25519",
                "fingerprint": identity.fingerprint.clone(),
                "status": "ACTIVE",
                "certificatePayload": payload,
                "certificateSignature": signature,
                "controlSigningPublicKey": trust.public_key.clone(),
                "controlKeyFingerprint": trust.fingerprint.clone(),
            }
        });
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let expected_identity = identity.clone();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let request = read_http_request(&mut stream);
            let (headers, body) = request.split_once("\r\n\r\n").unwrap();
            assert!(headers.starts_with("POST /api/v2/control/devices/registrations HTTP/1.1"));
            assert!(headers
                .to_ascii_lowercase()
                .contains("authorization: bearer test-access-token"));
            let body: Value = serde_json::from_str(body).unwrap();
            assert_eq!(body["deviceKey"], expected_identity.device_key);
            assert_eq!(body["publicKey"], expected_identity.public_key);
            assert_eq!(body["algorithm"], "ED25519");
            assert!(body.get("privateKey").is_none());

            let response = json!({"code": 200, "msg": "ok", "data": response_device}).to_string();
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                response.len(),
                response
            )
            .unwrap();
        });

        let device = register_active_device(
            &origin,
            "Office Desktop",
            &build_client().unwrap(),
            "test-access-token",
            &identity,
            &trust,
        )
        .await
        .unwrap();
        assert_eq!(device["id"], 42);
        server.join().unwrap();
    }

    #[test]
    fn clearing_a_node_session_leaves_other_nodes_untouched() {
        let state = NativeAuthState::default();
        let session = NativeSession {
            session_id: 1,
            client: build_client().unwrap(),
            api_base_path: API_BASE_PATH.to_string(),
            runtime_key: None,
        };
        state.replace_client("https://node-a.local:8443".to_string(), session.clone());
        state.replace_client("https://node-b.local:8443".to_string(), session);

        let _ = state.remove_client("https://node-a.local:8443");

        assert_eq!(
            state.client_for("https://node-a.local:8443").err(),
            Some("no native refresh session exists for this node".to_string())
        );
        assert!(state.client_for("https://node-b.local:8443").is_ok());
    }

    #[tokio::test]
    async fn logout_for_a_pruned_origin_still_clears_the_stored_session() {
        let state = NativeAuthState::default();
        state.replace_client(
            "https://node-a.local:8443".to_string(),
            NativeSession {
                session_id: 1,
                client: build_client().unwrap(),
                api_base_path: API_BASE_PATH.to_string(),
                runtime_key: None,
            },
        );

        // desktop_logout takes this path when the origin is no longer on the
        // discovery allow-list: the network logout is skipped, but the local
        // session must still be cleared so the user can leave a dead node.
        assert_eq!(
            clear_session_without_network(&state, "https://node-a.local:8443", 1).await,
            Ok(())
        );
        assert_eq!(
            state.client_for("https://node-a.local:8443").err(),
            Some("no native refresh session exists for this node".to_string())
        );
    }

    #[tokio::test]
    async fn stale_logout_cannot_remove_a_newer_session_for_the_same_origin() {
        let state = NativeAuthState::default();
        let origin = "https://node-a.local:8443";
        state.replace_client(
            origin.to_string(),
            NativeSession {
                session_id: 1,
                client: build_client().unwrap(),
                api_base_path: API_BASE_PATH.to_string(),
                runtime_key: None,
            },
        );
        state.replace_client(
            origin.to_string(),
            NativeSession {
                session_id: 2,
                client: build_client().unwrap(),
                api_base_path: API_BASE_PATH.to_string(),
                runtime_key: None,
            },
        );

        remove_session_and_deactivate(&state, origin, Some(1))
            .await
            .unwrap();

        assert_eq!(state.client_for(origin).unwrap().session_id, 2);
    }

    #[tokio::test]
    async fn clearing_a_matching_session_deactivates_its_runtime_database() {
        let directory = std::env::temp_dir().join(format!(
            "meshx-native-auth-runtime-test-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let runtime = NodeRuntimeState::new(directory.clone()).unwrap();
        let runtime_key = NodeRuntimeIdentityKey {
            control_id: "control-a".to_string(),
            organization_id: "org-a".to_string(),
            account_user_id: 9,
            device_key: "device-a".to_string(),
        };
        runtime
            .activate(NodeRuntimeContext {
                key: runtime_key.clone(),
                member_id: 109,
                device_id: 209,
                node_id: "node-a".to_string(),
                algorithm: "ED25519".to_string(),
                public_key: "public-key".to_string(),
                public_key_fingerprint: "fingerprint".to_string(),
                credential_id: "credential".to_string(),
                certificate_payload: "payload".to_string(),
                certificate_signature: "signature".to_string(),
                certificate_issued_at: "2026-08-11T10:00:00Z".to_string(),
                certificate_expires_at: "2026-11-09T10:00:00Z".to_string(),
                max_offline_hours: 72,
                revocation_version: 3,
            })
            .await
            .unwrap();
        let state = NativeAuthState {
            runtime: Some(runtime.clone()),
            ..NativeAuthState::default()
        };
        let origin = "https://node-a.local:8443";
        state.replace_client(
            origin.to_string(),
            NativeSession {
                session_id: 1,
                client: build_client().unwrap(),
                api_base_path: API_BASE_PATH.to_string(),
                runtime_key: Some(runtime_key),
            },
        );

        remove_session_and_deactivate(&state, origin, Some(1))
            .await
            .unwrap();

        assert!(!runtime.snapshot().active);
        runtime.shutdown().unwrap();
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn clearing_an_unknown_node_session_is_a_no_op() {
        let state = NativeAuthState::default();
        let _ = state.remove_client("https://node-a.local:8443");
        assert!(state.client_for("https://node-a.local:8443").is_err());
    }

    fn test_identity(seed: [u8; 32]) -> DeviceIdentity {
        let public_der = SigningKey::from_bytes(&seed)
            .verifying_key()
            .to_public_key_der()
            .unwrap();
        let fingerprint = format!("{:x}", Sha256::digest(public_der.as_bytes()));
        DeviceIdentity {
            device_key: format!("desktop-{}", &fingerprint[..32]),
            public_key: STANDARD.encode(public_der.as_bytes()),
            fingerprint,
        }
    }

    fn read_http_request(stream: &mut impl Read) -> String {
        let mut bytes = Vec::new();
        let mut buffer = [0_u8; 4096];
        loop {
            let read = stream.read(&mut buffer).unwrap();
            assert!(read > 0, "HTTP request ended before its body was complete");
            bytes.extend_from_slice(&buffer[..read]);
            let Some(header_end) = bytes.windows(4).position(|window| window == b"\r\n\r\n") else {
                continue;
            };
            let headers = String::from_utf8_lossy(&bytes[..header_end]);
            let content_length = headers
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length:")
                        .map(str::trim)
                        .and_then(|value| value.parse::<usize>().ok())
                })
                .unwrap();
            if bytes.len() >= header_end + 4 + content_length {
                return String::from_utf8(bytes).unwrap();
            }
        }
    }
}
