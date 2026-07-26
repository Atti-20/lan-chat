use std::sync::Arc;

use tauri::menu::MenuBuilder;
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{image::Image, App, Emitter, Manager};

use crate::discovery::DiscoveryService;
use crate::lifecycle::{show_main_window, LifecycleState};
use crate::updater::trigger_update_check;

const TRAY_OPEN: &str = "open";
const TRAY_RESCAN: &str = "rescan";
const TRAY_UPDATE: &str = "update";
const TRAY_QUIT: &str = "quit";

pub fn install(app: &App) -> tauri::Result<()> {
    let menu = MenuBuilder::new(app)
        .text(TRAY_OPEN, "打开 MeshX")
        .text(TRAY_RESCAN, "重新扫描节点")
        .text(TRAY_UPDATE, "检查更新")
        .separator()
        .text(TRAY_QUIT, "退出")
        .build()?;
    let mut builder = TrayIconBuilder::with_id("lanchat")
        .menu(&menu)
        .tooltip("MeshX")
        // Template rendering is a macOS-only concept; requesting it elsewhere
        // is at best a no-op, so only mark the icon on macOS.
        .icon_as_template(cfg!(target_os = "macos"))
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            if matches!(
                event,
                TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                }
            ) {
                show_main_window(tray.app_handle());
            }
        })
        .on_menu_event(|app, event| match event.id().as_ref() {
            TRAY_OPEN => show_main_window(app),
            TRAY_RESCAN => {
                if let Some(discovery) = app.try_state::<Arc<DiscoveryService>>() {
                    if let Err(error) = discovery.inner().refresh() {
                        let _ = app.emit("desktop://discovery-error", error);
                    }
                }
            }
            TRAY_UPDATE => {
                show_main_window(app);
                trigger_update_check(app.clone());
            }
            TRAY_QUIT => {
                if let Some(state) = app.try_state::<LifecycleState>() {
                    state.begin_quit();
                }
                app.exit(0);
            }
            _ => {}
        });
    // On macOS the status item must use the transparent, monochrome MeshX
    // mark so the system can apply template coloring on either menu-bar
    // appearance. Windows and Linux trays render icons as-is, so the
    // template mark would be near-invisible there; those platforms get the
    // full-color application icon instead.
    let tray_icon = if cfg!(target_os = "macos") {
        Image::from_bytes(include_bytes!("../icons/menu-bar-template.png"))?
    } else {
        Image::from_bytes(include_bytes!("../icons/32x32.png"))?
    };
    builder = builder.icon(tray_icon);
    builder.build(app)?;
    Ok(())
}
