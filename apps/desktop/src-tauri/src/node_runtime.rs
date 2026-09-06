use std::fs;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use chrono::Utc;
use rusqlite::{params, Connection, OpenFlags, OptionalExtension, TransactionBehavior};
use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::device_identity::{ControlTrustAnchor, DeviceIdentity};

const CURRENT_SCHEMA_VERSION: u32 = 1;
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const REQUIRED_TABLES: &[&str] = &[
    "schema_migration",
    "node_identity",
    "peer_node",
    "local_conversation",
    "local_message",
    "message_outbox",
    "message_receipt",
    "local_file",
    "file_transfer_session",
    "sync_cursor",
    "control_snapshot",
    "revocation_snapshot",
];

const MIGRATION_V1: &str = r#"
CREATE TABLE schema_migration (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    applied_at TEXT NOT NULL
) STRICT;

CREATE TABLE node_identity (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    control_id TEXT NOT NULL,
    organization_id TEXT NOT NULL,
    account_user_id INTEGER NOT NULL,
    member_id INTEGER NOT NULL,
    device_id INTEGER NOT NULL,
    device_key TEXT NOT NULL,
    node_id TEXT NOT NULL,
    algorithm TEXT NOT NULL CHECK (algorithm = 'ED25519'),
    public_key TEXT NOT NULL,
    public_key_fingerprint TEXT NOT NULL,
    credential_id TEXT NOT NULL,
    certificate_payload TEXT NOT NULL,
    certificate_signature TEXT NOT NULL,
    certificate_issued_at TEXT NOT NULL,
    certificate_expires_at TEXT NOT NULL,
    max_offline_hours INTEGER NOT NULL CHECK (max_offline_hours > 0),
    revocation_version INTEGER NOT NULL CHECK (revocation_version >= 0),
    updated_at TEXT NOT NULL,
    UNIQUE (organization_id, account_user_id, device_key),
    UNIQUE (node_id)
) STRICT;

CREATE TABLE peer_node (
    node_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    device_key TEXT NOT NULL,
    public_key_fingerprint TEXT NOT NULL,
    certificate_payload TEXT NOT NULL,
    certificate_signature TEXT NOT NULL,
    endpoint TEXT,
    capabilities_json TEXT NOT NULL DEFAULT '[]',
    status TEXT NOT NULL,
    last_seen_at TEXT,
    updated_at TEXT NOT NULL
) STRICT;

CREATE TABLE local_conversation (
    conversation_id TEXT PRIMARY KEY,
    conversation_type TEXT NOT NULL,
    title TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    last_sequence INTEGER NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
) STRICT;

CREATE TABLE local_message (
    message_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    client_msg_id TEXT NOT NULL,
    sender_user_id INTEGER NOT NULL,
    sender_node_id TEXT,
    message_type TEXT NOT NULL,
    content_json TEXT NOT NULL,
    sequence INTEGER CHECK (sequence IS NULL OR sequence >= 0),
    delivery_status TEXT NOT NULL,
    sent_at TEXT NOT NULL,
    received_at TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (conversation_id, client_msg_id),
    FOREIGN KEY (conversation_id) REFERENCES local_conversation(conversation_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) STRICT;

CREATE INDEX idx_local_message_conversation_sequence
    ON local_message(conversation_id, sequence, sent_at);

CREATE TABLE message_outbox (
    client_msg_id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES local_conversation(conversation_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) STRICT;

CREATE INDEX idx_message_outbox_pending
    ON message_outbox(status, next_attempt_at, created_at);

CREATE TABLE message_receipt (
    message_id TEXT NOT NULL,
    peer_node_id TEXT NOT NULL,
    receipt_type TEXT NOT NULL,
    received_at TEXT NOT NULL,
    PRIMARY KEY (message_id, peer_node_id, receipt_type),
    FOREIGN KEY (message_id) REFERENCES local_message(message_id)
        ON UPDATE CASCADE ON DELETE CASCADE
) STRICT;

CREATE TABLE local_file (
    file_id TEXT PRIMARY KEY,
    relative_path TEXT NOT NULL,
    display_name TEXT NOT NULL,
    mime_type TEXT,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    sha256 TEXT NOT NULL,
    state TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (relative_path),
    UNIQUE (sha256, size_bytes)
) STRICT;

CREATE TABLE file_transfer_session (
    transfer_id TEXT PRIMARY KEY,
    file_id TEXT NOT NULL,
    peer_node_id TEXT NOT NULL,
    direction TEXT NOT NULL,
    state TEXT NOT NULL,
    chunk_size INTEGER NOT NULL CHECK (chunk_size > 0),
    total_chunks INTEGER NOT NULL CHECK (total_chunks >= 0),
    completed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (completed_chunks >= 0),
    resume_token TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (file_id) REFERENCES local_file(file_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) STRICT;

CREATE INDEX idx_file_transfer_active
    ON file_transfer_session(state, updated_at);

CREATE TABLE sync_cursor (
    scope TEXT NOT NULL,
    cursor_key TEXT NOT NULL,
    cursor_value TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (scope, cursor_key)
) STRICT;

CREATE TABLE control_snapshot (
    control_id TEXT PRIMARY KEY,
    organization_id TEXT NOT NULL,
    policy_version INTEGER,
    revocation_version INTEGER NOT NULL DEFAULT 0 CHECK (revocation_version >= 0),
    snapshot_json TEXT NOT NULL,
    fetched_at TEXT NOT NULL
) STRICT;

CREATE TABLE revocation_snapshot (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    subject_type TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    device_key TEXT,
    reason TEXT NOT NULL,
    signed_payload TEXT NOT NULL,
    signature TEXT NOT NULL,
    control_key_fingerprint TEXT NOT NULL,
    revoked_at TEXT NOT NULL,
    expires_at TEXT
) STRICT;

CREATE INDEX idx_revocation_snapshot_subject
    ON revocation_snapshot(subject_type, subject_id, version);
"#;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NodeRuntimeIdentityKey {
    pub control_id: String,
    pub organization_id: String,
    pub account_user_id: u64,
    pub device_key: String,
}

#[derive(Clone, Debug)]
pub struct NodeRuntimeContext {
    pub key: NodeRuntimeIdentityKey,
    pub member_id: u64,
    pub device_id: u64,
    pub node_id: String,
    pub algorithm: String,
    pub public_key: String,
    pub public_key_fingerprint: String,
    pub credential_id: String,
    pub certificate_payload: String,
    pub certificate_signature: String,
    pub certificate_issued_at: String,
    pub certificate_expires_at: String,
    pub max_offline_hours: u64,
    pub revocation_version: u64,
}

impl NodeRuntimeContext {
    pub fn from_verified_session(
        auth: &Value,
        device: &Value,
        trust: &ControlTrustAnchor,
        identity: &DeviceIdentity,
        revocation_version: u64,
    ) -> Result<Self, String> {
        let account_user_id = required_u64(auth, "userId", "authenticated user ID")?;
        let device_id = required_u64(device, "id", "registered device ID")?;
        if device.get("deviceKey").and_then(Value::as_str) != Some(identity.device_key.as_str()) {
            return Err("registered device key did not match the native identity".to_string());
        }
        let credential = device
            .get("credential")
            .ok_or_else(|| "registered device credential was missing".to_string())?;
        let certificate_payload = required_string(
            credential,
            "certificatePayload",
            "device certificate payload",
        )?;
        let claims: Value = serde_json::from_str(&certificate_payload)
            .map_err(|_| "device certificate payload was invalid".to_string())?;
        let member_id = required_u64(&claims, "memberId", "device certificate member ID")?;
        if required_u64(&claims, "deviceId", "device certificate device ID")? != device_id {
            return Err("device certificate ID did not match registration".to_string());
        }
        let public_key_fingerprint =
            required_string(credential, "fingerprint", "device credential fingerprint")?;
        if public_key_fingerprint != identity.fingerprint {
            return Err(
                "device credential fingerprint did not match the native identity".to_string(),
            );
        }
        Ok(Self {
            key: NodeRuntimeIdentityKey {
                control_id: trust.control_id.clone(),
                organization_id: trust.organization_id.clone(),
                account_user_id,
                device_key: identity.device_key.clone(),
            },
            member_id,
            device_id,
            node_id: format!(
                "node-{}",
                identity
                    .fingerprint
                    .get(..32)
                    .ok_or_else(|| "native identity fingerprint was invalid".to_string())?
            ),
            algorithm: "ED25519".to_string(),
            public_key: identity.public_key.clone(),
            public_key_fingerprint,
            credential_id: required_string(credential, "credentialId", "device credential ID")?,
            certificate_payload,
            certificate_signature: required_string(
                credential,
                "certificateSignature",
                "device certificate signature",
            )?,
            certificate_issued_at: required_string(
                &claims,
                "issuedAt",
                "device certificate issue time",
            )?,
            certificate_expires_at: required_string(
                &claims,
                "expiresAt",
                "device certificate expiry time",
            )?,
            max_offline_hours: required_u64(
                &claims,
                "maxOfflineHours",
                "device certificate offline limit",
            )?,
            revocation_version,
        })
    }
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeRuntimeStatus {
    pub running: bool,
    pub active: bool,
    pub node_id: Option<String>,
    pub organization_id: Option<String>,
    pub account_user_id: Option<u64>,
    pub device_key: Option<String>,
    pub storage_scope_id: Option<String>,
    pub schema_version: Option<u32>,
    pub last_error: Option<String>,
}

enum RuntimeCommand {
    Activate {
        context: Box<NodeRuntimeContext>,
        reply: SyncSender<Result<NodeRuntimeStatus, String>>,
    },
    Deactivate {
        expected: NodeRuntimeIdentityKey,
        reply: SyncSender<Result<(), String>>,
    },
    Shutdown {
        reply: SyncSender<Result<(), String>>,
    },
}

struct ActiveDatabase {
    context: NodeRuntimeContext,
    path: PathBuf,
    connection: Connection,
}

pub struct NodeRuntimeState {
    sender: SyncSender<RuntimeCommand>,
    status: Arc<Mutex<NodeRuntimeStatus>>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl NodeRuntimeState {
    pub fn new(base_dir: PathBuf) -> Result<Arc<Self>, String> {
        let (sender, receiver) = mpsc::sync_channel(32);
        let (ready_sender, ready_receiver) = mpsc::sync_channel(1);
        let status = Arc::new(Mutex::new(NodeRuntimeStatus::default()));
        let worker_status = Arc::clone(&status);
        let worker = thread::Builder::new()
            .name("meshx-node-runtime".to_string())
            .spawn(move || worker_loop(base_dir, receiver, worker_status, ready_sender))
            .map_err(|error| format!("failed to start Node Runtime worker: {error}"))?;
        ready_receiver
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|_| "Node Runtime worker did not initialize".to_string())??;
        Ok(Arc::new(Self {
            sender,
            status,
            worker: Mutex::new(Some(worker)),
        }))
    }

    pub async fn activate(&self, context: NodeRuntimeContext) -> Result<NodeRuntimeStatus, String> {
        send_async(self.sender.clone(), move |reply| RuntimeCommand::Activate {
            context: Box::new(context),
            reply,
        })
        .await
    }

    pub async fn deactivate(&self, expected: NodeRuntimeIdentityKey) -> Result<(), String> {
        send_async(self.sender.clone(), move |reply| {
            RuntimeCommand::Deactivate { expected, reply }
        })
        .await
    }

    pub fn snapshot(&self) -> NodeRuntimeStatus {
        self.status
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clone()
    }

    pub fn shutdown(&self) -> Result<(), String> {
        let worker = self
            .worker
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .take();
        let Some(worker) = worker else {
            return Ok(());
        };
        let (reply_sender, reply_receiver) = mpsc::sync_channel(1);
        let send_result = self.sender.send(RuntimeCommand::Shutdown {
            reply: reply_sender,
        });
        let shutdown_result = if send_result.is_ok() {
            reply_receiver
                .recv_timeout(COMMAND_TIMEOUT)
                .map_err(|_| "Node Runtime shutdown timed out".to_string())?
        } else {
            Err("Node Runtime worker stopped unexpectedly".to_string())
        };
        worker
            .join()
            .map_err(|_| "Node Runtime worker panicked".to_string())?;
        shutdown_result
    }
}

impl Drop for NodeRuntimeState {
    fn drop(&mut self) {
        let _ = self.shutdown();
    }
}

#[tauri::command]
pub fn node_runtime_status(state: tauri::State<'_, Arc<NodeRuntimeState>>) -> NodeRuntimeStatus {
    state.snapshot()
}

async fn send_async<T: Send + 'static>(
    sender: SyncSender<RuntimeCommand>,
    command: impl FnOnce(SyncSender<Result<T, String>>) -> RuntimeCommand + Send + 'static,
) -> Result<T, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let (reply_sender, reply_receiver) = mpsc::sync_channel(1);
        sender
            .send(command(reply_sender))
            .map_err(|_| "Node Runtime worker is unavailable".to_string())?;
        reply_receiver
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|_| "Node Runtime command timed out".to_string())?
    })
    .await
    .map_err(|_| "Node Runtime command task did not complete".to_string())?
}

fn worker_loop(
    base_dir: PathBuf,
    receiver: Receiver<RuntimeCommand>,
    status: Arc<Mutex<NodeRuntimeStatus>>,
    ready: SyncSender<Result<(), String>>,
) {
    if let Err(error) = fs::create_dir_all(&base_dir)
        .map_err(|_| "failed to create the Node Runtime data directory".to_string())
    {
        set_runtime_error(&status, &error);
        let _ = ready.send(Err(error));
        return;
    }
    update_status(&status, |current| {
        current.running = true;
        current.last_error = None;
    });
    let _ = ready.send(Ok(()));
    let mut active: Option<ActiveDatabase> = None;

    while let Ok(command) = receiver.recv() {
        match command {
            RuntimeCommand::Activate { context, reply } => {
                let result = activate_context(&base_dir, &mut active, *context).map(|database| {
                    let snapshot = status_for_database(database);
                    update_status(&status, |current| *current = snapshot.clone());
                    snapshot
                });
                if let Err(error) = &result {
                    let active_snapshot = active.as_ref().map(status_for_database);
                    update_status(&status, |current| {
                        *current = active_snapshot.unwrap_or(NodeRuntimeStatus {
                            running: true,
                            ..NodeRuntimeStatus::default()
                        });
                        current.last_error = Some(error.clone());
                    });
                }
                let _ = reply.send(result);
            }
            RuntimeCommand::Deactivate { expected, reply } => {
                let result = deactivate_context(&mut active, &expected);
                match &result {
                    Ok(()) => update_status(&status, |current| {
                        if active.is_none() {
                            *current = NodeRuntimeStatus {
                                running: true,
                                ..NodeRuntimeStatus::default()
                            };
                        }
                    }),
                    Err(error) => set_runtime_error(&status, error),
                }
                let _ = reply.send(result);
            }
            RuntimeCommand::Shutdown { reply } => {
                let result = close_active_database(active.take());
                update_status(&status, |current| {
                    *current = NodeRuntimeStatus::default();
                    if let Err(error) = &result {
                        current.last_error = Some(error.clone());
                    }
                });
                let _ = reply.send(result);
                break;
            }
        }
    }
    if active.is_some() {
        let _ = close_active_database(active.take());
    }
    update_status(&status, |current| current.running = false);
}

fn activate_context<'a>(
    base_dir: &Path,
    active: &'a mut Option<ActiveDatabase>,
    context: NodeRuntimeContext,
) -> Result<&'a ActiveDatabase, String> {
    let same_context = active
        .as_ref()
        .is_some_and(|database| database.context.key == context.key);
    if same_context {
        let database = active.as_mut().expect("active database exists");
        upsert_node_identity(&database.connection, &context)?;
        database.context = context;
        return Ok(database);
    }
    close_active_database(active.take())?;
    let database = open_context_database(base_dir, context)?;
    *active = Some(database);
    Ok(active.as_ref().expect("activated database exists"))
}

fn deactivate_context(
    active: &mut Option<ActiveDatabase>,
    expected: &NodeRuntimeIdentityKey,
) -> Result<(), String> {
    if active
        .as_ref()
        .is_some_and(|database| &database.context.key == expected)
    {
        close_active_database(active.take())?;
    }
    Ok(())
}

fn open_context_database(
    base_dir: &Path,
    context: NodeRuntimeContext,
) -> Result<ActiveDatabase, String> {
    let path = context_database_path(base_dir, &context.key);
    let parent = path
        .parent()
        .ok_or_else(|| "Node Runtime database path was invalid".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|_| "failed to create the scoped Node Runtime directory".to_string())?;
    let mut connection = Connection::open_with_flags(
        &path,
        OpenFlags::SQLITE_OPEN_READ_WRITE
            | OpenFlags::SQLITE_OPEN_CREATE
            | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|error| database_error(&path, "open", error))?;
    connection
        .busy_timeout(Duration::from_secs(5))
        .map_err(|error| database_error(&path, "configure timeout", error))?;
    connection
        .execute_batch(
            "PRAGMA foreign_keys=ON;
             PRAGMA synchronous=FULL;
             PRAGMA secure_delete=ON;
             PRAGMA trusted_schema=OFF;",
        )
        .map_err(|error| database_error(&path, "apply safety pragmas", error))?;
    let journal_mode: String = connection
        .query_row("PRAGMA journal_mode=WAL", [], |row| row.get(0))
        .map_err(|error| database_error(&path, "enable WAL", error))?;
    if !journal_mode.eq_ignore_ascii_case("wal") {
        return Err(format!(
            "Node Runtime database did not enter WAL mode: {}",
            path.display()
        ));
    }
    let integrity: String = connection
        .query_row("PRAGMA quick_check", [], |row| row.get(0))
        .map_err(|error| database_error(&path, "run integrity check", error))?;
    if integrity != "ok" {
        return Err(format!(
            "Node Runtime database failed integrity check and was preserved for recovery: {}",
            path.display()
        ));
    }
    migrate_database(&mut connection, &path)?;
    validate_database_schema(&connection, &path)?;
    upsert_node_identity(&connection, &context)?;
    Ok(ActiveDatabase {
        context,
        path,
        connection,
    })
}

fn migrate_database(connection: &mut Connection, path: &Path) -> Result<(), String> {
    let version: u32 = connection
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .map_err(|error| database_error(path, "read schema version", error))?;
    if version > CURRENT_SCHEMA_VERSION {
        return Err(format!(
            "Node Runtime database schema {version} is newer than supported version {CURRENT_SCHEMA_VERSION}: {}",
            path.display()
        ));
    }
    if version == 0 {
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|error| database_error(path, "start migration", error))?;
        transaction
            .execute_batch(MIGRATION_V1)
            .map_err(|error| database_error(path, "apply migration 1", error))?;
        transaction
            .execute(
                "INSERT INTO schema_migration(version, description, applied_at)
                 VALUES (?1, ?2, ?3)",
                params![1_u32, "initial full-node storage", Utc::now().to_rfc3339()],
            )
            .map_err(|error| database_error(path, "record migration 1", error))?;
        transaction
            .pragma_update(None, "user_version", 1_u32)
            .map_err(|error| database_error(path, "record schema version", error))?;
        transaction
            .commit()
            .map_err(|error| database_error(path, "commit migration 1", error))?;
    }
    Ok(())
}

fn upsert_node_identity(
    connection: &Connection,
    context: &NodeRuntimeContext,
) -> Result<(), String> {
    let existing: Option<(String, String, u64, String)> = connection
        .query_row(
            "SELECT control_id, organization_id, account_user_id, device_key
             FROM node_identity WHERE singleton_id = 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )
        .optional()
        .map_err(|error| format!("failed to read scoped node identity: {error}"))?;
    if existing.as_ref().is_some_and(|existing| {
        existing
            != &(
                context.key.control_id.clone(),
                context.key.organization_id.clone(),
                context.key.account_user_id,
                context.key.device_key.clone(),
            )
    }) {
        return Err("Node Runtime database identity scope did not match its directory".to_string());
    }
    connection
        .execute(
            "INSERT INTO node_identity(
                singleton_id, control_id, organization_id, account_user_id, member_id,
                device_id, device_key, node_id, algorithm, public_key,
                public_key_fingerprint, credential_id, certificate_payload,
                certificate_signature, certificate_issued_at, certificate_expires_at,
                max_offline_hours, revocation_version, updated_at
             ) VALUES (
                1, ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13,
                ?14, ?15, ?16, ?17, ?18
             ) ON CONFLICT(singleton_id) DO UPDATE SET
                member_id=excluded.member_id,
                device_id=excluded.device_id,
                node_id=excluded.node_id,
                algorithm=excluded.algorithm,
                public_key=excluded.public_key,
                public_key_fingerprint=excluded.public_key_fingerprint,
                credential_id=excluded.credential_id,
                certificate_payload=excluded.certificate_payload,
                certificate_signature=excluded.certificate_signature,
                certificate_issued_at=excluded.certificate_issued_at,
                certificate_expires_at=excluded.certificate_expires_at,
                max_offline_hours=excluded.max_offline_hours,
                revocation_version=excluded.revocation_version,
                updated_at=excluded.updated_at",
            params![
                &context.key.control_id,
                &context.key.organization_id,
                context.key.account_user_id,
                context.member_id,
                context.device_id,
                &context.key.device_key,
                &context.node_id,
                &context.algorithm,
                &context.public_key,
                &context.public_key_fingerprint,
                &context.credential_id,
                &context.certificate_payload,
                &context.certificate_signature,
                &context.certificate_issued_at,
                &context.certificate_expires_at,
                context.max_offline_hours,
                context.revocation_version,
                Utc::now().to_rfc3339(),
            ],
        )
        .map_err(|error| format!("failed to persist scoped node identity: {error}"))?;
    Ok(())
}

fn validate_database_schema(connection: &Connection, path: &Path) -> Result<(), String> {
    for table in REQUIRED_TABLES {
        let exists: bool = connection
            .query_row(
                "SELECT EXISTS(
                    SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?1
                 )",
                [table],
                |row| row.get(0),
            )
            .map_err(|error| database_error(path, "validate schema", error))?;
        if !exists {
            return Err(format!(
                "Node Runtime database schema is missing table {table}: {}",
                path.display()
            ));
        }
    }
    let migration_recorded: bool = connection
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM schema_migration WHERE version = ?1)",
            [CURRENT_SCHEMA_VERSION],
            |row| row.get(0),
        )
        .map_err(|error| database_error(path, "validate migration history", error))?;
    if !migration_recorded {
        return Err(format!(
            "Node Runtime database migration history is incomplete: {}",
            path.display()
        ));
    }
    Ok(())
}

fn close_active_database(active: Option<ActiveDatabase>) -> Result<(), String> {
    let Some(active) = active else {
        return Ok(());
    };
    active
        .connection
        .execute_batch("PRAGMA optimize;")
        .map_err(|error| database_error(&active.path, "optimize before close", error))?;
    let _: (u32, u32, u32) = active
        .connection
        .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?))
        })
        .map_err(|error| database_error(&active.path, "checkpoint WAL", error))?;
    active
        .connection
        .close()
        .map_err(|(_, error)| database_error(&active.path, "close", error))
}

fn status_for_database(database: &ActiveDatabase) -> NodeRuntimeStatus {
    NodeRuntimeStatus {
        running: true,
        active: true,
        node_id: Some(database.context.node_id.clone()),
        organization_id: Some(database.context.key.organization_id.clone()),
        account_user_id: Some(database.context.key.account_user_id),
        device_key: Some(database.context.key.device_key.clone()),
        storage_scope_id: Some(storage_scope_id(&database.context.key)),
        schema_version: Some(CURRENT_SCHEMA_VERSION),
        last_error: None,
    }
}

fn context_database_path(base_dir: &Path, key: &NodeRuntimeIdentityKey) -> PathBuf {
    base_dir
        .join("controls")
        .join(scoped_component("control", &key.control_id))
        .join("organizations")
        .join(scoped_component("org", &key.organization_id))
        .join("accounts")
        .join(scoped_component("user", &key.account_user_id.to_string()))
        .join("devices")
        .join(scoped_component("device", &key.device_key))
        .join("meshx-node.db")
}

fn scoped_component(prefix: &str, value: &str) -> String {
    format!("{prefix}-{}", &sha256_hex(value.as_bytes())[..32])
}

fn storage_scope_id(key: &NodeRuntimeIdentityKey) -> String {
    sha256_hex(
        format!(
            "{}\u{0}{}\u{0}{}\u{0}{}",
            key.control_id, key.organization_id, key.account_user_id, key.device_key
        )
        .as_bytes(),
    )
}

fn sha256_hex(value: &[u8]) -> String {
    format!("{:x}", Sha256::digest(value))
}

fn required_string(value: &Value, key: &str, label: &str) -> Result<String, String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
        .ok_or_else(|| format!("{label} was missing"))
}

fn required_u64(value: &Value, key: &str, label: &str) -> Result<u64, String> {
    value
        .get(key)
        .and_then(Value::as_u64)
        .ok_or_else(|| format!("{label} was missing"))
}

fn database_error(path: &Path, operation: &str, error: rusqlite::Error) -> String {
    format!(
        "failed to {operation} Node Runtime database {}: {error}",
        path.display()
    )
}

fn update_status(
    status: &Arc<Mutex<NodeRuntimeStatus>>,
    update: impl FnOnce(&mut NodeRuntimeStatus),
) {
    update(&mut status.lock().unwrap_or_else(|poison| poison.into_inner()));
}

fn set_runtime_error(status: &Arc<Mutex<NodeRuntimeStatus>>, error: &str) {
    update_status(status, |current| {
        current.last_error = Some(error.to_string())
    });
}

#[cfg(test)]
mod tests {
    use super::{
        context_database_path, NodeRuntimeContext, NodeRuntimeIdentityKey, NodeRuntimeState,
        CURRENT_SCHEMA_VERSION,
    };
    use crate::device_identity::{ControlTrustAnchor, DeviceIdentity};
    use rusqlite::{Connection, OptionalExtension};
    use serde_json::json;
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEST_DIR: AtomicU64 = AtomicU64::new(1);

    struct TestDir(PathBuf);

    impl TestDir {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "meshx-node-runtime-test-{}-{}",
                std::process::id(),
                NEXT_TEST_DIR.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn builds_context_only_from_matching_verified_session_values() {
        let identity = DeviceIdentity {
            device_key: "desktop-device-key".to_string(),
            public_key: "public-key".to_string(),
            fingerprint: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                .to_string(),
        };
        let trust = ControlTrustAnchor {
            control_id: "control-a".to_string(),
            organization_id: "org-a".to_string(),
            public_key: "control-public-key".to_string(),
            fingerprint: "control-fingerprint".to_string(),
        };
        let certificate_payload = json!({
            "memberId": 71,
            "deviceId": 42,
            "issuedAt": "2026-08-11T10:00:00Z",
            "expiresAt": "2026-11-09T10:00:00Z",
            "maxOfflineHours": 72,
        })
        .to_string();
        let device = json!({
            "id": 42,
            "deviceKey": identity.device_key,
            "credential": {
                "credentialId": "credential-a",
                "fingerprint": identity.fingerprint,
                "certificatePayload": certificate_payload,
                "certificateSignature": "signature-a",
            }
        });

        let context = NodeRuntimeContext::from_verified_session(
            &json!({ "userId": 9 }),
            &device,
            &trust,
            &identity,
            13,
        )
        .unwrap();
        assert_eq!(context.key.control_id, "control-a");
        assert_eq!(context.key.organization_id, "org-a");
        assert_eq!(context.key.account_user_id, 9);
        assert_eq!(context.member_id, 71);
        assert_eq!(context.device_id, 42);
        assert_eq!(context.revocation_version, 13);
        assert_eq!(context.node_id, "node-0123456789abcdef0123456789abcdef");

        let mut wrong_device = device;
        wrong_device["deviceKey"] = json!("another-device");
        assert!(NodeRuntimeContext::from_verified_session(
            &json!({ "userId": 9 }),
            &wrong_device,
            &trust,
            &identity,
            13,
        )
        .is_err());
    }

    #[tokio::test]
    async fn migrates_scoped_database_and_preserves_data_across_restart() {
        let directory = TestDir::new();
        let context = test_context("org-a", 9, "device-a");
        let database_path = context_database_path(directory.path(), &context.key);
        let runtime = NodeRuntimeState::new(directory.path().to_path_buf()).unwrap();

        let status = runtime.activate(context.clone()).await.unwrap();
        assert!(status.running && status.active);
        assert_eq!(status.schema_version, Some(CURRENT_SCHEMA_VERSION));
        assert_eq!(status.organization_id.as_deref(), Some("org-a"));

        let connection = Connection::open(&database_path).unwrap();
        let version: u32 = connection
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .unwrap();
        assert_eq!(version, CURRENT_SCHEMA_VERSION);
        let journal_mode: String = connection
            .query_row("PRAGMA journal_mode", [], |row| row.get(0))
            .unwrap();
        assert_eq!(journal_mode.to_ascii_lowercase(), "wal");
        let migration_count: u32 = connection
            .query_row("SELECT COUNT(*) FROM schema_migration", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(migration_count, 1);
        let persisted_scope: (String, u64, String) = connection
            .query_row(
                "SELECT organization_id, account_user_id, device_key FROM node_identity",
                [],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(
            persisted_scope,
            ("org-a".to_string(), 9, "device-a".to_string())
        );
        let private_key_column: Option<String> = connection
            .query_row(
                "SELECT name FROM pragma_table_info('node_identity') WHERE name LIKE '%private%'",
                [],
                |row| row.get(0),
            )
            .optional()
            .unwrap();
        assert!(private_key_column.is_none());
        connection
            .execute(
                "INSERT INTO local_conversation(
                    conversation_id, conversation_type, metadata_json, created_at, updated_at
                 ) VALUES ('conversation-a', 'DIRECT', '{}', 'now', 'now')",
                [],
            )
            .unwrap();
        drop(connection);
        runtime.shutdown().unwrap();

        let restarted = NodeRuntimeState::new(directory.path().to_path_buf()).unwrap();
        restarted.activate(context).await.unwrap();
        let connection = Connection::open(&database_path).unwrap();
        let conversation_count: u32 = connection
            .query_row("SELECT COUNT(*) FROM local_conversation", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(conversation_count, 1);
        let migration_count: u32 = connection
            .query_row("SELECT COUNT(*) FROM schema_migration", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(migration_count, 1);
        drop(connection);
        restarted.shutdown().unwrap();
    }

    #[tokio::test]
    async fn isolates_org_account_and_device_and_serializes_context_switches() {
        let directory = TestDir::new();
        let context_a = test_context("org-a", 9, "device-a");
        let context_b = test_context("org-b", 9, "device-a");
        let context_c = test_context("org-a", 10, "device-b");
        let mut context_d = context_a.clone();
        context_d.key.control_id = "another-control".to_string();
        let path_a = context_database_path(directory.path(), &context_a.key);
        let path_b = context_database_path(directory.path(), &context_b.key);
        let path_c = context_database_path(directory.path(), &context_c.key);
        let path_d = context_database_path(directory.path(), &context_d.key);
        assert_ne!(path_a, path_b);
        assert_ne!(path_a, path_c);
        assert_ne!(path_a, path_d);
        let path_text = path_a.to_string_lossy();
        assert!(!path_text.contains("org-a"));
        assert!(!path_text.contains("device-a"));
        assert!(!path_text.contains("control-org-a"));

        let runtime = NodeRuntimeState::new(directory.path().to_path_buf()).unwrap();
        runtime.activate(context_a.clone()).await.unwrap();
        runtime.activate(context_b.clone()).await.unwrap();
        assert_eq!(runtime.snapshot().organization_id.as_deref(), Some("org-b"));
        assert!(path_a.exists() && path_b.exists());

        runtime.deactivate(context_a.key).await.unwrap();
        assert!(
            runtime.snapshot().active,
            "an old session must not stop the current context"
        );
        runtime.deactivate(context_b.key).await.unwrap();
        assert!(!runtime.snapshot().active);
        runtime.shutdown().unwrap();
    }

    #[tokio::test]
    async fn corrupt_database_fails_closed_without_deleting_original_bytes() {
        let directory = TestDir::new();
        let context = test_context("org-a", 9, "device-a");
        let database_path = context_database_path(directory.path(), &context.key);
        fs::create_dir_all(database_path.parent().unwrap()).unwrap();
        let original = b"not-a-sqlite-database";
        fs::write(&database_path, original).unwrap();
        let runtime = NodeRuntimeState::new(directory.path().to_path_buf()).unwrap();

        let error = runtime.activate(context).await.unwrap_err();
        assert!(error.contains("Node Runtime database"));
        assert!(!runtime.snapshot().active);
        assert_eq!(fs::read(&database_path).unwrap(), original);
        runtime.shutdown().unwrap();
    }

    fn test_context(
        organization_id: &str,
        account_user_id: u64,
        device_key: &str,
    ) -> NodeRuntimeContext {
        NodeRuntimeContext {
            key: NodeRuntimeIdentityKey {
                control_id: format!("control-{organization_id}"),
                organization_id: organization_id.to_string(),
                account_user_id,
                device_key: device_key.to_string(),
            },
            member_id: account_user_id + 100,
            device_id: account_user_id + 200,
            node_id: format!("node-{organization_id}-{account_user_id}-{device_key}"),
            algorithm: "ED25519".to_string(),
            public_key: "public-key".to_string(),
            public_key_fingerprint: "fingerprint".to_string(),
            credential_id: "credential".to_string(),
            certificate_payload: "certificate-payload".to_string(),
            certificate_signature: "certificate-signature".to_string(),
            certificate_issued_at: "2026-08-11T10:00:00Z".to_string(),
            certificate_expires_at: "2026-11-09T10:00:00Z".to_string(),
            max_offline_hours: 72,
            revocation_version: 3,
        }
    }
}
