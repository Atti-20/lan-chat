import Capacitor
import Foundation
import Network

/**
 * Discovers MeshX nodes through Bonjour/DNS-SD. The plugin only returns
 * mDNS-advertised origins; the web layer must still verify every candidate
 * with the public node handshake before it can be selected.
 *
 * 对应 Android 版 MeshXDiscoveryPlugin（NsdManager 实现）的 iOS 移植：
 * - NWBrowser(bonjourWithTXTRecord) 负责浏览服务并读取 TXT 记录；
 * - Network.framework 不直接暴露解析后的 IP，这里对每个服务端点发起一次
 *   短 TCP 连接并读取 currentPath.remoteEndpoint 取得具体地址，
 *   等价于 NsdManager.resolveService 的解析步骤（NetServiceBrowser 虽能
 *   直接给地址但已废弃，故不采用）；
 * - Android 的 WifiManager.MulticastLock 在 iOS 没有对应物：mDNS 由系统
 *   mDNSResponder 守护进程代理，无需持锁；
 * - 依赖 Info.plist 中的 NSLocalNetworkUsageDescription 与 NSBonjourServices
 *   （含 _lanchat._tcp），该配置由平台脚手架工作流负责。
 */
@objc(MeshXDiscoveryPlugin)
public class MeshXDiscoveryPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MeshXDiscoveryPlugin"
    public let jsName = "MeshXDiscovery"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "scan", returnType: CAPPluginReturnPromise)
    ]

    private static let tag = "MeshXDiscovery"
    // Network.framework 的服务类型串不带末尾点；与 Android 侧的
    // "_lanchat._tcp." 指向同一 DNS-SD 服务类型。
    private static let serviceType = "_lanchat._tcp"
    // 与 Android 的 SCAN_WINDOW_MS = 2_500 对齐：限时扫描后一次性返回。
    private static let scanWindow: DispatchTimeInterval = .milliseconds(2_500)

    private let sessionsLock = NSLock()
    private var sessions: [UUID: MeshXDiscoveryScanSession] = [:]

    @objc func scan(_ call: CAPPluginCall) {
        let sessionId = UUID()
        let session = MeshXDiscoveryScanSession(call: call) { [weak self] in
            guard let self = self else { return }
            self.sessionsLock.lock()
            self.sessions.removeValue(forKey: sessionId)
            self.sessionsLock.unlock()
        }
        sessionsLock.lock()
        sessions[sessionId] = session
        sessionsLock.unlock()
        CAPLog.print(Self.tag, "Starting DNS-SD scan for \(Self.serviceType)")
        session.start(serviceType: Self.serviceType, window: Self.scanWindow)
        // NWBrowser.start 不会抛异常，Android 侧 discoverServices 抛出
        // RuntimeException 时的“无法开始局域网节点扫描”分支在 iOS 没有
        // 对应场景；启动类故障统一经 .failed 状态给出。
    }
}

/**
 * 单次扫描会话。所有可变状态都限定在私有串行队列上
 * （browser、connection、定时器均在该队列回调），
 * 与 Android 侧 AtomicBoolean + 主线程 Handler 的“只完成一次”语义一致。
 */
private final class MeshXDiscoveryScanSession {
    private static let tag = "MeshXDiscovery"

    private let call: CAPPluginCall
    private let onFinish: () -> Void
    private let queue = DispatchQueue(label: "com.atti20.lanchat.meshx-discovery")

    private var browser: NWBrowser?
    private var connections: [NWConnection] = []
    private var attemptedEndpoints = Set<NWEndpoint>()
    // LinkedHashSet 语义：去重且保留发现顺序。
    private var origins: [String] = []
    private var seenOrigins = Set<String>()
    private var finished = false

    init(call: CAPPluginCall, onFinish: @escaping () -> Void) {
        self.call = call
        self.onFinish = onFinish
    }

    func start(serviceType: String, window: DispatchTimeInterval) {
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: serviceType, domain: nil),
            using: NWParameters()
        )
        self.browser = browser
        browser.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                CAPLog.print(Self.tag, "DNS-SD scan started: \(serviceType)")
            case .failed(let error):
                // 对应 Android 的 onStartDiscoveryFailed：浏览器进入
                // failed 后不再产生结果，携带数字错误码返回同款文案。
                self?.finishWithError("局域网节点扫描启动失败（\(Self.numericCode(of: error))）")
            case .waiting:
                // 无可用网络时 Android 侧只是扫不到结果；
                // 这里同样静待扫描窗口结束并返回已收集的列表。
                break
            default:
                break
            }
        }
        browser.browseResultsChangedHandler = { [weak self] _, changes in
            for change in changes {
                switch change {
                case .added(let result):
                    self?.resolveResult(result)
                case .changed(_, let result, _):
                    self?.resolveResult(result)
                default:
                    break
                }
            }
        }
        browser.start(queue: queue)
        queue.asyncAfter(deadline: .now() + window) { [weak self] in
            self?.finish()
        }
    }

    private func resolveResult(_ result: NWBrowser.Result) {
        if finished { return }
        // NWBrowser 只返回所浏览类型的服务，等价于 Java 侧的 serviceType 校验。
        guard case .service(let name, _, _, _) = result.endpoint else { return }
        if attemptedEndpoints.contains(result.endpoint) { return }
        attemptedEndpoints.insert(result.endpoint)
        CAPLog.print(Self.tag, "Found advertised service: \(name)")

        var txtRecord: NWTXTRecord?
        if case .bonjour(let txt) = result.metadata {
            txtRecord = txt
        }

        // 通过一次立即取消的短连接取得服务的具体地址与端口，
        // 等价于 NsdManager.resolveService；连接失败即忽略该候选
        // （对应 Android 的 onResolveFailed）。
        let connection = NWConnection(to: result.endpoint, using: .tcp)
        connections.append(connection)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self = self, let connection = connection else { return }
            switch state {
            case .ready:
                if let remote = connection.currentPath?.remoteEndpoint,
                   case .hostPort(let host, let port) = remote {
                    self.addOrigin(host: host, port: port, txt: txtRecord)
                }
                connection.cancel()
            case .failed(let error):
                CAPLog.print(Self.tag, "Could not resolve DNS-SD service (\(Self.numericCode(of: error)))")
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func addOrigin(host: NWEndpoint.Host, port: NWEndpoint.Port, txt: NWTXTRecord?) {
        if finished { return }
        guard let origin = Self.origin(host: host, port: port, txt: txt) else { return }
        if seenOrigins.insert(origin).inserted {
            origins.append(origin)
            CAPLog.print(Self.tag, "Resolved MeshX node origin: \(origin)")
        }
    }

    private func finish() {
        if finished { return }
        finished = true
        teardown()
        CAPLog.print(Self.tag, "DNS-SD scan finished with \(origins.count) candidate(s)")
        call.resolve(["origins": origins])
        onFinish()
    }

    private func finishWithError(_ message: String) {
        if finished { return }
        finished = true
        teardown()
        CAPLog.print(Self.tag, message)
        call.reject(message)
        onFinish()
    }

    private func teardown() {
        browser?.stateUpdateHandler = nil
        browser?.browseResultsChangedHandler = nil
        browser?.cancel()
        browser = nil
        for connection in connections {
            connection.cancel()
        }
        connections.removeAll()
    }

    // 镜像 Java originFor：advertisedHost 优先，否则用解析出的地址；
    // IPv6 直写需加方括号；TXT 的 secure=true 时用 https。
    private static func origin(host: NWEndpoint.Host, port: NWEndpoint.Port, txt: NWTXTRecord?) -> String? {
        // NWEndpoint.Port 为 UInt16，上限即 65535，对应 Java 的端口区间校验。
        let portNumber = Int(port.rawValue)
        guard portNumber >= 1 else { return nil }

        var hostAddress = advertisedHost(txt)
        if hostAddress == nil {
            hostAddress = literal(of: host)
        }
        guard var address = hostAddress,
              !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        if address.contains(":") {
            address = "[\(address)]"
        }

        let secure = (txt?.dictionary["secure"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let scheme = secure.caseInsensitiveCompare("true") == .orderedSame ? "https" : "http"
        return "\(scheme)://\(address):\(portNumber)"
    }

    // 镜像 Java advertisedHost：仅接受主机名字符集且长度受限的覆盖值。
    private static func advertisedHost(_ txt: NWTXTRecord?) -> String? {
        guard let value = txt?.dictionary["advertisedHost"] else { return nil }
        let host = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let valid = host.range(of: "^[A-Za-z0-9.-]{1,253}$", options: .regularExpression) != nil
            && !host.hasPrefix(".")
            && !host.contains("..")
        return valid ? host : nil
    }

    private static func literal(of host: NWEndpoint.Host) -> String? {
        switch host {
        case .ipv4(let address):
            return address.debugDescription
        case .ipv6(let address):
            // URL 层无法解析带 zone id 的 IPv6 直写（如 fe80::1%en0），
            // 去掉 zone 后再拼 origin，方括号由上层统一补齐。
            let text = address.debugDescription
            if let percent = text.firstIndex(of: "%") {
                return String(text[..<percent])
            }
            return text
        case .name(let name, _):
            return name
        @unknown default:
            return nil
        }
    }

    private static func numericCode(of error: NWError) -> Int32 {
        // 用普通 default 而非 @unknown default：新 SDK 持续追加错误枚举
        // （如 .wifiAware），显式列举会随 SDK 版本产生告警或编译错误。
        switch error {
        case .posix(let code):
            return code.rawValue
        case .dns(let code):
            return code
        case .tls(let status):
            return status
        default:
            return -1
        }
    }
}
