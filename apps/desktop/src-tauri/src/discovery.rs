use std::collections::{HashMap, HashSet};
use std::fs;
use std::net::{IpAddr, Ipv6Addr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::thread;
use std::time::{Duration, Instant};

use chrono::{DateTime, Utc};
use mdns_sd::{ResolvedService, ServiceDaemon, ServiceEvent};
use reqwest::redirect::Policy;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tauri::{AppHandle, Emitter, Manager};

use crate::endpoint::{normalize_node_address, valid_node_id};

const SERVICE_TYPE: &str = "_lanchat._tcp.local.";
const PROTOCOL_VERSION: u16 = 1;
const API_BASE_PATH: &str = "/api/v1";
const WEB_SOCKET_PATH: &str = "/ws/chat";
const HEALTH_PATH: &str = "/api/v1/node/health";
const DEFAULT_APP_PATH: &str = "/app/";
const NODES_CHANGED_EVENT: &str = "desktop://nodes-changed";
const CACHE_LIMIT: usize = 32;
const CACHE_RETENTION_DAYS: i64 = 7;
const STALE_AFTER_SECONDS: i64 = 120;
const HEALTH_CHECK_SECONDS: u64 = 30;
const REFRESH_TRANSPORT: &str = "HTTP_ONLY_COOKIE";

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DesktopNodeSource {
    Mdns,
    ServerFallback,
    Cache,
    Manual,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DesktopNodeHealth {
    Unknown,
    Probing,
    Healthy,
    Degraded,
    Offline,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopNode {
    pub node_id: String,
    pub node_name: String,
    pub organization_name: String,
    pub version: String,
    pub mode: String,
    pub app_url: String,
    pub secure: bool,
    pub current: bool,
    pub last_seen_at: String,
    pub source: DesktopNodeSource,
    pub health: DesktopNodeHealth,
    pub latency_ms: Option<u64>,
    pub failure_count: u32,
    pub pinned: bool,
    pub protocol_version: u16,
    pub api_origin: String,
    pub api_base_path: String,
    pub web_socket_path: String,
    pub health_path: String,
    pub app_path: String,
    pub last_successful_at: Option<String>,
    pub addresses: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NodePublicInfo {
    node_id: String,
    node_name: String,
    organization_name: String,
    version: String,
    mode: String,
    service_status: String,
    secure: bool,
    protocol_version: u16,
    api_base_path: String,
    web_socket_path: String,
    health_path: String,
    app_path: String,
    desktop_auth_supported: bool,
    refresh_transport: String,
}

#[derive(Deserialize)]
struct ApiResult<T> {
    code: i64,
    #[serde(default)]
    msg: String,
    data: Option<T>,
}

struct DiscoveryCandidate {
    fullname: Option<String>,
    expected_node_id: Option<String>,
    protocol_version: u16,
    api_base_path: String,
    web_socket_path: String,
    health_path: String,
    app_path: String,
    desktop_auth_supported: bool,
    origins: Vec<(String, String)>,
    source: DesktopNodeSource,
}

pub struct DiscoveryService {
    app: AppHandle,
    daemon: Mutex<Option<ServiceDaemon>>,
    generation: AtomicU64,
    probe_running: AtomicBool,
    nodes: Mutex<HashMap<String, DesktopNode>>,
    active_services: Mutex<HashSet<String>>,
    service_index: Mutex<HashMap<String, String>>,
    cache_path: PathBuf,
    http: Client,
}

impl DiscoveryService {
    pub fn new(app: AppHandle) -> Result<Arc<Self>, String> {
        let cache_path = app
            .path()
            .app_data_dir()
            .map_err(|error| format!("failed to resolve desktop data directory: {error}"))?
            .join("nodes.json");
        let service = Arc::new(Self {
            app,
            daemon: Mutex::new(None),
            generation: AtomicU64::new(0),
            probe_running: AtomicBool::new(false),
            nodes: Mutex::new(load_cache(&cache_path)),
            active_services: Mutex::new(HashSet::new()),
            service_index: Mutex::new(HashMap::new()),
            cache_path,
            http: Client::builder()
                .redirect(Policy::none())
                .no_proxy()
                .connect_timeout(Duration::from_millis(1_500))
                .timeout(Duration::from_secs(3))
                .user_agent("MeshX-Desktop-Discovery/3.0.0")
                .build()
                .map_err(|error| format!("failed to initialize discovery HTTP client: {error}"))?,
        });
        service.refresh()?;
        spawn_health_worker(Arc::downgrade(&service));
        Ok(service)
    }

    pub fn snapshot(&self) -> Vec<DesktopNode> {
        self.prune_stale();
        let mut nodes: Vec<_> = self
            .nodes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .values()
            .cloned()
            .collect();
        nodes.sort_by(|left, right| {
            right
                .current
                .cmp(&left.current)
                .then_with(|| health_rank(left.health).cmp(&health_rank(right.health)))
                .then_with(|| {
                    left.latency_ms
                        .unwrap_or(u64::MAX)
                        .cmp(&right.latency_ms.unwrap_or(u64::MAX))
                })
                .then_with(|| {
                    left.node_name
                        .to_lowercase()
                        .cmp(&right.node_name.to_lowercase())
                })
        });
        nodes
    }

    pub fn refresh(self: &Arc<Self>) -> Result<(), String> {
        if self
            .daemon
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .is_some()
        {
            self.probe_known_nodes_async();
            self.emit_nodes();
            return Ok(());
        }
        let daemon = ServiceDaemon::new()
            .map_err(|error| format!("failed to start native mDNS discovery: {error}"))?;
        let receiver = daemon
            .browse(SERVICE_TYPE)
            .map_err(|error| format!("failed to browse {SERVICE_TYPE}: {error}"))?;
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;

        self.active_services
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clear();
        self.service_index
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clear();
        {
            let mut nodes = self
                .nodes
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            for node in nodes.values_mut() {
                if node.source == DesktopNodeSource::Mdns {
                    node.source = DesktopNodeSource::Cache;
                    node.health = DesktopNodeHealth::Unknown;
                    node.latency_ms = None;
                }
            }
        }

        let old = self
            .daemon
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .replace(daemon);
        if let Some(old) = old {
            let _ = old.shutdown();
        }

        self.emit_nodes();
        let service = Arc::clone(self);
        thread::Builder::new()
            .name("lanchat-mdns-events".to_string())
            .spawn(move || {
                while generation == service.generation.load(Ordering::SeqCst) {
                    let Ok(event) = receiver.recv() else {
                        break;
                    };
                    service.handle_event(generation, event);
                }
                if generation == service.generation.load(Ordering::SeqCst) {
                    service
                        .daemon
                        .lock()
                        .unwrap_or_else(|poison| poison.into_inner())
                        .take();
                }
            })
            .map_err(|error| format!("failed to start mDNS event worker: {error}"))?;
        self.probe_known_nodes_async();
        Ok(())
    }

    fn handle_event(self: &Arc<Self>, generation: u64, event: ServiceEvent) {
        match event {
            ServiceEvent::ServiceFound(_, fullname) => {
                self.active_services
                    .lock()
                    .unwrap_or_else(|poison| poison.into_inner())
                    .insert(fullname);
            }
            ServiceEvent::ServiceResolved(info) => {
                let fullname = info.get_fullname().to_string();
                self.active_services
                    .lock()
                    .unwrap_or_else(|poison| poison.into_inner())
                    .insert(fullname);
                let service = Arc::clone(self);
                tauri::async_runtime::spawn(async move {
                    if generation != service.generation.load(Ordering::SeqCst) {
                        return;
                    }
                    if let Ok(candidate) = candidate_from_mdns(&info) {
                        let _ = service.probe_and_store(generation, candidate).await;
                    }
                });
            }
            ServiceEvent::ServiceRemoved(_, fullname) => self.remove_service(&fullname),
            _ => {}
        }
    }

    async fn probe_and_store(
        &self,
        generation: u64,
        candidate: DiscoveryCandidate,
    ) -> Result<DesktopNode, String> {
        self.mark_probing(&candidate);
        let fullname = candidate.fullname.clone();
        let mut last_error = "node did not expose a usable address".to_string();
        for (origin, address) in &candidate.origins {
            match self.handshake(origin, address, &candidate).await {
                Ok(node) => {
                    if generation != self.generation.load(Ordering::SeqCst)
                        && candidate.source == DesktopNodeSource::Mdns
                    {
                        return Err("discovery scan was superseded".to_string());
                    }
                    if let Some(name) = &fullname {
                        if !self
                            .active_services
                            .lock()
                            .unwrap_or_else(|poison| poison.into_inner())
                            .contains(name)
                        {
                            return Err("mDNS service disappeared during validation".to_string());
                        }
                        self.service_index
                            .lock()
                            .unwrap_or_else(|poison| poison.into_inner())
                            .insert(name.clone(), node.node_id.clone());
                    }
                    self.upsert(node.clone());
                    return Ok(node);
                }
                Err(error) => last_error = error,
            }
        }
        self.mark_failed(&candidate);
        Err(last_error)
    }

    async fn handshake(
        &self,
        origin: &str,
        address: &str,
        candidate: &DiscoveryCandidate,
    ) -> Result<DesktopNode, String> {
        let started = Instant::now();
        let info: NodePublicInfo = self
            .get_result(&format!("{origin}{API_BASE_PATH}/node/info"))
            .await?;
        if !valid_node_id(&info.node_id) {
            return Err("node handshake returned an invalid nodeId".to_string());
        }
        if let Some(expected) = &candidate.expected_node_id {
            if expected != &info.node_id {
                return Err("mDNS nodeId did not match the HTTP handshake".to_string());
            }
        }
        validate_protocol_contract(&info)?;
        if candidate.protocol_version != info.protocol_version
            || candidate.api_base_path != info.api_base_path
            || candidate.web_socket_path != info.web_socket_path
            || candidate.health_path != info.health_path
            || normalize_app_path(&candidate.app_path)? != normalize_app_path(&info.app_path)?
            || candidate.desktop_auth_supported != info.desktop_auth_supported
        {
            return Err("advertised node protocol did not match the HTTP handshake".to_string());
        }
        if info.secure && !origin.starts_with("https://") {
            return Err("node attempted to downgrade a secure advertisement".to_string());
        }

        let health: Value = self
            .get_result(&format!("{origin}{}", info.health_path))
            .await?;
        if health
            .get("nodeId")
            .and_then(Value::as_str)
            .is_some_and(|node_id| node_id != info.node_id)
        {
            return Err("health endpoint nodeId did not match node info".to_string());
        }
        let health_status = health
            .get("status")
            .and_then(Value::as_str)
            .unwrap_or("UNKNOWN");
        let now = Utc::now().to_rfc3339();
        let app_path = normalize_app_path(&info.app_path)?;
        Ok(DesktopNode {
            node_id: info.node_id,
            node_name: safe_label(&info.node_name, "MeshX Node", 80),
            organization_name: safe_label(&info.organization_name, "Local Organization", 80),
            version: safe_label(&info.version, "unknown", 30),
            mode: safe_mode(&info.mode),
            app_url: format!("{origin}{app_path}"),
            secure: origin.starts_with("https://"),
            current: false,
            last_seen_at: now.clone(),
            source: candidate.source,
            health: if health_status == "UP" && info.service_status == "AVAILABLE" {
                DesktopNodeHealth::Healthy
            } else {
                DesktopNodeHealth::Degraded
            },
            latency_ms: Some(started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64),
            failure_count: 0,
            pinned: candidate.source == DesktopNodeSource::Manual,
            protocol_version: info.protocol_version,
            api_origin: origin.to_string(),
            api_base_path: info.api_base_path,
            web_socket_path: info.web_socket_path,
            health_path: info.health_path,
            app_path,
            last_successful_at: Some(now),
            addresses: vec![address.to_string()],
        })
    }

    async fn get_result<T: for<'de> Deserialize<'de>>(&self, url: &str) -> Result<T, String> {
        let response = self.http.get(url).send().await.map_err(|error| {
            if error.is_timeout() {
                "node handshake timed out".to_string()
            } else {
                "node handshake failed".to_string()
            }
        })?;
        let status = response.status();
        let result = response
            .json::<ApiResult<T>>()
            .await
            .map_err(|_| format!("node returned an invalid handshake response ({status})"))?;
        if result.code != 200 {
            return Err(if result.msg.trim().is_empty() {
                format!("node handshake failed with code {}", result.code)
            } else {
                result.msg
            });
        }
        result
            .data
            .ok_or_else(|| "node handshake response did not contain data".to_string())
    }

    pub async fn add_manual(self: &Arc<Self>, address: &str) -> Result<DesktopNode, String> {
        let origin = normalize_node_address(address)?;
        let generation = self.generation.load(Ordering::SeqCst);
        self.probe_and_store(
            generation,
            direct_candidate(origin, DesktopNodeSource::Manual),
        )
        .await
    }

    pub async fn add_server_fallback(
        self: &Arc<Self>,
        address: &str,
    ) -> Result<DesktopNode, String> {
        let origin = normalize_node_address(address)?;
        let generation = self.generation.load(Ordering::SeqCst);
        self.probe_and_store(
            generation,
            direct_candidate(origin, DesktopNodeSource::ServerFallback),
        )
        .await
    }

    fn upsert(&self, mut incoming: DesktopNode) {
        let mut nodes = self
            .nodes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if let Some(existing) = nodes.get(&incoming.node_id) {
            let mut addresses: HashSet<_> = existing.addresses.iter().cloned().collect();
            addresses.extend(incoming.addresses.iter().cloned());
            incoming.addresses = addresses.into_iter().collect();
            incoming.addresses.sort();
            incoming.current = existing.current;
            incoming.pinned |= existing.pinned;
            if source_rank(existing.source) > source_rank(incoming.source) {
                incoming.source = existing.source;
                incoming.api_origin = existing.api_origin.clone();
                incoming.app_url = existing.app_url.clone();
                incoming.secure = existing.secure;
                incoming.latency_ms = existing.latency_ms;
                incoming.api_base_path = existing.api_base_path.clone();
                incoming.web_socket_path = existing.web_socket_path.clone();
                incoming.health_path = existing.health_path.clone();
                incoming.app_path = existing.app_path.clone();
            }
            if source_rank(existing.source) == source_rank(incoming.source)
                && existing.latency_ms.unwrap_or(u64::MAX) < incoming.latency_ms.unwrap_or(u64::MAX)
            {
                incoming.api_origin = existing.api_origin.clone();
                incoming.app_url = existing.app_url.clone();
                incoming.secure = existing.secure;
                incoming.latency_ms = existing.latency_ms;
            }
        }
        nodes.insert(incoming.node_id.clone(), incoming);
        drop(nodes);
        self.persist_cache();
        self.emit_nodes();
    }

    fn mark_probing(&self, candidate: &DiscoveryCandidate) {
        self.update_candidate_node(candidate, |node| {
            node.health = DesktopNodeHealth::Probing;
        });
    }

    fn mark_failed(&self, candidate: &DiscoveryCandidate) {
        self.update_candidate_node(candidate, |node| {
            node.failure_count = node.failure_count.saturating_add(1);
            node.latency_ms = None;
            node.health = if node.failure_count >= 3 {
                DesktopNodeHealth::Offline
            } else {
                DesktopNodeHealth::Degraded
            };
        });
        self.persist_cache();
    }

    fn update_candidate_node(
        &self,
        candidate: &DiscoveryCandidate,
        update: impl FnOnce(&mut DesktopNode),
    ) {
        let mut nodes = self
            .nodes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        let node_id = candidate
            .expected_node_id
            .as_ref()
            .filter(|node_id| nodes.contains_key(*node_id))
            .cloned()
            .or_else(|| {
                nodes
                    .iter()
                    .find(|(_, node)| {
                        candidate
                            .origins
                            .iter()
                            .any(|(origin, _)| origin == &node.api_origin)
                    })
                    .map(|(node_id, _)| node_id.clone())
            });
        if let Some(node) = node_id.and_then(|node_id| nodes.get_mut(&node_id)) {
            update(node);
            drop(nodes);
            self.emit_nodes();
        }
    }

    fn probe_known_nodes_async(self: &Arc<Self>) {
        let candidates = self.known_candidates();
        if candidates.is_empty()
            || self
                .probe_running
                .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
                .is_err()
        {
            return;
        }
        let service = Arc::clone(self);
        let generation = self.generation.load(Ordering::SeqCst);
        tauri::async_runtime::spawn(async move {
            for candidate in candidates {
                let _ = service.probe_and_store(generation, candidate).await;
            }
            service.probe_running.store(false, Ordering::SeqCst);
        });
    }

    fn known_candidates(&self) -> Vec<DiscoveryCandidate> {
        self.nodes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .values()
            .filter(|node| !node.api_origin.is_empty())
            .map(|node| DiscoveryCandidate {
                fullname: None,
                expected_node_id: Some(node.node_id.clone()),
                protocol_version: node.protocol_version,
                api_base_path: node.api_base_path.clone(),
                web_socket_path: node.web_socket_path.clone(),
                health_path: node.health_path.clone(),
                app_path: node.app_path.clone(),
                desktop_auth_supported: true,
                origins: vec![(
                    node.api_origin.clone(),
                    node.addresses
                        .first()
                        .cloned()
                        .unwrap_or_else(|| node.api_origin.clone()),
                )],
                source: node.source,
            })
            .collect()
    }

    pub fn node_id_for_origin(&self, origin: &str) -> Option<String> {
        self.nodes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .values()
            .find(|node| node.api_origin == origin)
            .map(|node| node.node_id.clone())
    }

    fn remove_service(&self, fullname: &str) {
        self.active_services
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(fullname);
        let node_id = self
            .service_index
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(fullname);
        if let Some(node_id) = node_id {
            let mut nodes = self
                .nodes
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            if let Some(node) = nodes.get_mut(&node_id) {
                if node.source == DesktopNodeSource::Mdns {
                    node.source = DesktopNodeSource::Cache;
                    node.failure_count = node.failure_count.saturating_add(1);
                    node.health = DesktopNodeHealth::Degraded;
                    node.latency_ms = None;
                }
            }
        }
        self.persist_cache();
        self.emit_nodes();
    }

    fn prune_stale(&self) {
        let mdns_cutoff = Utc::now() - chrono::Duration::seconds(STALE_AFTER_SECONDS);
        let cache_cutoff = Utc::now() - chrono::Duration::days(CACHE_RETENTION_DAYS);
        self.nodes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .retain(|_, node| {
                let recently_successful = node.pinned
                    || node
                        .last_successful_at
                        .as_deref()
                        .and_then(|value| DateTime::parse_from_rfc3339(value).ok())
                        .map(|seen| seen >= cache_cutoff)
                        .unwrap_or(false);
                let mdns_fresh = node.source != DesktopNodeSource::Mdns
                    || DateTime::parse_from_rfc3339(&node.last_seen_at)
                        .map(|seen| seen >= mdns_cutoff)
                        .unwrap_or(false);
                recently_successful && mdns_fresh
            });
    }

    fn persist_cache(&self) {
        let nodes: Vec<_> = self
            .nodes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .values()
            .cloned()
            .collect();
        let nodes = cache_candidates(nodes);
        let Some(parent) = self.cache_path.parent() else {
            return;
        };
        if fs::create_dir_all(parent).is_err() {
            return;
        }
        let Ok(contents) = serde_json::to_vec_pretty(&nodes) else {
            return;
        };
        let temporary = self.cache_path.with_extension("json.tmp");
        if fs::write(&temporary, contents).is_ok() {
            let _ = fs::rename(temporary, &self.cache_path);
        }
    }

    fn emit_nodes(&self) {
        let _ = self.app.emit(NODES_CHANGED_EVENT, self.snapshot());
    }
}

impl Drop for DiscoveryService {
    fn drop(&mut self) {
        if let Some(daemon) = self
            .daemon
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .take()
        {
            let _ = daemon.shutdown();
        }
    }
}

#[tauri::command]
pub fn discovered_nodes(state: tauri::State<'_, Arc<DiscoveryService>>) -> Vec<DesktopNode> {
    state.snapshot()
}

#[tauri::command]
pub fn refresh_discovery(state: tauri::State<'_, Arc<DiscoveryService>>) -> Result<(), String> {
    state.inner().refresh()
}

#[tauri::command]
pub async fn add_manual_node(
    address: String,
    state: tauri::State<'_, Arc<DiscoveryService>>,
) -> Result<DesktopNode, String> {
    state.inner().add_manual(&address).await
}

#[tauri::command]
pub async fn add_server_fallback_nodes(
    addresses: Vec<String>,
    state: tauri::State<'_, Arc<DiscoveryService>>,
) -> Result<Vec<DesktopNode>, String> {
    if addresses.is_empty() || addresses.len() > 64 {
        return Err("server fallback node list must contain 1 to 64 addresses".to_string());
    }
    let mut nodes = Vec::new();
    let mut last_error = None;
    for address in addresses {
        match state.inner().add_server_fallback(&address).await {
            Ok(node) => nodes.push(node),
            Err(error) => last_error = Some(error),
        }
    }
    if nodes.is_empty() {
        Err(last_error.unwrap_or_else(|| "no server fallback node was valid".to_string()))
    } else {
        nodes.sort_by(|left, right| left.node_id.cmp(&right.node_id));
        nodes.dedup_by(|left, right| left.node_id == right.node_id);
        Ok(nodes)
    }
}

fn candidate_from_mdns(info: &ResolvedService) -> Result<DiscoveryCandidate, String> {
    if !info.is_valid() || info.get_port() == 0 {
        return Err("mDNS record is incomplete".to_string());
    }
    let node_id = info
        .get_property_val_str("nodeId")
        .ok_or_else(|| "mDNS record does not contain nodeId".to_string())?
        .trim()
        .to_lowercase();
    if !valid_node_id(&node_id) {
        return Err("mDNS record contains an invalid nodeId".to_string());
    }
    let protocol = info
        .get_property_val_str("protocolVersion")
        .or_else(|| info.get_property_val_str("protocol"))
        .and_then(|value| value.parse::<u16>().ok())
        .ok_or_else(|| "mDNS record does not contain a valid protocol".to_string())?;
    if protocol != PROTOCOL_VERSION {
        return Err("mDNS protocol version is not supported".to_string());
    }
    let secure = match info.get_property_val_str("secure") {
        Some("true") => true,
        Some("false") | None => false,
        Some(_) => return Err("mDNS secure flag is invalid".to_string()),
    };
    let api_base_path = advertised_path(info, "apiBasePath", API_BASE_PATH, "mDNS API base path")?;
    let web_socket_path = advertised_path(
        info,
        "webSocketPath",
        WEB_SOCKET_PATH,
        "mDNS WebSocket path",
    )?;
    let health_path = advertised_path(info, "healthPath", HEALTH_PATH, "mDNS health path")?;
    let app_path = normalize_app_path(
        info.get_property_val_str("appPath")
            .or_else(|| info.get_property_val_str("path"))
            .ok_or_else(|| "mDNS record does not contain appPath".to_string())?,
    )?;
    let desktop_auth_supported = match info.get_property_val_str("desktopAuthSupported") {
        Some("true") => true,
        _ => return Err("mDNS record does not support desktop authentication".to_string()),
    };
    if info.get_property_val_str("refreshTransport") != Some(REFRESH_TRANSPORT) {
        return Err("mDNS refresh transport is not supported".to_string());
    }

    let mut addresses: Vec<IpAddr> = info
        .get_addresses()
        .iter()
        .map(|address| address.to_ip_addr())
        .filter(usable_ip)
        .collect();
    addresses.sort_by_key(|address| if address.is_ipv4() { 0 } else { 1 });
    addresses.dedup();
    let origins = addresses
        .into_iter()
        .map(|address| {
            let host = match address {
                IpAddr::V4(address) => address.to_string(),
                IpAddr::V6(address) => format!("[{address}]"),
            };
            let origin = format!(
                "{}://{}:{}",
                if secure { "https" } else { "http" },
                host,
                info.get_port()
            );
            (origin, address.to_string())
        })
        .collect();
    Ok(DiscoveryCandidate {
        fullname: Some(info.get_fullname().to_string()),
        expected_node_id: Some(node_id),
        protocol_version: protocol,
        api_base_path,
        web_socket_path,
        health_path,
        app_path,
        desktop_auth_supported,
        origins,
        source: DesktopNodeSource::Mdns,
    })
}

fn direct_candidate(origin: String, source: DesktopNodeSource) -> DiscoveryCandidate {
    DiscoveryCandidate {
        fullname: None,
        expected_node_id: None,
        protocol_version: PROTOCOL_VERSION,
        api_base_path: API_BASE_PATH.to_string(),
        web_socket_path: WEB_SOCKET_PATH.to_string(),
        health_path: HEALTH_PATH.to_string(),
        app_path: DEFAULT_APP_PATH.to_string(),
        desktop_auth_supported: true,
        origins: vec![(origin.clone(), origin)],
        source,
    }
}

fn validate_protocol_contract(info: &NodePublicInfo) -> Result<(), String> {
    if info.protocol_version != PROTOCOL_VERSION {
        return Err("node protocol version is not supported".to_string());
    }
    if !info.desktop_auth_supported {
        return Err("node does not support native desktop authentication".to_string());
    }
    if info.refresh_transport != REFRESH_TRANSPORT {
        return Err("node refresh transport is not supported".to_string());
    }
    validate_exact_path(&info.api_base_path, API_BASE_PATH, "API base path")?;
    validate_exact_path(&info.web_socket_path, WEB_SOCKET_PATH, "WebSocket path")?;
    validate_exact_path(&info.health_path, HEALTH_PATH, "health path")?;
    normalize_app_path(&info.app_path)?;
    Ok(())
}

fn validate_exact_path(value: &str, expected: &str, label: &str) -> Result<(), String> {
    if value == expected {
        Ok(())
    } else {
        Err(format!("node {label} is not supported"))
    }
}

fn advertised_path(
    info: &ResolvedService,
    key: &str,
    expected: &str,
    label: &str,
) -> Result<String, String> {
    let value = info
        .get_property_val_str(key)
        .ok_or_else(|| format!("mDNS record does not contain {key}"))?;
    validate_exact_path(value, expected, label)?;
    Ok(value.to_string())
}

fn normalize_app_path(value: &str) -> Result<String, String> {
    match value {
        "/app" | "/app/" => Ok(DEFAULT_APP_PATH.to_string()),
        _ => Err("node app path is not supported".to_string()),
    }
}

fn spawn_health_worker(service: Weak<DiscoveryService>) {
    let _ = thread::Builder::new()
        .name("lanchat-node-health".to_string())
        .spawn(move || loop {
            thread::sleep(Duration::from_secs(HEALTH_CHECK_SECONDS));
            let Some(service) = service.upgrade() else {
                break;
            };
            service.probe_known_nodes_async();
        });
}

fn usable_ip(address: &IpAddr) -> bool {
    if address.is_unspecified() || address.is_multicast() {
        return false;
    }
    match address {
        IpAddr::V4(_) => true,
        IpAddr::V6(address) => !is_ipv6_link_local(address),
    }
}

fn is_ipv6_link_local(address: &Ipv6Addr) -> bool {
    (address.segments()[0] & 0xffc0) == 0xfe80
}

fn load_cache(path: &PathBuf) -> HashMap<String, DesktopNode> {
    let Ok(contents) = fs::read(path) else {
        return HashMap::new();
    };
    let Ok(nodes) = serde_json::from_slice::<Vec<DesktopNode>>(&contents) else {
        return HashMap::new();
    };
    cache_candidates(nodes)
        .into_iter()
        .filter(|node| valid_node_id(&node.node_id))
        .map(|mut node| {
            node.source = DesktopNodeSource::Cache;
            node.health = DesktopNodeHealth::Unknown;
            node.current = false;
            node.latency_ms = None;
            (node.node_id.clone(), node)
        })
        .collect()
}

fn cache_candidates(mut nodes: Vec<DesktopNode>) -> Vec<DesktopNode> {
    let cutoff = Utc::now() - chrono::Duration::days(CACHE_RETENTION_DAYS);
    nodes.retain(|node| {
        valid_node_id(&node.node_id)
            && (node.pinned
                || node
                    .last_successful_at
                    .as_deref()
                    .and_then(|value| DateTime::parse_from_rfc3339(value).ok())
                    .map(|seen| seen >= cutoff)
                    .unwrap_or(false))
    });
    nodes.sort_by(|left, right| {
        right
            .pinned
            .cmp(&left.pinned)
            .then_with(|| right.last_seen_at.cmp(&left.last_seen_at))
            .then_with(|| left.node_id.cmp(&right.node_id))
    });
    let mut seen = HashSet::new();
    nodes.retain(|node| seen.insert(node.node_id.clone()));
    nodes.truncate(CACHE_LIMIT);
    nodes
}

fn health_rank(health: DesktopNodeHealth) -> u8 {
    match health {
        DesktopNodeHealth::Healthy => 0,
        DesktopNodeHealth::Degraded => 1,
        DesktopNodeHealth::Probing => 2,
        DesktopNodeHealth::Unknown => 3,
        DesktopNodeHealth::Offline => 4,
    }
}

fn source_rank(source: DesktopNodeSource) -> u8 {
    match source {
        DesktopNodeSource::Manual => 3,
        DesktopNodeSource::Mdns => 2,
        DesktopNodeSource::ServerFallback => 1,
        DesktopNodeSource::Cache => 0,
    }
}

fn safe_label(value: &str, fallback: &str, maximum: usize) -> String {
    let clean: String = value
        .chars()
        .filter(|character| !character.is_control())
        .take(maximum)
        .collect();
    if clean.trim().is_empty() {
        fallback.to_string()
    } else {
        clean.trim().to_string()
    }
}

fn safe_mode(value: &str) -> String {
    let normalized = value.trim().to_uppercase().replace('-', "_");
    if matches!(
        normalized.as_str(),
        "LOCAL_INDEPENDENT" | "LAN_FIRST" | "HYBRID"
    ) {
        normalized
    } else {
        "LAN_FIRST".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::{
        cache_candidates, safe_label, safe_mode, usable_ip, validate_protocol_contract,
        DesktopNode, DesktopNodeHealth, DesktopNodeSource, NodePublicInfo, API_BASE_PATH,
        DEFAULT_APP_PATH, HEALTH_PATH, REFRESH_TRANSPORT, WEB_SOCKET_PATH,
    };
    use chrono::{Duration, Utc};
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    #[test]
    fn serializes_frontend_enum_contracts() {
        assert_eq!(
            serde_json::to_string(&DesktopNodeSource::Mdns).unwrap(),
            "\"MDNS\""
        );
        assert_eq!(
            serde_json::to_string(&DesktopNodeHealth::Healthy).unwrap(),
            "\"HEALTHY\""
        );
    }

    #[test]
    fn rejects_unusable_discovery_addresses() {
        assert!(!usable_ip(&IpAddr::V4(Ipv4Addr::UNSPECIFIED)));
        assert!(!usable_ip(&IpAddr::V4(Ipv4Addr::new(224, 0, 0, 1))));
        assert!(!usable_ip(&IpAddr::V6(
            "fe80::1".parse::<Ipv6Addr>().unwrap()
        )));
        assert!(usable_ip(&IpAddr::V4(Ipv4Addr::new(192, 168, 1, 5))));
    }

    #[test]
    fn sanitizes_advertised_labels_and_modes() {
        assert_eq!(safe_label("\nNode\u{0000}", "fallback", 80), "Node");
        assert_eq!(safe_mode("hybrid"), "HYBRID");
        assert_eq!(safe_mode("unexpected"), "LAN_FIRST");
    }

    #[test]
    fn cache_is_newest_first_deduplicated_and_bounded() {
        let mut nodes: Vec<_> = (0..35).map(cached_node).collect();
        let mut duplicate = cached_node(7);
        duplicate.last_seen_at = "2025-01-01T00:00:00Z".to_string();
        nodes.push(duplicate);

        let cached = cache_candidates(nodes);
        assert_eq!(cached.len(), 32);
        assert_eq!(cached.first().unwrap().node_id, "n34");
        assert_eq!(
            cached.iter().filter(|node| node.node_id == "n07").count(),
            1
        );
    }

    #[test]
    fn cache_expires_old_non_pinned_nodes_but_retains_manual_pins() {
        let expired_at = (Utc::now() - Duration::days(8)).to_rfc3339();
        let mut expired = cached_node(90);
        expired.last_seen_at = expired_at.clone();
        expired.last_successful_at = Some(expired_at.clone());
        let mut pinned = cached_node(91);
        pinned.last_seen_at = expired_at.clone();
        pinned.last_successful_at = Some(expired_at);
        pinned.pinned = true;
        pinned.source = DesktopNodeSource::Manual;

        let cached = cache_candidates(vec![expired, pinned]);
        assert_eq!(cached.len(), 1);
        assert_eq!(cached[0].node_id, "n91");
    }

    #[test]
    fn rejects_incompatible_http_protocol_contracts() {
        let mut info = compatible_info();
        assert!(validate_protocol_contract(&info).is_ok());
        info.protocol_version = 2;
        assert!(validate_protocol_contract(&info).is_err());
        info.protocol_version = 1;
        info.desktop_auth_supported = false;
        assert!(validate_protocol_contract(&info).is_err());
        info.desktop_auth_supported = true;
        info.health_path = "/health".to_string();
        assert!(validate_protocol_contract(&info).is_err());
    }

    fn cached_node(index: u8) -> DesktopNode {
        let seen_at = (Utc::now() + Duration::seconds(i64::from(index))).to_rfc3339();
        DesktopNode {
            node_id: format!("n{index:02}"),
            node_name: format!("Node {index}"),
            organization_name: "MeshX".to_string(),
            version: "3.0.0".to_string(),
            mode: "LAN_FIRST".to_string(),
            app_url: format!("http://10.0.0.{index}:8080{DEFAULT_APP_PATH}"),
            secure: false,
            current: false,
            last_seen_at: seen_at.clone(),
            source: DesktopNodeSource::Cache,
            health: DesktopNodeHealth::Healthy,
            latency_ms: Some(u64::from(index)),
            failure_count: 0,
            pinned: false,
            protocol_version: 1,
            api_origin: format!("http://10.0.0.{index}:8080"),
            api_base_path: API_BASE_PATH.to_string(),
            web_socket_path: WEB_SOCKET_PATH.to_string(),
            health_path: HEALTH_PATH.to_string(),
            app_path: DEFAULT_APP_PATH.to_string(),
            last_successful_at: Some(seen_at),
            addresses: vec![format!("10.0.0.{index}")],
        }
    }

    fn compatible_info() -> NodePublicInfo {
        NodePublicInfo {
            node_id: "node_abc".to_string(),
            node_name: "Node".to_string(),
            organization_name: "MeshX".to_string(),
            version: "3.0.0".to_string(),
            mode: "LAN_FIRST".to_string(),
            service_status: "AVAILABLE".to_string(),
            secure: false,
            protocol_version: 1,
            api_base_path: API_BASE_PATH.to_string(),
            web_socket_path: WEB_SOCKET_PATH.to_string(),
            health_path: HEALTH_PATH.to_string(),
            app_path: DEFAULT_APP_PATH.to_string(),
            desktop_auth_supported: true,
            refresh_transport: REFRESH_TRANSPORT.to_string(),
        }
    }
}
