mod deep_link;
mod discovery;
mod endpoint;
mod lifecycle;
mod native_auth;
mod runtime;
mod tray;
mod updater;

use std::sync::Arc;

use deep_link::{dispatch_url, DeepLinkState};
use discovery::DiscoveryService;
use lifecycle::{show_main_window, LifecycleState};
use native_auth::NativeAuthState;
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
        .setup(|app| {
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
            runtime::runtime_info,
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
        ])
        .run(tauri::generate_context!())
        .expect("failed to run LANChat desktop client");
}
