package com.meshx.spike.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.meshx.spike.MeshXApp
import com.meshx.spike.desktopPlatformServices

fun main() = application {
    val services = remember { desktopPlatformServices() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "MeshX Compose Multiplatform Spike",
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
    ) {
        window.minimumSize = java.awt.Dimension(720, 640)
        MeshXApp(services)
    }
}
