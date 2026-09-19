package com.meshx.spike.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.meshx.spike.FilePreviewer
import com.meshx.spike.PreviewResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AndroidFilePreviewer(private val activity: ComponentActivity) : FilePreviewer {
    private val lock = Any()
    private var pending: CancellableContinuation<PreviewResult>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val result = if (uri == null) PreviewResult.Cancelled else openUri(uri)
        takePending()?.resume(result)
    }

    override suspend fun pickAndPreview(): PreviewResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val accepted = synchronized(lock) {
                if (pending != null) false else {
                    pending = continuation
                    true
                }
            }
            if (!accepted) {
                continuation.resume(PreviewResult.Failed("已有文件选择窗口正在打开"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                synchronized(lock) {
                    if (pending === continuation) pending = null
                }
            }
            picker.launch(arrayOf("*/*"))
        }
    }

    private fun openUri(uri: Uri): PreviewResult = try {
        val mimeType = activity.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "使用系统应用预览"))
        PreviewResult.Opened
    } catch (_: ActivityNotFoundException) {
        PreviewResult.Failed("系统中没有可以预览此文件的应用")
    } catch (_: SecurityException) {
        PreviewResult.Failed("系统未授予该文件的读取权限")
    }

    private fun takePending(): CancellableContinuation<PreviewResult>? = synchronized(lock) {
        pending.also { pending = null }
    }
}
