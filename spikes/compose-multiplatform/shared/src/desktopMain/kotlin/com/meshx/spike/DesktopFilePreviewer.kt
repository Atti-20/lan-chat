package com.meshx.spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.coroutines.resume

class DesktopFilePreviewer : FilePreviewer {
    override suspend fun pickAndPreview(): PreviewResult {
        val file = pickFile() ?: return PreviewResult.Cancelled
        return withContext(Dispatchers.IO) {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    PreviewResult.Failed("当前桌面系统不支持默认应用预览")
                } else {
                    Desktop.getDesktop().open(file)
                    PreviewResult.Opened
                }
            } catch (exception: Exception) {
                PreviewResult.Failed(exception.message ?: "系统预览器打开失败")
            }
        }
    }

    private suspend fun pickFile(): File? = suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            val dialog = FileDialog(null as Frame?, "选择要预览的文件", FileDialog.LOAD)
            dialog.isVisible = true
            val selected = dialog.file?.let { File(dialog.directory, it) }
            dialog.dispose()
            if (continuation.isActive) continuation.resume(selected)
        }
    }
}
