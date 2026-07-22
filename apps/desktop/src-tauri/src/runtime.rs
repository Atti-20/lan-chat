use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeInfo {
    runtime: &'static str,
    platform: &'static str,
    version: String,
}

#[tauri::command]
pub fn runtime_info(app: tauri::AppHandle) -> RuntimeInfo {
    RuntimeInfo {
        runtime: "tauri",
        platform: current_platform(),
        version: app.package_info().version.to_string(),
    }
}

const fn current_platform() -> &'static str {
    if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else {
        "unknown"
    }
}
