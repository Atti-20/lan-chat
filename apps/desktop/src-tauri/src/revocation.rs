use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use chrono::Utc;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::device_identity::{verify_control_signature, ControlTrustAnchor, DeviceIdentity};

const CACHE_FORMAT_VERSION: u8 = 1;
const PAGE_LIMIT: u16 = 500;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RevocationSnapshotResponse {
    organization_id: String,
    current_version: u64,
    after_version: u64,
    control_signing_public_key: String,
    control_key_fingerprint: String,
    #[serde(default)]
    entries: Vec<RevocationEntryResponse>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RevocationEntryResponse {
    version: u64,
    subject_type: String,
    subject_id: String,
    reason: String,
    revoked_at: String,
    expires_at: Option<String>,
    signed_payload: String,
    signature: String,
    control_key_fingerprint: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct VerifiedRevocationEntry {
    version: u64,
    subject_type: String,
    subject_id: String,
    device_key: Option<String>,
    reason: String,
    revoked_at: String,
    expires_at: Option<String>,
    signed_payload: String,
    signature: String,
    control_key_fingerprint: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct CachedControlRevocations {
    control_id: String,
    organization_id: String,
    current_version: u64,
    control_key_fingerprint: String,
    synced_at: String,
    entries: Vec<VerifiedRevocationEntry>,
}

#[derive(Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct RevocationCacheFile {
    format_version: u8,
    controls: Vec<CachedControlRevocations>,
}

#[derive(Deserialize)]
struct ApiResult<T> {
    code: i64,
    #[serde(default)]
    msg: String,
    data: Option<T>,
}

#[derive(Default)]
pub struct RevocationState {
    cache_path: Option<PathBuf>,
    controls: Mutex<HashMap<String, CachedControlRevocations>>,
    sync_gate: tokio::sync::Mutex<()>,
}

impl RevocationState {
    pub fn new(cache_path: PathBuf) -> Self {
        Self {
            controls: Mutex::new(load_cache(&cache_path)),
            cache_path: Some(cache_path),
            sync_gate: tokio::sync::Mutex::new(()),
        }
    }

    pub async fn sync(
        &self,
        origin: &str,
        client: &Client,
        access_token: &str,
        trust: &ControlTrustAnchor,
        identity: &DeviceIdentity,
        device: &Value,
    ) -> Result<u64, String> {
        // Serialize synchronization so two simultaneous login/refresh flows
        // cannot overwrite a newer in-memory snapshot with an older page.
        let _sync_guard = self.sync_gate.lock().await;
        let mut cached = self.cached_for(trust)?;
        verify_cached_snapshot(&cached, trust)?;
        let mut cursor = cached.current_version;

        loop {
            let response = client
                .get(format!(
                    "{origin}/api/v2/control/devices/revocations?afterVersion={cursor}&limit={PAGE_LIMIT}"
                ))
                .bearer_auth(access_token)
                .send()
                .await
                .map_err(|error| {
                    if error.is_timeout() {
                        "device revocation sync timed out".to_string()
                    } else {
                        "unable to synchronize device revocations".to_string()
                    }
                })?;
            let status = response.status();
            let result = response
                .json::<ApiResult<RevocationSnapshotResponse>>()
                .await
                .map_err(|_| {
                    format!("Control returned an invalid revocation response ({status})")
                })?;
            if result.code != 200 {
                return Err(if result.msg.trim().is_empty() {
                    format!("device revocation sync failed with code {}", result.code)
                } else {
                    result.msg
                });
            }
            let page = result
                .data
                .ok_or_else(|| "revocation response did not contain a snapshot".to_string())?;
            validate_page(&page, cursor, trust)?;
            if page.current_version < cached.current_version {
                return Err("Control revocation version moved backwards".to_string());
            }

            for entry in page.entries {
                if entry.version > page.current_version {
                    return Err("revocation entry exceeded the snapshot version".to_string());
                }
                let verified = verify_entry(entry, cursor + 1, trust)?;
                cursor = verified.version;
                cached.entries.push(verified);
            }
            cached.current_version = cursor;
            cached.synced_at = Utc::now().to_rfc3339();
            if cursor >= page.current_version {
                break;
            }
            if cached.entries.last().map(|entry| entry.version) != Some(cursor) {
                return Err("Control revocation snapshot did not advance".to_string());
            }
        }

        self.replace_and_persist(cached.clone()).await?;
        reject_revoked_device(&cached, identity, device)?;
        Ok(cursor)
    }

    fn cached_for(&self, trust: &ControlTrustAnchor) -> Result<CachedControlRevocations, String> {
        let cached = self
            .controls
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(&trust.control_id)
            .cloned();
        match cached {
            Some(cached)
                if cached.organization_id == trust.organization_id
                    && cached.control_key_fingerprint == trust.fingerprint =>
            {
                Ok(cached)
            }
            Some(_) => Err("cached revocation trust anchor did not match Control".to_string()),
            None => Ok(CachedControlRevocations {
                control_id: trust.control_id.clone(),
                organization_id: trust.organization_id.clone(),
                current_version: 0,
                control_key_fingerprint: trust.fingerprint.clone(),
                synced_at: Utc::now().to_rfc3339(),
                entries: Vec::new(),
            }),
        }
    }

    async fn replace_and_persist(&self, cached: CachedControlRevocations) -> Result<(), String> {
        let controls = {
            let mut controls = self
                .controls
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            controls.insert(cached.control_id.clone(), cached);
            controls.values().cloned().collect::<Vec<_>>()
        };
        let Some(path) = self.cache_path.clone() else {
            return Ok(());
        };
        tauri::async_runtime::spawn_blocking(move || persist_cache(&path, controls))
            .await
            .map_err(|_| "revocation cache task did not complete".to_string())?
    }
}

fn validate_page(
    page: &RevocationSnapshotResponse,
    cursor: u64,
    trust: &ControlTrustAnchor,
) -> Result<(), String> {
    if page.organization_id != trust.organization_id
        || page.control_signing_public_key != trust.public_key
        || page.control_key_fingerprint != trust.fingerprint
    {
        return Err("revocation snapshot trust anchor did not match discovery".to_string());
    }
    if page.after_version != cursor || page.current_version < cursor {
        return Err("revocation snapshot version was invalid".to_string());
    }
    if page.entries.is_empty() && page.current_version > cursor {
        return Err("Control revocation snapshot did not advance".to_string());
    }
    Ok(())
}

fn verify_entry(
    entry: RevocationEntryResponse,
    expected_version: u64,
    trust: &ControlTrustAnchor,
) -> Result<VerifiedRevocationEntry, String> {
    if entry.version != expected_version || entry.control_key_fingerprint != trust.fingerprint {
        return Err("revocation entry version or signing key was invalid".to_string());
    }
    verify_control_signature(&entry.signed_payload, &entry.signature, trust)?;
    let claims: Value = serde_json::from_str(&entry.signed_payload)
        .map_err(|_| "revocation payload was invalid".to_string())?;
    if claims.get("type").and_then(Value::as_str) != Some("MESHX_REVOCATION")
        || claims.get("version").and_then(Value::as_u64) != Some(entry.version)
        || claims.get("organizationId").and_then(Value::as_str)
            != Some(trust.organization_id.as_str())
        || claims.get("controlId").and_then(Value::as_str) != Some(trust.control_id.as_str())
        || claims.get("subjectType").and_then(Value::as_str) != Some(entry.subject_type.as_str())
        || claims.get("subjectId").and_then(Value::as_str) != Some(entry.subject_id.as_str())
        || claims.get("reason").and_then(Value::as_str) != Some(entry.reason.as_str())
        || claims.get("revokedAt").and_then(Value::as_str) != Some(entry.revoked_at.as_str())
    {
        return Err("revocation payload claims did not match the entry".to_string());
    }
    Ok(VerifiedRevocationEntry {
        version: entry.version,
        subject_type: entry.subject_type,
        subject_id: entry.subject_id,
        device_key: claims
            .get("deviceKey")
            .and_then(Value::as_str)
            .map(str::to_string),
        reason: entry.reason,
        revoked_at: entry.revoked_at,
        expires_at: entry.expires_at,
        signed_payload: entry.signed_payload,
        signature: entry.signature,
        control_key_fingerprint: entry.control_key_fingerprint,
    })
}

fn verify_cached_snapshot(
    cached: &CachedControlRevocations,
    trust: &ControlTrustAnchor,
) -> Result<(), String> {
    if cached.current_version
        != cached
            .entries
            .last()
            .map(|entry| entry.version)
            .unwrap_or(0)
    {
        return Err("cached revocation snapshot version was inconsistent".to_string());
    }
    for (index, entry) in cached.entries.iter().enumerate() {
        let response = RevocationEntryResponse {
            version: entry.version,
            subject_type: entry.subject_type.clone(),
            subject_id: entry.subject_id.clone(),
            reason: entry.reason.clone(),
            revoked_at: entry.revoked_at.clone(),
            expires_at: entry.expires_at.clone(),
            signed_payload: entry.signed_payload.clone(),
            signature: entry.signature.clone(),
            control_key_fingerprint: entry.control_key_fingerprint.clone(),
        };
        verify_entry(response, index as u64 + 1, trust)?;
    }
    Ok(())
}

fn reject_revoked_device(
    cached: &CachedControlRevocations,
    identity: &DeviceIdentity,
    device: &Value,
) -> Result<(), String> {
    let device_id = device
        .get("id")
        .and_then(Value::as_u64)
        .map(|id| id.to_string());
    if cached.entries.iter().any(|entry| {
        entry.subject_type == "DEVICE"
            && (device_id.as_deref() == Some(entry.subject_id.as_str())
                || entry.device_key.as_deref() == Some(identity.device_key.as_str()))
    }) {
        Err("this device appears in the signed Control revocation snapshot".to_string())
    } else {
        Ok(())
    }
}

fn load_cache(path: &PathBuf) -> HashMap<String, CachedControlRevocations> {
    let Ok(contents) = fs::read(path) else {
        return HashMap::new();
    };
    let Ok(cache) = serde_json::from_slice::<RevocationCacheFile>(&contents) else {
        return HashMap::new();
    };
    if cache.format_version != CACHE_FORMAT_VERSION {
        return HashMap::new();
    }
    cache
        .controls
        .into_iter()
        .map(|control| (control.control_id.clone(), control))
        .collect()
}

fn persist_cache(
    path: &PathBuf,
    mut controls: Vec<CachedControlRevocations>,
) -> Result<(), String> {
    controls.sort_by(|left, right| left.control_id.cmp(&right.control_id));
    let parent = path
        .parent()
        .ok_or_else(|| "revocation cache path was invalid".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|_| "failed to create the revocation cache directory".to_string())?;
    let contents = serde_json::to_vec_pretty(&RevocationCacheFile {
        format_version: CACHE_FORMAT_VERSION,
        controls,
    })
    .map_err(|_| "failed to encode the revocation cache".to_string())?;
    let temporary = path.with_extension("json.tmp");
    fs::write(&temporary, contents)
        .map_err(|_| "failed to write the revocation cache".to_string())?;
    fs::rename(temporary, path).map_err(|_| "failed to replace the revocation cache".to_string())
}

#[cfg(test)]
mod tests {
    use super::{
        reject_revoked_device, verify_cached_snapshot, verify_entry, CachedControlRevocations,
        RevocationEntryResponse, RevocationState, VerifiedRevocationEntry,
    };
    use crate::device_identity::{ControlTrustAnchor, DeviceIdentity};
    use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
    use base64::Engine;
    use ed25519_dalek::pkcs8::EncodePublicKey;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::json;
    use sha2::{Digest, Sha256};
    use std::fs;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::path::PathBuf;
    use std::thread;
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    struct TestDirectory(PathBuf);

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn verifies_signed_revocation_and_rejects_claim_tampering() {
        let (trust, signing_key) = test_trust();
        let entry = signed_entry(1, "99", "desktop-revoked", &trust, &signing_key);
        let verified = verify_entry(entry.clone(), 1, &trust).unwrap();
        assert_eq!(verified.device_key.as_deref(), Some("desktop-revoked"));

        let mut tampered = entry;
        tampered.reason = "DIFFERENT_REASON".to_string();
        assert_eq!(
            verify_entry(tampered, 1, &trust).unwrap_err(),
            "revocation payload claims did not match the entry"
        );

        let mut bad_signature = signed_entry(1, "99", "desktop-revoked", &trust, &signing_key);
        bad_signature.signature = URL_SAFE_NO_PAD.encode([0_u8; 64]);
        assert!(verify_entry(bad_signature, 1, &trust).is_err());
        assert!(verify_entry(
            signed_entry(1, "99", "desktop-revoked", &trust, &signing_key),
            2,
            &trust,
        )
        .is_err());
    }

    #[test]
    fn rejects_the_current_device_by_id_or_stable_device_key() {
        let (trust, signing_key) = test_trust();
        let entry = verify_entry(
            signed_entry(1, "42", "desktop-current", &trust, &signing_key),
            1,
            &trust,
        )
        .unwrap();
        let cached = cached_snapshot(&trust, vec![entry]);
        let identity = test_identity([7_u8; 32], "desktop-current");
        assert!(reject_revoked_device(&cached, &identity, &json!({"id": 42})).is_err());
        assert!(reject_revoked_device(&cached, &identity, &json!({"id": 43})).is_err());
    }

    #[tokio::test]
    async fn synchronizes_and_verifies_the_incremental_control_snapshot() {
        let (trust, signing_key) = test_trust();
        let identity = test_identity([7_u8; 32], "desktop-current");
        let entry = signed_entry(1, "99", "desktop-other", &trust, &signing_key);
        let page = json!({
            "organizationId": trust.organization_id.clone(),
            "currentVersion": 1,
            "afterVersion": 0,
            "controlSigningPublicKey": trust.public_key.clone(),
            "controlKeyFingerprint": trust.fingerprint.clone(),
            "entries": [{
                "version": entry.version,
                "subjectType": entry.subject_type,
                "subjectId": entry.subject_id,
                "reason": entry.reason,
                "revokedAt": entry.revoked_at,
                "expiresAt": entry.expires_at,
                "signedPayload": entry.signed_payload,
                "signature": entry.signature,
                "controlKeyFingerprint": entry.control_key_fingerprint,
            }]
        });
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let request = read_http_request(&mut stream);
            let headers = request.split_once("\r\n\r\n").unwrap().0;
            assert!(headers.starts_with(
                "GET /api/v2/control/devices/revocations?afterVersion=0&limit=500 HTTP/1.1"
            ));
            assert!(headers
                .to_ascii_lowercase()
                .contains("authorization: bearer test-access-token"));
            let response = json!({"code": 200, "msg": "ok", "data": page}).to_string();
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                response.len(),
                response
            )
            .unwrap();
        });

        let temporary = TestDirectory(std::env::temp_dir().join(format!(
            "meshx-revocations-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )));
        let cache_path = temporary.0.join("revocations.json");
        let state = RevocationState::new(cache_path.clone());
        let version = state
            .sync(
                &origin,
                &reqwest::Client::builder().no_proxy().build().unwrap(),
                "test-access-token",
                &trust,
                &identity,
                &json!({"id": 42}),
            )
            .await
            .unwrap();
        assert_eq!(version, 1);
        server.join().unwrap();
        assert!(cache_path.is_file());
        let reloaded = RevocationState::new(cache_path);
        let reloaded = reloaded.cached_for(&trust).unwrap();
        assert_eq!(reloaded.current_version, 1);
        assert!(verify_cached_snapshot(&reloaded, &trust).is_ok());
    }

    fn test_trust() -> (ControlTrustAnchor, SigningKey) {
        let signing_key = SigningKey::from_bytes(&[9_u8; 32]);
        let public_der = signing_key.verifying_key().to_public_key_der().unwrap();
        let public_key = STANDARD.encode(public_der.as_bytes());
        let fingerprint = format!("{:x}", Sha256::digest(public_der.as_bytes()));
        (
            ControlTrustAnchor {
                control_id: "control-test".to_string(),
                organization_id: "org-test".to_string(),
                public_key,
                fingerprint,
            },
            signing_key,
        )
    }

    fn test_identity(seed: [u8; 32], device_key: &str) -> DeviceIdentity {
        let public_der = SigningKey::from_bytes(&seed)
            .verifying_key()
            .to_public_key_der()
            .unwrap();
        DeviceIdentity {
            device_key: device_key.to_string(),
            public_key: STANDARD.encode(public_der.as_bytes()),
            fingerprint: format!("{:x}", Sha256::digest(public_der.as_bytes())),
        }
    }

    fn signed_entry(
        version: u64,
        subject_id: &str,
        device_key: &str,
        trust: &ControlTrustAnchor,
        signing_key: &SigningKey,
    ) -> RevocationEntryResponse {
        let payload = json!({
            "type": "MESHX_REVOCATION",
            "version": version,
            "organizationId": trust.organization_id.clone(),
            "controlId": trust.control_id.clone(),
            "subjectType": "DEVICE",
            "subjectId": subject_id,
            "deviceKey": device_key,
            "reason": "LOST_DEVICE",
            "revokedAt": "2026-08-11T10:00:00",
        })
        .to_string();
        RevocationEntryResponse {
            version,
            subject_type: "DEVICE".to_string(),
            subject_id: subject_id.to_string(),
            reason: "LOST_DEVICE".to_string(),
            revoked_at: "2026-08-11T10:00:00".to_string(),
            expires_at: None,
            signed_payload: payload.clone(),
            signature: URL_SAFE_NO_PAD.encode(signing_key.sign(payload.as_bytes()).to_bytes()),
            control_key_fingerprint: trust.fingerprint.clone(),
        }
    }

    fn cached_snapshot(
        trust: &ControlTrustAnchor,
        entries: Vec<VerifiedRevocationEntry>,
    ) -> CachedControlRevocations {
        CachedControlRevocations {
            control_id: trust.control_id.clone(),
            organization_id: trust.organization_id.clone(),
            current_version: entries.last().map(|entry| entry.version).unwrap_or(0),
            control_key_fingerprint: trust.fingerprint.clone(),
            synced_at: "2026-08-11T10:00:00Z".to_string(),
            entries,
        }
    }

    fn read_http_request(stream: &mut impl Read) -> String {
        let mut bytes = Vec::new();
        let mut buffer = [0_u8; 4096];
        loop {
            let read = stream.read(&mut buffer).unwrap();
            assert!(read > 0);
            bytes.extend_from_slice(&buffer[..read]);
            if bytes.windows(4).any(|window| window == b"\r\n\r\n") {
                return String::from_utf8(bytes).unwrap();
            }
        }
    }
}
