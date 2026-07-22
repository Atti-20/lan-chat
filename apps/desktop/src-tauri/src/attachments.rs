use std::path::PathBuf;

use reqwest::Client;
use tauri::command;
use tokio::{fs::File, io::AsyncWriteExt};
use url::Url;

const MAX_ATTACHMENT_BYTES: u64 = 4 * 1024 * 1024 * 1024;

/// Streams a short-lived, already-authorized attachment URL to a path selected
/// through Tauri's native save dialog. The command deliberately accepts only
/// HTTP(S) resources and never interprets a URL as a local path.
#[command]
pub async fn save_attachment(url: String, path: String) -> Result<(), String> {
    let source = Url::parse(&url).map_err(|_| "文件地址无效".to_string())?;
    if !matches!(source.scheme(), "https" | "http") || source.host_str().is_none() {
        return Err("文件地址必须是 HTTP(S) 地址".to_string());
    }

    let destination = PathBuf::from(path);
    if destination.file_name().is_none() {
        return Err("保存位置无效".to_string());
    }

    let mut response = Client::new()
        .get(source)
        .header(reqwest::header::ACCEPT_ENCODING, "identity")
        .send()
        .await
        .map_err(|error| format!("文件请求失败：{error}"))?
        .error_for_status()
        .map_err(|error| format!("文件请求失败：{error}"))?;

    if response
        .content_length()
        .is_some_and(|length| length > MAX_ATTACHMENT_BYTES)
    {
        return Err("文件超过桌面端允许的 4 GB 大小".to_string());
    }

    let mut file = File::create(&destination)
        .await
        .map_err(|error| format!("无法创建保存文件：{error}"))?;
    let mut written = 0_u64;
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|error| format!("文件传输中断：{error}"))?
    {
        written = written.saturating_add(chunk.len() as u64);
        if written > MAX_ATTACHMENT_BYTES {
            let _ = tokio::fs::remove_file(&destination).await;
            return Err("文件超过桌面端允许的 4 GB 大小".to_string());
        }
        file.write_all(&chunk)
            .await
            .map_err(|error| format!("写入文件失败：{error}"))?;
    }
    file.flush()
        .await
        .map_err(|error| format!("写入文件失败：{error}"))?;
    Ok(())
}
