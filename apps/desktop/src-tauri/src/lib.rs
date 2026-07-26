mod attachments;
mod deep_link;
mod discovery;
mod endpoint;
mod lifecycle;
mod native_auth;
mod native_transport;
mod runtime;
mod tray;
mod updater;

use std::sync::Arc;

use attachments::AttachmentDownloadState;
use deep_link::{dispatch_url, DeepLinkState};
use discovery::DiscoveryService;
use lifecycle::{show_main_window, LifecycleState};
use native_auth::NativeAuthState;
use native_transport::NativeTransportState;
use tauri::{Manager, WindowEvent};
use tauri_plugin_deep_link::DeepLinkExt;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // This plugin must remain first so deep links and CLI arguments are
        // forwarded to the existing process before any other plugin can act.
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            for argument in args {
                if let Ok(url) = url::Url::parse(&argument) {
                    dispatch_url(app, &url);
                }
            }
            show_main_window(app);
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--hidden"]),
        ))
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(DeepLinkState::default())
        .manage(LifecycleState::default())
        .manage(NativeAuthState::default())
        .manage(NativeTransportState::default())
        .manage(AttachmentDownloadState::default())
        .setup(|app| {
            use tauri::Manager;

            let discovery =
                DiscoveryService::new(app.handle().clone()).map_err(std::io::Error::other)?;
            app.manage(Arc::clone(&discovery));
            tray::install(app)?;

            if let Ok(Some(urls)) = app.deep_link().get_current() {
                for url in urls {
                    dispatch_url(app.handle(), &url);
                }
            }
            let handle = app.handle().clone();
            app.deep_link().on_open_url(move |event| {
                for url in event.urls() {
                    dispatch_url(&handle, &url);
                }
                show_main_window(&handle);
            });

            for argument in std::env::args() {
                if let Ok(url) = url::Url::parse(&argument) {
                    dispatch_url(app.handle(), &url);
                }
            }

            if !lifecycle::start_hidden() {
                show_main_window(app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() != "main" {
                return;
            }
            if let WindowEvent::CloseRequested { api, .. } = event {
                let state = window.app_handle().state::<LifecycleState>();
                if !state.is_quitting() {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            attachments::save_attachment,
            attachments::cancel_attachment,
            runtime::runtime_info,
            updater::updater_configured,
            lifecycle::desktop_show,
            lifecycle::desktop_hide,
            lifecycle::desktop_quit,
            deep_link::take_pending_deep_link,
            discovery::discovered_nodes,
            discovery::refresh_discovery,
            discovery::add_manual_node,
            discovery::add_server_fallback_nodes,
            native_auth::desktop_login,
            native_auth::desktop_refresh,
            native_auth::desktop_logout,
            native_transport::node_http_request,
            native_transport::cancel_node_request,
            native_transport::open_node_socket,
            native_transport::send_node_socket,
            native_transport::close_node_socket,
        ])
        .build(tauri::generate_context!())
        .expect("failed to build MeshX desktop client")
        .run(|_app, _event| {
            // On macOS, clicking the Dock icon of an already-running app does
            // not start a second instance. It emits Reopen instead. Our close
            // policy hides the only window, so restore it explicitly here.
            // 参数带下划线前缀：其他平台上该 cfg 块被裁掉，参数未被使用。
            #[cfg(target_os = "macos")]
            if let tauri::RunEvent::Reopen {
                has_visible_windows: false,
                ..
            } = _event
            {
                show_main_window(_app);
            }
        });
}

#[cfg(test)]
mod tests {
    #[test]
    fn windows_bundle_configuration_stays_polished() {
        // The Windows smoke build runs with --no-bundle, so installer config
        // is only exercised when a release is tagged. Pin the invariants the
        // release gate depends on here so regressions surface in every CI run.
        let config: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.windows.conf.json")).unwrap();
        let bundle = &config["bundle"];

        let targets: Vec<&str> = bundle["targets"]
            .as_array()
            .expect("windows bundle targets")
            .iter()
            .filter_map(|value| value.as_str())
            .collect();
        assert!(
            targets.contains(&"nsis") && targets.contains(&"msi"),
            "release verification expects both NSIS and MSI artifacts"
        );

        assert!(
            bundle["publisher"]
                .as_str()
                .is_some_and(|publisher| !publisher.trim().is_empty()),
            "without a publisher, Add/Remove Programs falls back to an identifier segment"
        );

        let nsis = &bundle["windows"]["nsis"];
        assert_eq!(
            nsis["installMode"].as_str(),
            Some("currentUser"),
            "per-user install must not require Administrator access"
        );
        assert_eq!(nsis["installerIcon"].as_str(), Some("icons/icon.ico"));
        let languages: Vec<&str> = nsis["languages"]
            .as_array()
            .expect("nsis languages")
            .iter()
            .filter_map(|value| value.as_str())
            .collect();
        assert!(
            languages.contains(&"SimpChinese"),
            "the product UI is Chinese-first; the installer must offer it"
        );

        // The referenced installer icon must exist in the repository.
        assert!(!include_bytes!("../icons/icon.ico").is_empty());
    }
}
