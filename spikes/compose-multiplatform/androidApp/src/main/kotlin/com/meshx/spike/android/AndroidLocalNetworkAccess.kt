package com.meshx.spike.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.meshx.spike.LocalNetworkAccess
import com.meshx.spike.LocalNetworkAccessPhase
import com.meshx.spike.LocalNetworkAccessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PERMISSION_REQUESTED_KEY = "access_local_network_requested"

class AndroidLocalNetworkAccess(
    private val activity: ComponentActivity,
) : LocalNetworkAccess, DefaultLifecycleObserver {
    private val preferences = activity.getSharedPreferences("meshx_spike_permissions", 0)
    private val _state = MutableStateFlow(resolveState(requesting = false))
    override val state: StateFlow<LocalNetworkAccessState> = _state.asStateFlow()

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        _state.value = resolveState(requesting = false)
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        refresh()
    }

    override fun refresh() {
        if (_state.value.phase != LocalNetworkAccessPhase.REQUESTING) {
            _state.value = resolveState(requesting = false)
        }
    }

    override fun request() {
        val current = resolveState(requesting = false)
        if (current.granted) {
            _state.value = current
            return
        }
        if (current.canRequest) {
            preferences.edit { putBoolean(PERMISSION_REQUESTED_KEY, true) }
            _state.value = resolveState(requesting = true)
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        }
    }

    override fun close() {
        activity.lifecycle.removeObserver(this)
    }

    private fun resolveState(requesting: Boolean): LocalNetworkAccessState {
        val required = Build.VERSION.SDK_INT >= 37
        val granted = !required || ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
        val previouslyRequested = preferences.getBoolean(PERMISSION_REQUESTED_KEY, false)
        val canShowRationale = required && activity.shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        return resolveLocalNetworkAccess(
            required = required,
            granted = granted,
            requesting = requesting,
            previouslyRequested = previouslyRequested,
            canShowRationale = canShowRationale,
        )
    }
}

internal fun resolveLocalNetworkAccess(
    required: Boolean,
    granted: Boolean,
    requesting: Boolean,
    previouslyRequested: Boolean,
    canShowRationale: Boolean,
): LocalNetworkAccessState = when {
    !required -> LocalNetworkAccessState(LocalNetworkAccessPhase.NOT_REQUIRED)
    granted -> LocalNetworkAccessState(LocalNetworkAccessPhase.GRANTED)
    requesting -> LocalNetworkAccessState(
        phase = LocalNetworkAccessPhase.REQUESTING,
        canRequest = false,
        message = "正在请求 Android 局域网权限…",
    )
    !previouslyRequested -> LocalNetworkAccessState(
        phase = LocalNetworkAccessPhase.REQUIRED,
        canRequest = true,
        message = "Android 17 默认阻止局域网访问，需要授权后才能自动发现 Control。",
    )
    canShowRationale -> LocalNetworkAccessState(
        phase = LocalNetworkAccessPhase.DENIED,
        canRequest = true,
        message = "局域网权限已拒绝；可了解用途后再次授权，或继续手动输入 Control 地址。",
    )
    else -> LocalNetworkAccessState(
        phase = LocalNetworkAccessPhase.DENIED,
        canRequest = false,
        message = "局域网权限已关闭；请在系统设置中允许“附近的设备”，或继续手动输入 Control 地址。",
    )
}
