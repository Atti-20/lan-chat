use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine;
use ed25519_dalek::pkcs8::{DecodePublicKey, EncodePublicKey};
use ed25519_dalek::{Signature, SigningKey, Verifier, VerifyingKey};
use keyring::{Entry, Error as KeyringError};
use rand::rngs::OsRng;
use serde_json::Value;
use sha2::{Digest, Sha256};

const KEYRING_SERVICE: &str = "cc.atti.meshx.desktop.device-identity";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeviceIdentity {
    pub device_key: String,
    pub public_key: String,
    pub fingerprint: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ControlTrustAnchor {
    pub control_id: String,
    pub organization_id: String,
    pub public_key: String,
    pub fingerprint: String,
}

#[derive(Default)]
pub struct DeviceIdentityState;

impl DeviceIdentityState {
    pub async fn identity_for(&self, control_id: String) -> Result<DeviceIdentity, String> {
        tauri::async_runtime::spawn_blocking(move || load_or_create_identity(&control_id))
            .await
            .map_err(|_| "device identity task did not complete".to_string())?
    }
}

pub fn validate_control_trust_anchor(
    control_id: &str,
    organization_id: &str,
    public_key: &str,
    fingerprint: &str,
) -> Result<ControlTrustAnchor, String> {
    if control_id.trim().is_empty() {
        return Err("Control device identity did not include a control ID".to_string());
    }
    if organization_id.trim().is_empty() {
        return Err("Control device identity did not include an organization".to_string());
    }
    let key = decode_public_key(public_key, "Control signing public key")?;
    let normalized_public_key = STANDARD.encode(
        key.to_public_key_der()
            .map_err(|_| "Control signing public key could not be normalized".to_string())?
            .as_bytes(),
    );
    let actual_fingerprint = sha256_hex(
        &STANDARD
            .decode(&normalized_public_key)
            .map_err(|_| "Control signing public key is invalid".to_string())?,
    );
    if actual_fingerprint != fingerprint.to_ascii_lowercase() {
        return Err("Control signing public key fingerprint did not match".to_string());
    }
    Ok(ControlTrustAnchor {
        control_id: control_id.to_string(),
        organization_id: organization_id.to_string(),
        public_key: normalized_public_key,
        fingerprint: actual_fingerprint,
    })
}

pub fn verify_active_credential(
    device: &Value,
    identity: &DeviceIdentity,
    trust: &ControlTrustAnchor,
) -> Result<(), String> {
    if device.get("status").and_then(Value::as_str) != Some("ACTIVE") {
        return Err("device registration is waiting for administrator approval".to_string());
    }
    let credential = device
        .get("credential")
        .ok_or_else(|| "Control did not return a device credential".to_string())?;
    if credential.get("status").and_then(Value::as_str) != Some("ACTIVE") {
        return Err("device credential is waiting for administrator approval".to_string());
    }
    if credential.get("fingerprint").and_then(Value::as_str) != Some(identity.fingerprint.as_str())
    {
        return Err(
            "device credential fingerprint did not match the Keychain identity".to_string(),
        );
    }
    if credential
        .get("controlKeyFingerprint")
        .and_then(Value::as_str)
        != Some(trust.fingerprint.as_str())
    {
        return Err("device credential was signed by an unexpected Control key".to_string());
    }
    if credential
        .get("controlSigningPublicKey")
        .and_then(Value::as_str)
        != Some(trust.public_key.as_str())
    {
        return Err("device credential Control public key did not match discovery".to_string());
    }
    let payload = credential
        .get("certificatePayload")
        .and_then(Value::as_str)
        .ok_or_else(|| "device certificate payload is missing".to_string())?;
    let signature = credential
        .get("certificateSignature")
        .and_then(Value::as_str)
        .ok_or_else(|| "device certificate signature is missing".to_string())?;
    let claims: Value = serde_json::from_str(payload)
        .map_err(|_| "device certificate payload is invalid".to_string())?;
    if claims.get("type").and_then(Value::as_str) != Some("MESHX_DEVICE_CERTIFICATE")
        || claims.get("version").and_then(Value::as_u64) != Some(1)
        || claims.get("algorithm").and_then(Value::as_str) != Some("ED25519")
        || claims.get("controlId").and_then(Value::as_str) != Some(trust.control_id.as_str())
        || claims.get("organizationId").and_then(Value::as_str)
            != Some(trust.organization_id.as_str())
        || claims.get("deviceKey").and_then(Value::as_str) != Some(identity.device_key.as_str())
        || claims.get("publicKeyFingerprint").and_then(Value::as_str)
            != Some(identity.fingerprint.as_str())
    {
        return Err("device certificate claims did not match this device".to_string());
    }
    verify_control_signature(payload, signature, trust)
        .map_err(|_| "device certificate signature verification failed".to_string())
}

pub fn verify_control_signature(
    payload: &str,
    signature: &str,
    trust: &ControlTrustAnchor,
) -> Result<(), String> {
    let signature = URL_SAFE_NO_PAD
        .decode(signature)
        .map_err(|_| "Control signature is invalid".to_string())?;
    let signature = Signature::try_from(signature.as_slice())
        .map_err(|_| "Control signature is invalid".to_string())?;
    decode_public_key(&trust.public_key, "Control signing public key")?
        .verify(payload.as_bytes(), &signature)
        .map_err(|_| "Control signature verification failed".to_string())
}

fn load_or_create_identity(control_id: &str) -> Result<DeviceIdentity, String> {
    let account = format!("control-{}", sha256_hex(control_id.as_bytes()));
    let entry = Entry::new(KEYRING_SERVICE, &account)
        .map_err(|_| "failed to open the operating system credential store".to_string())?;
    let secret = match entry.get_secret() {
        Ok(secret) => secret,
        Err(KeyringError::NoEntry) => {
            let signing_key = SigningKey::generate(&mut OsRng);
            let secret = signing_key.to_bytes().to_vec();
            entry.set_secret(&secret).map_err(|_| {
                "failed to save the device private key in the credential store".to_string()
            })?;
            secret
        }
        Err(_) => {
            return Err(
                "failed to read the device private key from the credential store".to_string(),
            )
        }
    };
    identity_from_secret(&secret)
}

fn identity_from_secret(secret: &[u8]) -> Result<DeviceIdentity, String> {
    let secret: [u8; 32] = secret.try_into().map_err(|_| {
        "stored device private key is invalid; remove it before registering again".to_string()
    })?;
    let signing_key = SigningKey::from_bytes(&secret);
    let public_der = signing_key
        .verifying_key()
        .to_public_key_der()
        .map_err(|_| "failed to encode the device public key".to_string())?;
    let fingerprint = sha256_hex(public_der.as_bytes());
    Ok(DeviceIdentity {
        device_key: format!("desktop-{}", &fingerprint[..32]),
        public_key: STANDARD.encode(public_der.as_bytes()),
        fingerprint,
    })
}

fn decode_public_key(value: &str, label: &str) -> Result<VerifyingKey, String> {
    let der = STANDARD
        .decode(value.trim())
        .map_err(|_| format!("{label} is not valid Base64"))?;
    VerifyingKey::from_public_key_der(&der).map_err(|_| format!("{label} is not Ed25519"))
}

fn sha256_hex(value: &[u8]) -> String {
    format!("{:x}", Sha256::digest(value))
}

#[cfg(test)]
mod tests {
    use super::{
        identity_from_secret, load_or_create_identity, sha256_hex, validate_control_trust_anchor,
        verify_active_credential, KEYRING_SERVICE,
    };
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};
    use keyring::Entry;
    use serde_json::json;
    use std::time::{SystemTime, UNIX_EPOCH};

    struct KeyringCleanup(Entry);

    impl Drop for KeyringCleanup {
        fn drop(&mut self) {
            let _ = self.0.delete_credential();
        }
    }

    #[test]
    fn deterministic_secret_produces_stable_x509_identity() {
        let identity = identity_from_secret(&[7_u8; 32]).unwrap();
        assert!(identity.device_key.starts_with("desktop-"));
        assert_eq!(identity.device_key.len(), 40);
        assert_eq!(identity.fingerprint.len(), 64);
        assert!(identity.public_key.starts_with("MCowBQYDK2VwAyEA"));
        assert_eq!(identity, identity_from_secret(&[7_u8; 32]).unwrap());
    }

    #[test]
    #[ignore = "mutates and then cleans up the operating system credential store"]
    fn operating_system_credential_store_round_trip_is_stable() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let control_id = format!("control-keyring-test-{}-{nonce}", std::process::id());
        let account = format!("control-{}", sha256_hex(control_id.as_bytes()));
        let cleanup = KeyringCleanup(Entry::new(KEYRING_SERVICE, &account).unwrap());

        let first = load_or_create_identity(&control_id).unwrap();
        let second = load_or_create_identity(&control_id).unwrap();
        assert_eq!(first, second);
        assert_eq!(cleanup.0.get_secret().unwrap().len(), 32);
        cleanup.0.delete_credential().unwrap();
        assert!(matches!(
            cleanup.0.get_secret(),
            Err(keyring::Error::NoEntry)
        ));
        std::mem::forget(cleanup);
    }

    #[test]
    fn rejects_control_fingerprint_mismatch() {
        let identity = identity_from_secret(&[9_u8; 32]).unwrap();
        assert!(validate_control_trust_anchor(
            "control-test",
            "org-test",
            &identity.public_key,
            &"0".repeat(64),
        )
        .is_err());
    }

    #[test]
    fn verifies_control_signed_device_certificate_and_rejects_tampering() {
        let identity = identity_from_secret(&[7_u8; 32]).unwrap();
        let control_identity = identity_from_secret(&[9_u8; 32]).unwrap();
        let trust = validate_control_trust_anchor(
            "control-test",
            "org-test",
            &control_identity.public_key,
            &control_identity.fingerprint,
        )
        .unwrap();
        let payload = json!({
            "type": "MESHX_DEVICE_CERTIFICATE",
            "version": 1,
            "credentialId": "cred-test",
            "organizationId": "org-test",
            "controlId": "control-test",
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
        let signature = SigningKey::from_bytes(&[9_u8; 32]).sign(payload.as_bytes());
        let mut device = json!({
            "status": "ACTIVE",
            "credential": {
                "status": "ACTIVE",
                "fingerprint": identity.fingerprint.clone(),
                "controlKeyFingerprint": trust.fingerprint.clone(),
                "controlSigningPublicKey": trust.public_key.clone(),
                "certificatePayload": payload.clone(),
                "certificateSignature": URL_SAFE_NO_PAD.encode(signature.to_bytes()),
            }
        });

        assert!(verify_active_credential(&device, &identity, &trust).is_ok());
        device["credential"]["certificatePayload"] = json!(format!("{payload} "));
        assert_eq!(
            verify_active_credential(&device, &identity, &trust).unwrap_err(),
            "device certificate signature verification failed"
        );
    }
}
