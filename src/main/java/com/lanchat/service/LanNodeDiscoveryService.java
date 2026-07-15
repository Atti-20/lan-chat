package com.lanchat.service;

import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.dto.DiscoveredNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advertises this node and discovers peers using mDNS/DNS-SD.
 *
 * <p>Discovery failures never prevent core chat startup. This is important on
 * segmented networks, CI hosts and Docker bridge networks where multicast may
 * be unavailable.</p>
 */
@Service
public class LanNodeDiscoveryService implements ApplicationRunner {

    public static final String SERVICE_TYPE = "_lanchat._tcp.local.";
    private static final Logger log = LoggerFactory.getLogger(LanNodeDiscoveryService.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final LanChatNodeProperties nodeProperties;
    private final List<JmDNS> responders = new CopyOnWriteArrayList<>();
    private final Map<String, DiscoveredNode> discovered = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lanchat-mdns-discovery");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${lanchat.discovery.enabled:false}")
    private boolean enabled;

    @Value("${lanchat.discovery.interface-address:}")
    private String configuredInterfaceAddress;

    public LanNodeDiscoveryService(LanChatNodeProperties nodeProperties) {
        this.nodeProperties = nodeProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("局域网节点 mDNS 自动发现已禁用");
            return;
        }
        executor.submit(this::startSafely);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<DiscoveredNode> listDiscoveredNodes() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        discovered.entrySet().removeIf(entry -> !entry.getValue().current()
                && entry.getValue().lastSeenAt().isBefore(cutoff));

        Map<String, DiscoveredNode> byNode = new LinkedHashMap<>();
        discovered.values().stream()
                .sorted(Comparator.comparing(DiscoveredNode::current).reversed()
                        .thenComparing(DiscoveredNode::nodeName, String.CASE_INSENSITIVE_ORDER))
                .forEach(node -> byNode.merge(node.nodeId(), node, this::newest));
        return List.copyOf(byNode.values());
    }

    private void startSafely() {
        try {
            List<InetAddress> addresses = discoveryAddresses();
            if (addresses.isEmpty()) {
                log.warn("mDNS 自动发现未找到可用的 IPv4 局域网接口");
                return;
            }
            for (InetAddress address : addresses) {
                try {
                    startOnAddress(address);
                } catch (Exception exception) {
                    log.warn("mDNS 无法在接口 {} 启动: {}", address.getHostAddress(), exception.getMessage());
                }
            }
            if (responders.isEmpty()) {
                log.warn("mDNS 自动发现未能在任何网络接口启动");
            } else {
                log.info("mDNS 自动发现已在 {} 个网络接口启动，服务类型 {}", responders.size(), SERVICE_TYPE);
            }
        } catch (Exception exception) {
            log.warn("mDNS 自动发现启动失败，聊天核心功能继续可用: {}", exception.getMessage());
        }
    }

    private void startOnAddress(InetAddress address) throws Exception {
        String nodeId = nodeProperties.resolvedId();
        String serviceName = serviceName(nodeProperties.getName(), nodeId);
        if (nodeProperties.getAdvertisedPort() < 1 || nodeProperties.getAdvertisedPort() > 65_535) {
            throw new IllegalArgumentException("公布端口必须在 1-65535 之间");
        }
        // DNS host labels cannot contain the spaces or Unicode that are valid in
        // a human-readable DNS-SD service instance name.
        JmDNS responder = JmDNS.create(address, nodeId + ".local.");
        try {
            responder.addServiceListener(SERVICE_TYPE, new NodeServiceListener(responder));

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("nodeId", nodeId);
            properties.put("nodeName", safeText(nodeProperties.getName(), "LanChat Node", 80));
            properties.put("organization", safeText(nodeProperties.getOrganizationName(), "Local Organization", 80));
            properties.put("version", safeText(nodeProperties.getVersion(), "unknown", 30));
            properties.put("mode", nodeProperties.normalizedMode());
            properties.put("secure", Boolean.toString(nodeProperties.isSecure()));
            properties.put("protocol", "1");
            properties.put("path", "/app/");

            ServiceInfo serviceInfo = ServiceInfo.create(
                    SERVICE_TYPE,
                    serviceName,
                    nodeProperties.getAdvertisedPort(),
                    0,
                    0,
                    properties
            );
            responder.registerService(serviceInfo);
            responders.add(responder);
            remember(serviceInfo, address, true);
        } catch (Exception exception) {
            responder.close();
            throw exception;
        }
    }

    private List<InetAddress> discoveryAddresses() throws Exception {
        if (StringUtils.hasText(configuredInterfaceAddress)) {
            InetAddress address = InetAddress.getByName(configuredInterfaceAddress.trim());
            return address instanceof Inet4Address && !address.isLoopbackAddress()
                    ? List.of(address) : List.of();
        }

        List<InetAddress> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return addresses;
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!usableNetworkInterface(network)) continue;
            Enumeration<InetAddress> candidates = network.getInetAddresses();
            while (candidates.hasMoreElements()) {
                InetAddress candidate = candidates.nextElement();
                if (candidate instanceof Inet4Address
                        && !candidate.isLoopbackAddress()
                        && !candidate.isAnyLocalAddress()
                        && !candidate.isMulticastAddress()) {
                    addresses.add(candidate);
                }
            }
        }
        return addresses;
    }

    private boolean usableNetworkInterface(NetworkInterface network) {
        try {
            return network.isUp() && !network.isLoopback() && network.supportsMulticast();
        } catch (SocketException exception) {
            return false;
        }
    }

    private void remember(ServiceInfo info, InetAddress fallbackAddress, boolean current) {
        String nodeId = safeNodeId(info.getPropertyString("nodeId"));
        if (nodeId == null) return;
        InetAddress address = firstIpv4(info, fallbackAddress);
        if (address == null) return;

        boolean secure = Boolean.parseBoolean(info.getPropertyString("secure"));
        String host = current && StringUtils.hasText(nodeProperties.getAdvertisedHost())
                ? nodeProperties.getAdvertisedHost().trim()
                : address.getHostAddress();
        if (!safeHost(host)) host = address.getHostAddress();
        int port = info.getPort() > 0 ? info.getPort() : nodeProperties.getAdvertisedPort();
        String appUrl = (secure ? "https" : "http") + "://" + host + ":" + port + "/app/";

        DiscoveredNode node = new DiscoveredNode(
                nodeId,
                safeText(info.getPropertyString("nodeName"), info.getName(), 80),
                safeText(info.getPropertyString("organization"), "Local Organization", 80),
                safeText(info.getPropertyString("version"), "unknown", 30),
                safeMode(info.getPropertyString("mode")),
                appUrl,
                secure,
                current || nodeId.equals(nodeProperties.resolvedId()),
                Instant.now()
        );
        discovered.put(nodeId + "@" + appUrl, node);
    }

    private InetAddress firstIpv4(ServiceInfo info, InetAddress fallback) {
        for (Inet4Address address : info.getInet4Addresses()) return address;
        return fallback instanceof Inet4Address ? fallback : null;
    }

    private void remove(ServiceInfo info) {
        String nodeId = safeNodeId(info.getPropertyString("nodeId"));
        if (nodeId == null || nodeId.equals(nodeProperties.resolvedId())) return;
        discovered.entrySet().removeIf(entry -> entry.getValue().nodeId().equals(nodeId));
    }

    private DiscoveredNode newest(DiscoveredNode first, DiscoveredNode second) {
        if (first.current() != second.current()) return first.current() ? first : second;
        return first.lastSeenAt().isAfter(second.lastSeenAt()) ? first : second;
    }

    private String serviceName(String name, String nodeId) {
        String base = safeText(name, "LanChat", 40)
                .replaceAll("[\\r\\n.]", "-")
                .trim();
        return base + "-" + nodeId.substring(Math.max(0, nodeId.length() - 6));
    }

    private String safeNodeId(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-z0-9][a-z0-9_-]{2,63}$") ? normalized : null;
    }

    private String safeMode(String value) {
        if (!StringUtils.hasText(value)) return "LAN_FIRST";
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Set.of("LOCAL_INDEPENDENT", "LAN_FIRST", "HYBRID").contains(normalized)
                ? normalized : "LAN_FIRST";
    }

    private boolean safeHost(String value) {
        if (!StringUtils.hasText(value) || value.length() > 253) return false;
        return value.matches("^[A-Za-z0-9._-]+$") && !value.contains("..") && !value.startsWith(".");
    }

    private String safeText(String value, String fallback, int maximumLength) {
        if (!StringUtils.hasText(value)) return fallback;
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return sanitized.substring(0, Math.min(maximumLength, sanitized.length()));
    }

    @PreDestroy
    public void shutdown() {
        for (JmDNS responder : responders) {
            try {
                responder.unregisterAllServices();
                responder.close();
            } catch (Exception exception) {
                log.debug("关闭 mDNS responder 失败: {}", exception.getMessage());
            }
        }
        responders.clear();
        executor.shutdownNow();
    }

    private final class NodeServiceListener implements ServiceListener {
        private final JmDNS responder;

        private NodeServiceListener(JmDNS responder) {
            this.responder = responder;
        }

        @Override
        public void serviceAdded(ServiceEvent event) {
            responder.requestServiceInfo(event.getType(), event.getName(), true);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            remove(event.getInfo());
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            remember(event.getInfo(), null, false);
        }
    }
}
