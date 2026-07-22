use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use url::Url;

use crate::discovery::DiscoveryService;
use crate::endpoint::{normalize_origin, valid_node_id};

const DEEP_LINK_EVENT: &str = "desktop://deep-link";

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeepLinkTarget {
    pub kind: DeepLinkKind,
    pub value: String,
    pub node_origin: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DeepLinkKind {
    Node,
    Room,
    Conversation,
    Broadcast,
}

#[derive(Default)]
pub struct DeepLinkState {
    pending: Mutex<Option<DeepLinkTarget>>,
}

impl DeepLinkState {
    pub fn dispatch(&self, app: &AppHandle, url: &Url) -> Result<DeepLinkTarget, String> {
        let mut target = parse_deep_link(url)?;
        if target.kind == DeepLinkKind::Node {
            if let Some(origin) = target.node_origin.as_deref() {
                if let Some(discovery) = app.try_state::<Arc<DiscoveryService>>() {
                    if let Some(node_id) = discovery.node_id_for_origin(origin) {
                        target.value = node_id;
                    }
                }
            }
        }
        *self
            .pending
            .lock()
            .unwrap_or_else(|poison| poison.into_inner()) = Some(target.clone());
        let _ = app.emit(DEEP_LINK_EVENT, &target);
        Ok(target)
    }

    fn take(&self) -> Option<DeepLinkTarget> {
        self.pending
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .take()
    }
}

pub fn dispatch_url(app: &AppHandle, url: &Url) {
    if let Some(state) = app.try_state::<DeepLinkState>() {
        let _ = state.dispatch(app, url);
    }
}

#[tauri::command]
pub fn take_pending_deep_link(state: tauri::State<'_, DeepLinkState>) -> Option<DeepLinkTarget> {
    state.take()
}

pub fn parse_deep_link(url: &Url) -> Result<DeepLinkTarget, String> {
    if url.scheme() != "lanchat"
        || url.username() != ""
        || url.password().is_some()
        || url.fragment().is_some()
    {
        return Err("invalid MeshX deep link".to_string());
    }

    let host = url
        .host_str()
        .ok_or_else(|| "deep link target is missing".to_string())?;
    let kind = match host {
        "node" => DeepLinkKind::Node,
        "room" => DeepLinkKind::Room,
        "conversation" => DeepLinkKind::Conversation,
        "broadcast" => DeepLinkKind::Broadcast,
        _ => return Err("unsupported MeshX deep link target".to_string()),
    };
    let query = strict_query(url)?;
    let path_value = url
        .path_segments()
        .and_then(|mut segments| segments.next())
        .filter(|value| !value.is_empty())
        .map(str::to_string);
    if url
        .path_segments()
        .is_some_and(|segments| segments.filter(|value| !value.is_empty()).count() > 1)
    {
        return Err("deep link path contains too many segments".to_string());
    }

    let node_origin = query
        .get("node")
        .map(|value| normalize_origin(value))
        .transpose()?;

    let (value, node_origin) = match kind {
        DeepLinkKind::Node => {
            ensure_query_keys(&query, &["origin"])?;
            match (path_value, query.get("origin")) {
                (Some(node_id), None) if valid_node_id(&node_id) => (node_id, None),
                (Some(_), None) => return Err("node deep link nodeId is invalid".to_string()),
                (None, Some(origin)) => {
                    let origin = normalize_origin(origin)?;
                    (origin.clone(), Some(origin))
                }
                (Some(_), Some(_)) => {
                    return Err("node deep link target was provided more than once".to_string())
                }
                (None, None) => return Err("node deep link target is missing".to_string()),
            }
        }
        DeepLinkKind::Room => {
            ensure_query_keys(&query, &["code", "node"])?;
            let value = one_value(path_value, query.get("code"), "room code")?;
            if !valid_token(&value, 4, 64, false) {
                return Err("room code is invalid".to_string());
            }
            (value, node_origin)
        }
        DeepLinkKind::Conversation => {
            ensure_query_keys(&query, &["id", "node"])?;
            let value = one_value(path_value, query.get("id"), "conversation id")?;
            if !valid_token(&value, 1, 128, true) {
                return Err("conversation id is invalid".to_string());
            }
            (value, node_origin)
        }
        DeepLinkKind::Broadcast => {
            ensure_query_keys(&query, &["id", "node"])?;
            let value = one_value(path_value, query.get("id"), "broadcast id")?;
            if !value.bytes().all(|byte| byte.is_ascii_digit())
                || value.parse::<u64>().ok().filter(|id| *id > 0).is_none()
            {
                return Err("broadcast id is invalid".to_string());
            }
            (value, node_origin)
        }
    };

    Ok(DeepLinkTarget {
        kind,
        value,
        node_origin,
    })
}

fn strict_query(url: &Url) -> Result<HashMap<String, String>, String> {
    let mut result = HashMap::new();
    for (key, value) in url.query_pairs() {
        if result
            .insert(key.into_owned(), value.into_owned())
            .is_some()
        {
            return Err("deep link contains duplicate parameters".to_string());
        }
    }
    Ok(result)
}

fn ensure_query_keys(query: &HashMap<String, String>, allowed: &[&str]) -> Result<(), String> {
    if query.keys().all(|key| allowed.contains(&key.as_str())) {
        Ok(())
    } else {
        Err("deep link contains unsupported parameters".to_string())
    }
}

fn one_value(path: Option<String>, query: Option<&String>, label: &str) -> Result<String, String> {
    match (path, query) {
        (Some(_), Some(_)) => Err(format!("{label} was provided more than once")),
        (Some(value), None) => Ok(value),
        (None, Some(value)) => Ok(value.clone()),
        (None, None) => Err(format!("{label} is missing")),
    }
}

fn valid_token(value: &str, minimum: usize, maximum: usize, colon: bool) -> bool {
    (minimum..=maximum).contains(&value.len())
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-') || (colon && byte == b':')
        })
}

#[cfg(test)]
mod tests {
    use super::{parse_deep_link, DeepLinkKind};
    use url::Url;

    #[test]
    fn parses_supported_targets() {
        let node = parse_deep_link(
            &Url::parse("lanchat://node?origin=http%3A%2F%2F10.0.0.8%3A8080").unwrap(),
        )
        .unwrap();
        assert_eq!(node.kind, DeepLinkKind::Node);
        assert_eq!(node.node_origin.as_deref(), Some("http://10.0.0.8:8080"));

        let node_id = parse_deep_link(&Url::parse("lanchat://node/node_abc").unwrap()).unwrap();
        assert_eq!(node_id.value, "node_abc");
        assert_eq!(node_id.node_origin, None);

        let room = parse_deep_link(
            &Url::parse("lanchat://room/ABC_123?node=https%3A%2F%2Fchat.local").unwrap(),
        )
        .unwrap();
        assert_eq!(room.kind, DeepLinkKind::Room);
        assert_eq!(room.value, "ABC_123");
    }

    #[test]
    fn rejects_ambiguous_or_unsafe_targets() {
        assert!(parse_deep_link(&Url::parse("lanchat://room/a?code=b").unwrap()).is_err());
        assert!(
            parse_deep_link(&Url::parse("lanchat://node?origin=file%3A%2F%2F%2Ftmp").unwrap())
                .is_err()
        );
        assert!(parse_deep_link(&Url::parse("lanchat://broadcast/-1").unwrap()).is_err());
        assert!(parse_deep_link(&Url::parse("lanchat://room/code?extra=x").unwrap()).is_err());
    }
}
