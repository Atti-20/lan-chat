package com.meshx.spike

import io.ktor.client.engine.cio.CIO
import java.net.InetAddress

fun desktopPlatformServices(): PlatformServices {
    val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop")
    return PlatformServices(
        platformName = "Desktop · Compose Multiplatform",
        deviceType = "desktop",
        deviceName = hostName.take(120),
        localNetworkAccess = UnrestrictedLocalNetworkAccess(),
        discovery = DesktopControlDiscovery(),
        filePreviewer = DesktopFilePreviewer(),
        httpClient = platformHttpClient(CIO.create()),
    )
}
