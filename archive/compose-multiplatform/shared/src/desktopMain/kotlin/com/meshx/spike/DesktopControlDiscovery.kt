package com.meshx.spike

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

private const val CONTROL_SERVICE_TYPE = "_meshx-control._tcp.local."

class DesktopControlDiscovery : ControlDiscovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DiscoveryState())
    override val state: StateFlow<DiscoveryState> = _state.asStateFlow()
    private val lifecycleLock = Any()
    private val responders = mutableListOf<JmDNS>()
    private var started = false

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            update {
                copy(controls = controls.filterNot { it.serviceName == event.name })
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val properties = Collections.list(info.propertyNames).associateWith { key ->
                info.getPropertyString(key).orEmpty()
            }
            val addresses = (info.inet4Addresses.asList() + info.inet6Addresses.asList())
                .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress || it.isLinkLocalAddress }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .mapNotNull(InetAddress::getHostAddress)
            controlFromDnsSd(info.name, info.port, addresses, properties)
                .onSuccess { control ->
                    update {
                        val next = controls.filterNot { it.controlId == control.controlId && it.origin == control.origin } + control
                        copy(controls = next.sortedBy { it.name.lowercase() }, message = null)
                    }
                }
        }
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (started) return
            started = true
            _state.value = DiscoveryState(phase = DiscoveryPhase.STARTING)
        }
        scope.launch {
            val created = runCatching {
                localIpv4Addresses().map { address ->
                    JmDNS.create(address, "meshx-compose-${address.hostAddress.replace('.', '-')}.local.")
                        .also { it.addServiceListener(CONTROL_SERVICE_TYPE, listener) }
                }
            }.getOrElse { exception ->
                update {
                    copy(
                        phase = DiscoveryPhase.FAILED,
                        message = exception.message ?: "Desktop mDNS 启动失败",
                    )
                }
                return@launch
            }

            synchronized(lifecycleLock) {
                if (!started) {
                    created.forEach { runCatching(it::close) }
                    return@launch
                }
                responders += created
            }
            update {
                if (created.isEmpty()) {
                    copy(phase = DiscoveryPhase.FAILED, message = "没有可用的 IPv4 局域网接口")
                } else {
                    copy(phase = DiscoveryPhase.RUNNING, message = null)
                }
            }
        }
    }

    override fun stop() {
        val toClose = synchronized(lifecycleLock) {
            if (!started) return
            started = false
            responders.toList().also { responders.clear() }
        }
        toClose.forEach { responder ->
            runCatching { responder.removeServiceListener(CONTROL_SERVICE_TYPE, listener) }
            runCatching(responder::close)
        }
        scope.cancel()
        update { copy(phase = DiscoveryPhase.STOPPED) }
    }

    private fun update(transform: DiscoveryState.() -> DiscoveryState) {
        synchronized(_state) {
            _state.value = _state.value.transform()
        }
    }

    private fun localIpv4Addresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!runCatching { network.isUp }.getOrDefault(false) || network.isLoopback || network.isVirtual) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address &&
                    !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isMulticastAddress
                ) {
                    result += address
                }
            }
        }
        return result.distinctBy(InetAddress::getHostAddress)
    }
}
