package com.meshx.spike

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiscoveryPhase {
    IDLE,
    STARTING,
    RUNNING,
    FAILED,
    STOPPED,
}

data class DiscoveredControl(
    val controlId: String,
    val organizationId: String,
    val name: String,
    val organizationName: String,
    val origin: String,
    val secure: Boolean,
    val serviceName: String,
)

data class DiscoveryState(
    val phase: DiscoveryPhase = DiscoveryPhase.IDLE,
    val controls: List<DiscoveredControl> = emptyList(),
    val message: String? = null,
)

interface ControlDiscovery {
    val state: StateFlow<DiscoveryState>
    fun start()
    fun stop()
}

enum class LocalNetworkAccessPhase {
    NOT_REQUIRED,
    REQUIRED,
    REQUESTING,
    GRANTED,
    DENIED,
}

data class LocalNetworkAccessState(
    val phase: LocalNetworkAccessPhase,
    val canRequest: Boolean = false,
    val message: String? = null,
) {
    val granted: Boolean
        get() = phase == LocalNetworkAccessPhase.NOT_REQUIRED || phase == LocalNetworkAccessPhase.GRANTED
}

interface LocalNetworkAccess : AutoCloseable {
    val state: StateFlow<LocalNetworkAccessState>
    fun refresh()
    fun request()
    override fun close() = Unit
}

class UnrestrictedLocalNetworkAccess : LocalNetworkAccess {
    private val _state = MutableStateFlow(LocalNetworkAccessState(LocalNetworkAccessPhase.NOT_REQUIRED))
    override val state: StateFlow<LocalNetworkAccessState> = _state.asStateFlow()
    override fun refresh() = Unit
    override fun request() = Unit
}

sealed interface PreviewResult {
    data object Opened : PreviewResult
    data object Cancelled : PreviewResult
    data class Failed(val message: String) : PreviewResult
}

interface FilePreviewer {
    suspend fun pickAndPreview(): PreviewResult
}

data class PlatformServices(
    val platformName: String,
    val deviceType: String,
    val deviceName: String,
    val localNetworkAccess: LocalNetworkAccess,
    val discovery: ControlDiscovery,
    val filePreviewer: FilePreviewer,
    val httpClient: HttpClient,
    val deviceIdentityStore: DeviceIdentityStore? = null,
) : AutoCloseable {
    override fun close() {
        discovery.stop()
        localNetworkAccess.close()
        httpClient.close()
    }
}
