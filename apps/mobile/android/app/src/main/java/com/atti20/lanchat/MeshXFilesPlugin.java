package com.atti20.lanchat;

import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uses Android's Storage Access Framework instead of a WebView anchor so the
 * user explicitly chooses the destination before an attachment is written.
 */
@CapacitorPlugin(name = "MeshXFiles")
public class MeshXFilesPlugin extends Plugin {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @PluginMethod
    public void save(PluginCall call) {
        String rawUrl = call.getString("url");
        if (!isDownloadUrl(rawUrl)) {
            call.reject("文件地址无效");
            return;
        }

        String name = safeFileName(call.getString("name", "MeshX 文件"));
        String mimeType = call.getString("mimeType", "application/octet-stream");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(call, intent, "saveFileResult");
    }

    @ActivityCallback
    private void saveFileResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != android.app.Activity.RESULT_OK
                || result.getData() == null || result.getData().getData() == null) {
            JSObject cancelled = new JSObject();
            cancelled.put("cancelled", true);
            call.resolve(cancelled);
            return;
        }

        String rawUrl = call.getString("url");
        Uri destination = result.getData().getData();
        io.execute(() -> copyToDocument(call, rawUrl, destination));
    }

    private void copyToDocument(PluginCall call, String rawUrl, Uri destination) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(rawUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("文件请求失败：HTTP " + status);
            }

            try (InputStream input = connection.getInputStream();
                 OutputStream output = getContext().getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("无法打开所选保存位置");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }

            JSObject saved = new JSObject();
            saved.put("location", documentName(destination));
            call.resolve(saved);
        } catch (Exception exception) {
            call.reject("保存文件失败", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String documentName(Uri uri) {
        try (android.database.Cursor cursor = getContext().getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) {
            // The picked document provider can omit display metadata.
        }
        return "所选位置";
    }

    private static boolean isDownloadUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return false;
        try {
            URL url = new URL(rawUrl);
            return ("https".equalsIgnoreCase(url.getProtocol()) || "http".equalsIgnoreCase(url.getProtocol()))
                    && url.getHost() != null && !url.getHost().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safeFileName(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\\\/:*?\\\"<>|\\p{Cntrl}]", "_").trim();
        return normalized.isEmpty() ? "MeshX 文件" : normalized.substring(0, Math.min(normalized.length(), 120));
    }
}
