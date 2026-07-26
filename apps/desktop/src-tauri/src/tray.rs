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
        // The menu-bar image is a transparent, monochrome brand mark. Marking
        // it as a macOS template lets the system render it legibly on both
        // light and dark menu bars; it is deliberately independent of the
        // full square application/Dock icon.
        .icon_as_template(true)
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
    // Do not reuse the full application icon here. The status item needs the
    // supplied, transparent MeshX mark so macOS can safely apply template
    // coloring on either menu-bar appearance.
    let menu_bar_icon = Image::from_bytes(include_bytes!("../icons/menu-bar-template.png"))?;
    builder = builder.icon(menu_bar_icon);
    builder.build(app)?;
    Ok(())
}
