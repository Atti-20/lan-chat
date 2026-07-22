use std::sync::atomic::{AtomicBool, Ordering};

use tauri::{AppHandle, Manager, WebviewWindow};

#[derive(Default)]
pub struct LifecycleState {
    quitting: AtomicBool,
}

impl LifecycleState {
    pub fn begin_quit(&self) {
        self.quitting.store(true, Ordering::SeqCst);
    }

    pub fn is_quitting(&self) -> bool {
        self.quitting.load(Ordering::SeqCst)
    }
}

pub fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        show_window(&window);
    }
}

fn show_window(window: &WebviewWindow) {
    let _ = window.unminimize();
    let _ = window.show();
    let _ = window.set_focus();
}

pub fn start_hidden() -> bool {
    std::env::args_os().any(|arg| arg == "--hidden")
}

#[tauri::command]
pub fn desktop_show(app: AppHandle) {
    show_main_window(&app);
}

#[tauri::command]
pub fn desktop_hide(app: AppHandle) -> Result<(), String> {
    app.get_webview_window("main")
        .ok_or_else(|| "main window is unavailable".to_string())?
        .hide()
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub fn desktop_quit(app: AppHandle, state: tauri::State<'_, LifecycleState>) {
    state.begin_quit();
    app.exit(0);
}
