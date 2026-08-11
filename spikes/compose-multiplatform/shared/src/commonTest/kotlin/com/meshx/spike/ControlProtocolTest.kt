package com.meshx.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlProtocolTest {
    private val validProperties = mapOf(
        "controlId" to "control-main",
        "organizationId" to "org-main",
        "controlName" to "MeshX Lab",
        "organizationName" to "Lab",
        "secure" to "false",
        "protocolVersion" to "2",
        "controlApiBasePath" to "/api/v2",
        "infoPath" to "/api/v2/control/info",
        "desktopAuthSupported" to "true",
        "refreshTransport" to "HTTP_ONLY_COOKIE",
    )

    @Test
    fun buildsOriginFromCompatibleAnnouncement() {
        val result = controlFromDnsSd("MeshX Lab", 8080, listOf("192.168.1.8"), validProperties)
        assertTrue(result.isSuccess)
        assertEquals("http://192.168.1.8:8080", result.getOrThrow().origin)
    }

    @Test
    fun rejectsProtocolDowngrade() {
        val result = controlFromDnsSd(
            "Old Node",
            8080,
            listOf("192.168.1.9"),
            validProperties + ("protocolVersion" to "1"),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun normalizesManualAddressToOrigin() {
        assertEquals("https://control.example:8443", normalizeControlOrigin("https://control.example:8443/app?q=1"))
        assertEquals("http://192.168.1.20:8080", normalizeControlOrigin("192.168.1.20:8080"))
    }
}
