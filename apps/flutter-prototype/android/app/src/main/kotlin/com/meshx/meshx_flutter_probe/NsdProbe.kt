package com.meshx.meshx_flutter_probe

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

internal class AndroidDiscoveryAdapter(private val activity: Activity, messenger: BinaryMessenger) {
    private val method = MethodChannel(messenger, "com.meshx.prototype/discovery")
    private var sink: EventChannel.EventSink? = null
    private var probe: NsdProbe? = null
    private var pending: MethodChannel.Result? = null
    private var pendingScan: Map<String, Any?>? = null
    private var session = 0L
    private var disposed = false
    private val prefs = activity.getSharedPreferences("meshx-capabilities", Context.MODE_PRIVATE)
    init {
        EventChannel(messenger, "com.meshx.prototype/discovery/events").setStreamHandler(object: EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) { sink = events }
            override fun onCancel(arguments: Any?) { sink = null; stop("CANCELLED", "listenerDetached") }
        })
        method.setMethodCallHandler { call, result ->
            if (disposed) { result.success(mapOf("status" to "CANCELLED")); return@setMethodCallHandler }
            val args = (call.arguments as? Map<*, *>) ?: emptyMap<Any, Any>()
            when (call.method) {
                "start", "prepareConnection" -> {
                    if (pending != null) { result.success(mapOf("status" to "TEMPORARILY_UNAVAILABLE", "reason" to "permissionInProgress")); return@setMethodCallHandler }
                    val scan = if (call.method == "start") mapOf("session" to args["session"], "windowMs" to args["windowMs"]) else null
                    val permission = permissionStatus()
                    if (permission != "AVAILABLE") {
                        if (args["requestPermission"] != true || permission == "PERMISSION_PERMANENTLY_DENIED") {
                            result.success(mapOf("status" to permission)); return@setMethodCallHandler
                        }
                        pending = result; pendingScan = scan
                        prefs.edit().putBoolean("lanAsked", true).apply()
                        activity.requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 7301)
                    } else if (scan != null) start(scan, result) else result.success(mapOf("status" to "AVAILABLE"))
                }
                "stop" -> {
                    val expected = (args["session"] as? Number)?.toLong()
                    val pendingId = (pendingScan?.get("session") as? Number)?.toLong()
                    if (expected == null || expected == session) stop(if (args["cancelled"] == true) "CANCELLED" else "SUCCESS", "stopped")
                    if (pending != null && (expected == null || expected == pendingId)) {
                        pending?.success(mapOf("status" to "CANCELLED")); pending = null; pendingScan = null
                    }
                    result.success(mapOf("status" to "SUCCESS"))
                }
                else -> result.notImplemented()
            }
        }
    }
    private fun permissionStatus(): String {
        if (Build.VERSION.SDK_INT < 37 || activity.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED) return "AVAILABLE"
        if (!prefs.getBoolean("lanAsked", false)) return "PERMISSION_REQUIRED"
        return if (activity.shouldShowRequestPermissionRationale("android.permission.ACCESS_LOCAL_NETWORK")) "PERMISSION_DENIED" else "PERMISSION_PERMANENTLY_DENIED"
    }
    fun permissionResult(code: Int, grants: IntArray): Boolean {
        if (code != 7301) return false
        val result = pending ?: return true
        val scan = pendingScan; pending = null; pendingScan = null
        if (grants.firstOrNull() != PackageManager.PERMISSION_GRANTED) result.success(mapOf("status" to permissionStatus()))
        else if (scan != null) start(scan, result) else result.success(mapOf("status" to "AVAILABLE"))
        return true
    }
    private fun start(args: Map<String, Any?>, result: MethodChannel.Result) {
        stop("CANCELLED", "replaced")
        val manager = activity.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) { result.success(mapOf("status" to "UNSUPPORTED")); return }
        session = (args["session"] as? Number)?.toLong() ?: 0L
        val owner = session
        val window = ((args["windowMs"] as? Number)?.toLong() ?: 8000).coerceIn(1000, 30000)
        probe = NsdProbe(manager, window) { nodes, status, reason, complete ->
            if (!disposed && session == owner) {
                sink?.success(mapOf("session" to owner, "nodes" to nodes, "status" to status, "reason" to reason, "complete" to complete))
                if (complete) probe = null
            }
        }
        result.success(mapOf("status" to "AVAILABLE"))
        probe?.start()
    }
    fun stop(status: String = "TEMPORARILY_UNAVAILABLE", reason: String = "background") {
        val current = probe; probe = null; current?.stop(status, reason)
    }
    fun dispose() {
        stop("CANCELLED", "disposed")
        pending?.success(mapOf("status" to "CANCELLED")); pending = null; pendingScan = null
        disposed = true; sink = null; method.setMethodCallHandler(null)
    }
}

/** Both existing DNS-SD types; full snapshots, bounded resolution, lost-event ownership. */
internal class NsdProbe(private val manager: NsdManager, private val window: Long,
    private val emit: (List<Map<String, String>>, String, String, Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val present = linkedMapOf<String, NsdServiceInfo>()
    private val revisions = mutableMapOf<String, Int>()
    private val nodes = linkedMapOf<String, Map<String, String>>()
    private val queue = ArrayDeque<Pair<String, Int>>()
    private var resolving = false
    private var finished = false
    private var ready = 0
    private var failures = 0
    private var resolutionFailures = 0
    private var scanStatus = "SUCCESS"
    private fun key(info: NsdServiceInfo) = info.serviceType + "/" + info.serviceName
    fun start() {
        for (type in listOf("_meshx-control._tcp.", "_lanchat._tcp.")) {
            val listener = object: NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { handler.post { if (!finished) { ready++; update() } } }
                override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                    if (finished || present.size >= 128 || !info.serviceType.equals(type, true)) return@post
                    val key = key(info); present[key] = info
                    val revision = (revisions[key] ?: 0) + 1; revisions[key] = revision
                    queue.removeAll { it.first == key }; queue.add(key to revision); resolveNext()
                } }
                override fun onServiceLost(info: NsdServiceInfo) { handler.post {
                    if (!finished) { val key = key(info); present.remove(key); revisions[key] = (revisions[key] ?: 0) + 1; nodes.remove(key); update() }
                } }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, code: Int) { handler.post {
                    if (!finished) { failures++; scanStatus = "TEMPORARILY_UNAVAILABLE"; if (failures == 2) stop(scanStatus, "discoveryStartFailed") }
                } }
                override fun onStopDiscoveryFailed(serviceType: String, code: Int) { scanStatus = "FAILED" }
            }
            listeners.add(listener)
            try { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (_: SecurityException) { stop("PERMISSION_DENIED", "localNetworkDenied"); return }
            catch (_: RuntimeException) { failures++; scanStatus = "TEMPORARILY_UNAVAILABLE" }
        }
        handler.postDelayed({ if (!finished) {
            val status = if (ready == 0) "TEMPORARILY_UNAVAILABLE" else if (nodes.isEmpty() && resolutionFailures > 0) "FAILED" else scanStatus
            stop(status, if (resolutionFailures > 0) "resolutionFailed" else "windowComplete")
        } }, window)
    }
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (finished || resolving || queue.isEmpty()) return
        val (key, revision) = queue.removeFirst()
        val info = present[key]
        if (info == null || revisions[key] != revision) { resolveNext(); return }
        resolving = true
        try {
            manager.resolveService(info, object: NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post {
                    if (!finished) { resolutionFailures++; resolving = false; resolveNext() }
                } }
                override fun onServiceResolved(service: NsdServiceInfo) { handler.post {
                    if (!finished) {
                        if (present.containsKey(key) && revisions[key] == revision) {
                            record(service)?.let { nodes[key] = it }; update()
                        }
                        resolving = false; resolveNext()
                    }
                } }
            })
        } catch (_: SecurityException) { stop("PERMISSION_DENIED", "localNetworkDenied") }
        catch (_: RuntimeException) { resolutionFailures++; resolving = false; handler.post { resolveNext() } }
    }
    private fun update() { if (!finished) emit(nodes.values.toList(), scanStatus, "snapshot", false) }
    fun stop(status: String, reason: String) {
        if (finished) return
        finished = true; handler.removeCallbacksAndMessages(null)
        var outcome = status
        for (listener in listeners) {
            try { manager.stopServiceDiscovery(listener) }
            catch (_: IllegalArgumentException) { /* A failed start has no registered listener. */ }
            catch (_: RuntimeException) { outcome = "FAILED" }
        }
        listeners.clear(); queue.clear(); present.clear()
        emit(if (reason == "windowComplete") nodes.values.toList() else emptyList(), outcome, reason, true)
        nodes.clear()
    }
    @Suppress("DEPRECATION")
    private fun record(info: NsdServiceInfo): Map<String, String>? {
        if (info.port !in 1..65535) return null
        fun txt(key: String) = info.attributes[key]?.toString(Charsets.UTF_8)?.trim()
        val advertised = txt("advertisedHost")?.takeIf { it.matches(Regex("^[A-Za-z0-9.-]{1,253}$")) && !it.startsWith(".") && !it.contains("..") }
        val address = advertised ?: info.host?.takeUnless { it.isLinkLocalAddress }?.hostAddress?.substringBefore("%") ?: return null
        val host = if (address.contains(":")) "[$address]" else address
        val origin = (if (txt("secure").equals("true", true)) "https" else "http") + "://" + host + ":" + info.port
        return mapOf("id" to (txt("nodeId") ?: txt("controlId") ?: origin).take(160),
            "name" to (txt("nodeName") ?: txt("controlName") ?: "MeshX 节点").take(80), "origin" to origin)
    }
}
