package com.meshx.spike

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class LoginUiState(
    val origin: String = "",
    val username: String = "",
    val password: String = "",
    val isLoggingIn: Boolean = false,
    val session: AuthSession? = null,
    val message: String? = null,
    val isError: Boolean = false,
)

class MeshXAppState(
    private val scope: CoroutineScope,
    private val services: PlatformServices,
) {
    val discovery: StateFlow<DiscoveryState> = services.discovery.state
    val localNetworkAccess: StateFlow<LocalNetworkAccessState> = services.localNetworkAccess.state

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()
    private val loginClient = LoginClient(services.httpClient, services.deviceType, services.deviceName)
    private var loginJob: Job? = null
    private var accessJob: Job? = null

    fun start() {
        if (accessJob?.isActive == true) return
        services.localNetworkAccess.refresh()
        accessJob = scope.launch {
            services.localNetworkAccess.state.collectLatest { access ->
                if (access.granted) services.discovery.start() else services.discovery.stop()
            }
        }
    }

    fun requestLocalNetworkAccess() = services.localNetworkAccess.request()

    fun selectControl(control: DiscoveredControl) {
        _login.value = _login.value.copy(origin = control.origin, message = null, isError = false)
    }

    fun updateOrigin(value: String) = updateForm { copy(origin = value) }
    fun updateUsername(value: String) = updateForm { copy(username = value) }
    fun updatePassword(value: String) = updateForm { copy(password = value) }

    fun login() {
        if (loginJob?.isActive == true) return
        val form = _login.value
        _login.value = form.copy(isLoggingIn = true, message = null, isError = false, session = null)
        loginJob = scope.launch {
            when (val result = loginClient.login(form.origin, form.username, form.password)) {
                is LoginResult.Success -> _login.value = _login.value.copy(
                    password = "",
                    isLoggingIn = false,
                    session = result.session,
                    message = "登录协议验证成功；生产接入仍需完成设备证书注册。",
                    isError = false,
                )
                is LoginResult.Failure -> _login.value = _login.value.copy(
                    isLoggingIn = false,
                    message = result.message,
                    isError = true,
                )
            }
        }
    }

    fun previewFile() {
        scope.launch {
            _login.value = _login.value.copy(message = null, isError = false)
            when (val result = services.filePreviewer.pickAndPreview()) {
                PreviewResult.Opened -> _login.value = _login.value.copy(message = "已交给系统预览器打开。")
                PreviewResult.Cancelled -> Unit
                is PreviewResult.Failed -> _login.value = _login.value.copy(message = result.message, isError = true)
            }
        }
    }

    fun close() {
        accessJob?.cancel()
        services.close()
    }

    private fun updateForm(transform: LoginUiState.() -> LoginUiState) {
        _login.value = _login.value.transform().copy(message = null, isError = false, session = null)
    }
}
