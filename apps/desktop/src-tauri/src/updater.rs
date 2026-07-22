use serde::Serialize;
use tauri::{AppHandle, Emitter};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_updater::UpdaterExt;

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
        let status = match app.updater() {
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
                Err(error) => unconfigured_or_failed(&error.to_string()),
            },
            Err(error) => unconfigured_or_failed(&error.to_string()),
        };
        let _ = app.emit(UPDATE_STATUS_EVENT, &status);
        let body = match status.status {
            "AVAILABLE" => format!(
                "发现新版本 {}，请打开 MeshX 完成更新。",
                status.version.as_deref().unwrap_or("unknown")
            ),
            "UP_TO_DATE" => "当前已经是最新版本。".to_string(),
            "UNCONFIGURED" => "当前构建未配置发布更新源。".to_string(),
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

fn unconfigured_or_failed(message: &str) -> UpdateStatus {
    let unconfigured = [
        "endpoint",
        "pubkey",
        "public key",
        "configured",
        "configuration",
    ]
    .iter()
    .any(|needle| message.to_lowercase().contains(needle));
    UpdateStatus {
        status: if unconfigured {
            "UNCONFIGURED"
        } else {
            "FAILED"
        },
        current_version: None,
        version: None,
        notes: None,
        message: if unconfigured {
            None
        } else {
            Some("unable to check for updates".to_string())
        },
    }
}

#[cfg(test)]
mod tests {
    use super::unconfigured_or_failed;

    #[test]
    fn classifies_missing_release_configuration() {
        assert_eq!(
            unconfigured_or_failed("updater endpoint is missing").status,
            "UNCONFIGURED"
        );
        assert_eq!(unconfigured_or_failed("network timeout").status, "FAILED");
    }
}
