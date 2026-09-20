use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Emitter};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_updater::UpdaterExt;
use url::Url;

const UPDATE_STATUS_EVENT: &str = "desktop://update-status";

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateStatus {
    status: &'static str,
    current_version: Option<String>,
    version: Option<String>,
    notes: Option<String>,
    message: Option<String>,
}

pub fn trigger_update_check(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let status = if !is_updater_configured(&app) {
            unconfigured()
        } else {
            match app.updater() {
                Ok(updater) => match updater.check().await {
                    Ok(Some(update)) => UpdateStatus {
                        status: "AVAILABLE",
                        current_version: Some(update.current_version),
                        version: Some(update.version),
                        notes: update.body,
                        message: None,
                    },
                    Ok(None) => UpdateStatus {
                        status: "UP_TO_DATE",
                        current_version: Some(app.package_info().version.to_string()),
                        version: None,
                        notes: None,
                        message: None,
                    },
                    Err(_) => failed(),
                },
                Err(_) => failed(),
            }
        };
        let _ = app.emit(UPDATE_STATUS_EVENT, &status);
        let body = match status.status {
            "AVAILABLE" => format!(
                "发现新版本 {}，请打开 MeshX 完成更新。",
                status.version.as_deref().unwrap_or("unknown")
            ),
            "UP_TO_DATE" => "当前已经是最新版本。".to_string(),
            "UNCONFIGURED" => "当前构建未配置发布更新源或公钥。".to_string(),
            _ => status
                .message
                .clone()
                .unwrap_or_else(|| "检查更新失败，请稍后重试。".to_string()),
        };
        let _ = app
            .notification()
            .builder()
            .title("MeshX 更新")
            .body(body)
            .show();
    });
}

pub fn is_updater_configured(app: &AppHandle) -> bool {
    updater_config_is_valid(app.config().plugins.0.get("updater"))
}

#[tauri::command]
pub fn updater_configured(app: AppHandle) -> bool {
    is_updater_configured(&app)
}

fn updater_config_is_valid(config: Option<&Value>) -> bool {
    let Some(config) = config.and_then(Value::as_object) else {
        return false;
    };
    let pubkey_valid = config
        .get("pubkey")
        .and_then(Value::as_str)
        .is_some_and(|value| !value.trim().is_empty());
    let endpoints_valid = config
        .get("endpoints")
        .and_then(Value::as_array)
        .is_some_and(|endpoints| {
            !endpoints.is_empty()
                && endpoints.iter().all(|endpoint| {
                    endpoint
                        .as_str()
                        .and_then(|value| Url::parse(value).ok())
                        .is_some_and(|url| url.scheme() == "https" && url.host_str().is_some())
                })
        });
    pubkey_valid && endpoints_valid
}

fn unconfigured() -> UpdateStatus {
    UpdateStatus {
        status: "UNCONFIGURED",
        current_version: None,
        version: None,
        notes: None,
        message: None,
    }
}

fn failed() -> UpdateStatus {
    UpdateStatus {
        status: "FAILED",
        current_version: None,
        version: None,
        notes: None,
        message: Some("unable to check for updates".to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::updater_config_is_valid;
    use serde_json::json;

    #[test]
    fn requires_https_endpoint_and_public_key() {
        assert!(!updater_config_is_valid(None));
        assert!(!updater_config_is_valid(Some(&json!({
            "endpoints": [],
            "pubkey": ""
        }))));
        assert!(!updater_config_is_valid(Some(&json!({
            "endpoints": ["https://updates.example/latest.json"],
            "pubkey": ""
        }))));
        assert!(!updater_config_is_valid(Some(&json!({
            "endpoints": ["http://updates.example/latest.json"],
            "pubkey": "public-key"
        }))));
        assert!(updater_config_is_valid(Some(&json!({
            "endpoints": ["https://updates.example/latest.json"],
            "pubkey": "public-key"
        }))));
    }
}
