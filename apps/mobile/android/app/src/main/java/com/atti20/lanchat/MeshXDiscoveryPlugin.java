package com.atti20.lanchat;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers MeshX nodes through Android's DNS-SD implementation. The plugin
 * only returns mDNS-advertised origins; the web layer must still verify every
 * candidate with the public node handshake before it can be selected.
 */
@CapacitorPlugin(name = "MeshXDiscovery")
public class MeshXDiscoveryPlugin extends Plugin {
    private static final String TAG = "MeshXDiscovery";
    private static final String SERVICE_TYPE = "_lanchat._tcp.";
    private static final long SCAN_WINDOW_MS = 2_500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @PluginMethod
    public void scan(PluginCall call) {
        NsdManager nsdManager = (NsdManager) getContext()
                .getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            call.reject("当前设备不支持局域网 DNS-SD 扫描");
            return;
        }

        ScanSession session = new ScanSession(call, nsdManager, acquireMulticastLock());
        try {
            Log.i(TAG, "Starting DNS-SD scan for " + SERVICE_TYPE);
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, session);
            mainHandler.postDelayed(session.finishTask, SCAN_WINDOW_MS);
        } catch (RuntimeException exception) {
            session.releaseMulticastLock();
            Log.w(TAG, "Unable to start DNS-SD scan", exception);
            call.reject("无法开始局域网节点扫描", exception);
        }
    }

    private WifiManager.MulticastLock acquireMulticastLock() {
        WifiManager wifiManager = (WifiManager) getContext()
                .getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return null;

        WifiManager.MulticastLock lock = wifiManager.createMulticastLock("meshx-mdns-discovery");
        lock.setReferenceCounted(false);
        try {
            lock.acquire();
            return lock;
        } catch (RuntimeException ignored) {
            // Discovery may still work on Ethernet or on devices that do not
            // require a multicast lock, so do not turn this into a hard error.
            return null;
        }
    }

    private final class ScanSession implements NsdManager.DiscoveryListener {
        private final PluginCall call;
        private final NsdManager nsdManager;
        private final WifiManager.MulticastLock multicastLock;
        private final Set<String> origins = new LinkedHashSet<>();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final Runnable finishTask = this::finish;

        private ScanSession(
                PluginCall call,
                NsdManager nsdManager,
                WifiManager.MulticastLock multicastLock
        ) {
            this.call = call;
            this.nsdManager = nsdManager;
            this.multicastLock = multicastLock;
        }

        @Override
        public void onDiscoveryStarted(String registrationType) {
            Log.i(TAG, "DNS-SD scan started: " + registrationType);
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            if (!SERVICE_TYPE.equalsIgnoreCase(serviceInfo.getServiceType())) return;
            Log.i(TAG, "Found advertised service: " + serviceInfo.getServiceName());
            try {
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                        Log.w(TAG, "Could not resolve DNS-SD service (" + errorCode + ")");
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        String origin = originFor(resolved);
                        if (origin != null && !finished.get()) {
                            origins.add(origin);
                            Log.i(TAG, "Resolved MeshX node origin: " + origin);
                        }
                    }
                });
            } catch (RuntimeException ignored) {
                Log.w(TAG, "DNS-SD service resolution was rejected", ignored);
            }
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            // The handshake decides whether a candidate is still reachable.
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            finish();
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            finishWithError("局域网节点扫描启动失败（" + errorCode + "）");
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            finish();
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) return;
            mainHandler.removeCallbacks(finishTask);
            try {
                nsdManager.stopServiceDiscovery(this);
            } catch (RuntimeException ignored) {
                // Discovery may already have stopped because Wi-Fi changed.
            }
            releaseMulticastLock();
            Log.i(TAG, "DNS-SD scan finished with " + origins.size() + " candidate(s)");
            JSObject result = new JSObject();
            result.put("origins", new JSArray(origins));
            call.resolve(result);
        }

        private void finishWithError(String message) {
            if (!finished.compareAndSet(false, true)) return;
            mainHandler.removeCallbacks(finishTask);
            releaseMulticastLock();
            Log.w(TAG, message);
            call.reject(message);
        }

        private void releaseMulticastLock() {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        }
    }

    private String originFor(NsdServiceInfo serviceInfo) {
        InetAddress host = serviceInfo.getHost();
        int port = serviceInfo.getPort();
        if (host == null || port < 1 || port > 65_535) return null;

        String hostAddress = advertisedHost(serviceInfo);
        if (hostAddress == null) hostAddress = host.getHostAddress();
        if (hostAddress == null || hostAddress.isBlank()) return null;
        if (hostAddress.contains(":")) hostAddress = "[" + hostAddress + "]";

        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        byte[] secureAttribute = attributes.get("secure");
        String secure = secureAttribute == null
                ? ""
                : new String(secureAttribute, StandardCharsets.UTF_8);
        String scheme = "true".equalsIgnoreCase(secure == null ? "" : secure.trim())
                ? "https" : "http";
        return String.format(Locale.ROOT, "%s://%s:%d", scheme, hostAddress, port);
    }

    private String advertisedHost(NsdServiceInfo serviceInfo) {
        byte[] value = serviceInfo.getAttributes().get("advertisedHost");
        if (value == null) return null;
        String host = new String(value, StandardCharsets.UTF_8).trim();
        return host.matches("^[A-Za-z0-9.-]{1,253}$")
                && !host.startsWith(".")
                && !host.contains("..")
                ? host
                : null;
    }
}
