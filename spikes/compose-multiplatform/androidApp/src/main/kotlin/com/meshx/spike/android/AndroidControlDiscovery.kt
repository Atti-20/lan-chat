package com.meshx.spike.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.meshx.spike.ControlDiscovery
import com.meshx.spike.DiscoveryPhase
import com.meshx.spike.DiscoveryState
import com.meshx.spike.controlFromDnsSd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets

private const val CONTROL_SERVICE_TYPE = "_meshx-control._tcp."

class AndroidControlDiscovery(
    context: Context,
    private val hasLocalNetworkAccess: () -> Boolean,
) : ControlDiscovery {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val _state = MutableStateFlow(DiscoveryState())
    override val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    private var started = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolveLock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            if (hasLocalNetworkAccess()) {
                update { copy(phase = DiscoveryPhase.RUNNING, message = null) }
            } else {
                stop()
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.startsWith("_meshx-control._tcp")) enqueueResolution(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            update {
                copy(controls = controls.filterNot { it.serviceName == serviceInfo.serviceName })
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            update { copy(phase = DiscoveryPhase.STOPPED) }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            fail("Android mDNS 启动失败（$errorCode）")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            fail("Android mDNS 停止失败（$errorCode）")
        }
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (started) return
            if (!hasLocalNetworkAccess()) {
                _state.value = DiscoveryState(
                    phase = DiscoveryPhase.STOPPED,
                    message = "局域网权限未授予，mDNS 未启动",
                )
                return
            }
            started = true
            _state.value = DiscoveryState(phase = DiscoveryPhase.STARTING)
            try {
                multicastLock = wifiManager.createMulticastLock("meshx-compose-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                nsdManager.discoverServices(CONTROL_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (exception: Exception) {
                started = false
                releaseMulticastLock()
                _state.value = DiscoveryState(
                    phase = DiscoveryPhase.FAILED,
                    message = exception.message ?: "Android mDNS 启动失败",
                )
            }
        }
    }

    override fun stop() {
        val shouldStop = synchronized(lifecycleLock) {
            if (!started) return@synchronized false
            started = false
            synchronized(resolveLock) {
                resolveQueue.clear()
            }
            releaseMulticastLock()
            true
        }
        if (shouldStop) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            update { copy(phase = DiscoveryPhase.STOPPED, controls = emptyList()) }
        }
    }

    @Suppress("DEPRECATION")
    private fun enqueueResolution(serviceInfo: NsdServiceInfo) {
        val shouldResolve = synchronized(resolveLock) {
            resolveQueue.addLast(serviceInfo)
            if (resolving) false else {
                resolving = true
                true
            }
        }
        if (shouldResolve) resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveLock) {
            if (resolveQueue.isEmpty()) {
                resolving = false
                null
            } else {
                resolveQueue.removeFirst()
            }
        } ?: return

        try {
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    remember(serviceInfo)
                    resolveNext()
                }
            })
        } catch (_: Exception) {
            resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun remember(info: NsdServiceInfo) {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        val usableAddresses = addresses
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress || it.isLinkLocalAddress }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull(InetAddress::getHostAddress)
        val properties = info.attributes.mapValues { (_, value) ->
            value.toString(StandardCharsets.UTF_8)
        }
        controlFromDnsSd(info.serviceName, info.port, usableAddresses, properties)
            .onSuccess { control ->
                update {
                    val next = controls.filterNot { it.controlId == control.controlId && it.origin == control.origin } + control
                    copy(controls = next.sortedBy { it.name.lowercase() }, message = null)
                }
            }
    }

    private fun fail(message: String) {
        synchronized(lifecycleLock) {
            started = false
            releaseMulticastLock()
        }
        update { copy(phase = DiscoveryPhase.FAILED, message = message) }
    }

    private fun update(transform: DiscoveryState.() -> DiscoveryState) {
        synchronized(_state) {
            _state.value = _state.value.transform()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }
}
