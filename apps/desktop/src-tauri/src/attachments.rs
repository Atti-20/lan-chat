use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use reqwest::redirect::Policy;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{command, ipc::Channel};
use tokio::fs::OpenOptions;
use tokio::io::AsyncWriteExt;
use url::Url;

use crate::discovery::DiscoveryService;
use crate::endpoint::normalize_origin;

const MAX_ATTACHMENT_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const CANCELLED_ERROR: &str = "ATTACHMENT_CANCELLED";

#[derive(Default)]
pub struct AttachmentDownloadState {
    cancellations: Mutex<HashMap<String, Arc<AtomicBool>>>,
}

impl AttachmentDownloadState {
    fn register(&self, download_id: &str) -> Result<Arc<AtomicBool>, String> {
        validate_download_id(download_id)?;
        let mut cancellations = self
            .cancellations
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if cancellations.contains_key(download_id) {
            return Err("下载任务已存在".to_string());
        }
        let cancelled = Arc::new(AtomicBool::new(false));
        cancellations.insert(download_id.to_string(), Arc::clone(&cancelled));
        Ok(cancelled)
    }

    fn cancel(&self, download_id: &str) -> bool {
        let cancellations = self
            .cancellations
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        cancellations.get(download_id).is_some_and(|cancelled| {
            cancelled.store(true, Ordering::Release);
            true
        })
    }

    fn finish(&self, download_id: &str) {
        self.cancellations
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(download_id);
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveAttachmentRequest {
    download_id: String,
    origin: String,
    url: String,
    path: String,
    expected_bytes: Option<u64>,
    sha256: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentProgress {
    download_id: String,
    written_bytes: u64,
    total_bytes: Option<u64>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedAttachment {
    bytes: u64,
    sha256: String,
}

/// Streams a short-lived, already-authorized attachment URL to a temporary
/// sibling file, verifies it, then atomically replaces the user-selected path.
#[command(rename_all = "camelCase")]
pub async fn save_attachment(
    request: SaveAttachmentRequest,
    on_progress: Channel<AttachmentProgress>,
    state: tauri::State<'_, AttachmentDownloadState>,
    discovery: tauri::State<'_, Arc<DiscoveryService>>,
) -> Result<SavedAttachment, String> {
    let origin = normalize_origin(&request.origin)?;
    if !discovery.allows_origin(&origin) {
        return Err("文件来源节点尚未完成原生握手".to_string());
    }
    let cancelled = state.register(&request.download_id)?;
    let result = execute_save(&request, &cancelled, |progress| {
        let _ = on_progress.send(progress.clone());
    })
    .await;
    state.finish(&request.download_id);
    result
}

#[command]
pub fn cancel_attachment(
    download_id: String,
    state: tauri::State<'_, AttachmentDownloadState>,
) -> bool {
    state.cancel(&download_id)
}

async fn execute_save<F>(
    request: &SaveAttachmentRequest,
    cancelled: &AtomicBool,
    progress: F,
) -> Result<SavedAttachment, String>
where
    F: FnMut(&AttachmentProgress),
{
    let destination = validated_destination(&request.path)?;
    let temporary = temporary_path(&destination, &request.download_id)?;
    let result = download_to_temporary(request, &temporary, cancelled, progress).await;
    let saved = match result {
        Ok(saved) => {
            if cancelled.load(Ordering::Acquire) {
                Err(CANCELLED_ERROR.to_string())
            } else {
                atomic_replace(&temporary, &destination)
                    .await
                    .map(|_| saved)
                    .map_err(|error| format!("无法提交保存文件：{error}"))
            }
        }
        Err(error) => Err(error),
    };
    if saved.is_err() {
        let _ = tokio::fs::remove_file(&temporary).await;
    }
    saved
}

async fn download_to_temporary<F>(
    request: &SaveAttachmentRequest,
    temporary: &Path,
    cancelled: &AtomicBool,
    mut progress: F,
) -> Result<SavedAttachment, String>
where
    F: FnMut(&AttachmentProgress),
{
    let source = validated_source(&request.url)?;
    if source.origin().ascii_serialization() != normalize_origin(&request.origin)? {
        return Err("文件地址离开了所选节点".to_string());
    }
    let expected_hash = validated_hash(request.sha256.as_deref())?;
    if request
        .expected_bytes
        .is_some_and(|length| length > MAX_ATTACHMENT_BYTES)
    {
        return Err("文件超过桌面端允许的 4 GB 大小".to_string());
    }
    ensure_not_cancelled(cancelled)?;

    let client = Client::builder()
        .redirect(Policy::none())
        .no_proxy()
        .connect_timeout(Duration::from_secs(10))
        .read_timeout(Duration::from_secs(30))
        .user_agent(concat!("MeshX-Desktop/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|error| format!("无法初始化文件下载：{error}"))?;
    let mut response = client
        .get(source)
        .header(reqwest::header::ACCEPT_ENCODING, "identity")
        .send()
        .await
        .map_err(|error| format!("文件请求失败：{error}"))?;
    if !response.status().is_success() {
        return Err(format!("文件请求失败：HTTP {}", response.status()));
    }

    let response_length = response.content_length();
    if response_length.is_some_and(|length| length > MAX_ATTACHMENT_BYTES) {
        return Err("文件超过桌面端允许的 4 GB 大小".to_string());
    }
    if let (Some(expected), Some(actual)) = (request.expected_bytes, response_length) {
        if expected != actual {
            return Err("文件长度与消息记录不一致".to_string());
        }
    }
    let total_bytes = request.expected_bytes.or(response_length);
    ensure_not_cancelled(cancelled)?;

    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(temporary)
        .await
        .map_err(|error| format!("无法创建临时保存文件：{error}"))?;
    let mut written = 0_u64;
    let mut hasher = Sha256::new();
    let mut last_progress = Instant::now() - Duration::from_secs(1);
    progress(&AttachmentProgress {
        download_id: request.download_id.clone(),
        written_bytes: 0,
        total_bytes,
    });

    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|error| format!("文件传输中断：{error}"))?
    {
        ensure_not_cancelled(cancelled)?;
        written = written.saturating_add(chunk.len() as u64);
        if written > MAX_ATTACHMENT_BYTES {
            return Err("文件超过桌面端允许的 4 GB 大小".to_string());
        }
        file.write_all(&chunk)
            .await
            .map_err(|error| format!("写入文件失败：{error}"))?;
        hasher.update(&chunk);
        if last_progress.elapsed() >= Duration::from_millis(100) {
            progress(&AttachmentProgress {
                download_id: request.download_id.clone(),
                written_bytes: written,
                total_bytes,
            });
            last_progress = Instant::now();
        }
    }
    ensure_not_cancelled(cancelled)?;
    if total_bytes.is_some_and(|expected| expected != written) {
        return Err("文件传输未完成，保存已取消".to_string());
    }

    let actual_hash = lowercase_hex(&hasher.finalize());
    if expected_hash
        .as_deref()
        .is_some_and(|expected| expected != actual_hash)
    {
        return Err("文件 SHA-256 校验失败".to_string());
    }
    file.flush()
        .await
        .map_err(|error| format!("写入文件失败：{error}"))?;
    file.sync_all()
        .await
        .map_err(|error| format!("同步保存文件失败：{error}"))?;
    drop(file);
    ensure_not_cancelled(cancelled)?;
    progress(&AttachmentProgress {
        download_id: request.download_id.clone(),
        written_bytes: written,
        total_bytes,
    });
    Ok(SavedAttachment {
        bytes: written,
        sha256: actual_hash,
    })
}

fn validated_source(value: &str) -> Result<Url, String> {
    let source = Url::parse(value).map_err(|_| "文件地址无效".to_string())?;
    if !matches!(source.scheme(), "https" | "http")
        || source.host_str().is_none()
        || !source.username().is_empty()
        || source.password().is_some()
    {
        return Err("文件地址必须是无凭据的 HTTP(S) 地址".to_string());
    }
    Ok(source)
}

fn validated_destination(value: &str) -> Result<PathBuf, String> {
    let destination = PathBuf::from(value);
    if destination.file_name().is_none() || destination.parent().is_none() {
        return Err("保存位置无效".to_string());
    }
    Ok(destination)
}

fn temporary_path(destination: &Path, download_id: &str) -> Result<PathBuf, String> {
    validate_download_id(download_id)?;
    let parent = destination
        .parent()
        .ok_or_else(|| "保存位置无效".to_string())?;
    let file_name = destination
        .file_name()
        .ok_or_else(|| "保存位置无效".to_string())?
        .to_string_lossy();
    Ok(parent.join(format!(".{file_name}.{download_id}.meshx-part")))
}

fn validate_download_id(value: &str) -> Result<(), String> {
    if (8..=100).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
    {
        Ok(())
    } else {
        Err("下载任务 ID 无效".to_string())
    }
}

fn validated_hash(value: Option<&str>) -> Result<Option<String>, String> {
    let Some(value) = value.filter(|value| !value.trim().is_empty()) else {
        return Ok(None);
    };
    let normalized = value.trim().to_ascii_lowercase();
    if normalized.len() == 64 && normalized.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        Ok(Some(normalized))
    } else {
        Err("文件 SHA-256 无效".to_string())
    }
}

fn ensure_not_cancelled(cancelled: &AtomicBool) -> Result<(), String> {
    if cancelled.load(Ordering::Acquire) {
        Err(CANCELLED_ERROR.to_string())
    } else {
        Ok(())
    }
}

fn lowercase_hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    bytes.iter().fold(
        String::with_capacity(bytes.len() * 2),
        |mut output, byte| {
            let _ = write!(output, "{byte:02x}");
            output
        },
    )
}

#[cfg(not(target_os = "windows"))]
async fn atomic_replace(source: &Path, destination: &Path) -> std::io::Result<()> {
    tokio::fs::rename(source, destination).await
}

#[cfg(target_os = "windows")]
async fn atomic_replace(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;

    let source = source.to_path_buf();
    let destination = destination.to_path_buf();
    tokio::task::spawn_blocking(move || {
        use windows_sys::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND};
        use windows_sys::Win32::Storage::FileSystem::{
            MoveFileExW, ReplaceFileW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
            REPLACEFILE_WRITE_THROUGH,
        };

        fn wide(value: &OsStr) -> Vec<u16> {
            value.encode_wide().chain(std::iter::once(0)).collect()
        }

        let source_wide = wide(source.as_os_str());
        let destination_wide = wide(destination.as_os_str());
        let move_into_place = || unsafe {
            MoveFileExW(
                source_wide.as_ptr(),
                destination_wide.as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        };
        let success = if destination.exists() {
            // ReplaceFileW keeps the destination's attributes and ACLs, but it
            // requires the destination to still exist. If it disappears
            // between the check above and this call, fall back to a plain
            // replace-if-exists move instead of failing the whole save.
            let replaced = unsafe {
                ReplaceFileW(
                    destination_wide.as_ptr(),
                    source_wide.as_ptr(),
                    std::ptr::null(),
                    REPLACEFILE_WRITE_THROUGH,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                )
            };
            if replaced == 0
                && matches!(
                    std::io::Error::last_os_error().raw_os_error(),
                    Some(code) if code == ERROR_FILE_NOT_FOUND as i32
                        || code == ERROR_PATH_NOT_FOUND as i32
                )
            {
                move_into_place()
            } else {
                replaced
            }
        } else {
            move_into_place()
        };
        if success == 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(())
        }
    })
    .await
    .map_err(std::io::Error::other)?
}

#[cfg(test)]
mod tests {
    use super::{
        execute_save, temporary_path, AttachmentDownloadState, SaveAttachmentRequest,
        CANCELLED_ERROR,
    };
    use sha2::{Digest, Sha256};
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::thread;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn response(status: &str, headers: &[(&str, String)], body: &[u8]) -> String {
        let mut value = format!("HTTP/1.1 {status}\r\nConnection: close\r\n");
        for (name, header_value) in headers {
            value.push_str(&format!("{name}: {header_value}\r\n"));
        }
        value.push_str("\r\n");
        value.push_str(&String::from_utf8_lossy(body));
        value
    }

    fn serve_once(payload: String) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = [0_u8; 2048];
            let _ = stream.read(&mut request);
            stream.write_all(payload.as_bytes()).unwrap();
        });
        format!("http://{address}/file")
    }

    fn destination(label: &str) -> std::path::PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("meshx-attachment-{label}-{nonce}.bin"))
    }

    fn request(url: String, path: &std::path::Path, body: &[u8]) -> SaveAttachmentRequest {
        let origin = url::Url::parse(&url)
            .unwrap()
            .origin()
            .ascii_serialization();
        SaveAttachmentRequest {
            download_id: "download_test_123".to_string(),
            origin,
            url,
            path: path.to_string_lossy().to_string(),
            expected_bytes: Some(body.len() as u64),
            sha256: Some(format!("{:x}", Sha256::digest(body))),
        }
    }

    #[test]
    fn saves_verified_bytes_and_reports_monotonic_progress() {
        tauri::async_runtime::block_on(async {
            let body = b"verified attachment";
            let url = serve_once(response(
                "200 OK",
                &[("Content-Length", body.len().to_string())],
                body,
            ));
            let destination = destination("success");
            let mut progress = Vec::new();
            let saved = execute_save(
                &request(url, &destination, body),
                &AtomicBool::new(false),
                |item| progress.push(item.written_bytes),
            )
            .await
            .unwrap();
            assert_eq!(saved.bytes, body.len() as u64);
            assert_eq!(std::fs::read(&destination).unwrap(), body);
            assert!(progress.windows(2).all(|pair| pair[0] <= pair[1]));
            let _ = std::fs::remove_file(destination);
        });
    }

    #[test]
    fn hash_failure_preserves_existing_destination_and_removes_temporary_file() {
        tauri::async_runtime::block_on(async {
            let body = b"unexpected";
            let url = serve_once(response(
                "200 OK",
                &[("Content-Length", body.len().to_string())],
                body,
            ));
            let destination = destination("hash-mismatch");
            std::fs::write(&destination, b"existing").unwrap();
            let mut request = request(url, &destination, body);
            request.sha256 = Some("0".repeat(64));
            let temporary = temporary_path(&destination, &request.download_id).unwrap();
            assert!(execute_save(&request, &AtomicBool::new(false), |_| {})
                .await
                .unwrap_err()
                .contains("SHA-256"));
            assert_eq!(std::fs::read(&destination).unwrap(), b"existing");
            assert!(!temporary.exists());
            let _ = std::fs::remove_file(destination);
        });
    }

    #[test]
    fn rejects_redirects_without_touching_the_destination() {
        tauri::async_runtime::block_on(async {
            let url = serve_once(response(
                "302 Found",
                &[("Location", "https://example.invalid/file".to_string())],
                b"",
            ));
            let destination = destination("redirect");
            let request = request(url, &destination, b"");
            assert!(execute_save(&request, &AtomicBool::new(false), |_| {})
                .await
                .unwrap_err()
                .contains("HTTP 302"));
            assert!(!destination.exists());
        });
    }

    #[test]
    fn cancellation_is_scoped_and_idempotent() {
        let state = AttachmentDownloadState::default();
        let cancelled = state.register("download_cancel_1").unwrap();
        assert!(state.cancel("download_cancel_1"));
        assert!(state.cancel("download_cancel_1"));
        assert!(cancelled.load(Ordering::Acquire));
        state.finish("download_cancel_1");
        assert!(!state.cancel("download_cancel_1"));

        tauri::async_runtime::block_on(async {
            let destination = destination("cancelled");
            let request = request("http://127.0.0.1:9/file".to_string(), &destination, b"");
            assert_eq!(
                execute_save(&request, &AtomicBool::new(true), |_| {})
                    .await
                    .unwrap_err(),
                CANCELLED_ERROR
            );
            assert!(!destination.exists());
        });
    }
}
