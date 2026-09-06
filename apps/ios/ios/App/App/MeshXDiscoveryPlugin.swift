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
 *   （含 _meshx-control._tcp 和兼容用 _lanchat._tcp）。
 */
@objc(MeshXDiscoveryPlugin)
public class MeshXDiscoveryPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MeshXDiscoveryPlugin"
    public let jsName = "MeshXDiscovery"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "scan", returnType: CAPPluginReturnPromise)
    ]

    private static let tag = "MeshXDiscovery"
    // Network.framework 的服务类型串不带末尾点。先扫描当前控制面服务，
    // 未找到候选时再回退到历史 LANChat 服务类型。
    private static let serviceTypes = ["_meshx-control._tcp", "_lanchat._tcp"]
    // 两个类型共享 Android 同等的 2.5 秒总扫描预算。
    private static let scanWindow: DispatchTimeInterval = .milliseconds(1_250)

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
        CAPLog.print(Self.tag, "Starting DNS-SD scan for \(Self.serviceTypes.joined(separator: ", "))")
        session.start(serviceTypes: Self.serviceTypes, window: Self.scanWindow)
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
    private var serviceTypes: [String] = []
    private var serviceIndex = 0
    private var scanGeneration = 0

    init(call: CAPPluginCall, onFinish: @escaping () -> Void) {
        self.call = call
        self.onFinish = onFinish
    }

    func start(serviceTypes: [String], window: DispatchTimeInterval) {
        self.serviceTypes = serviceTypes
        self.serviceIndex = 0
        self.scanWindow = window
        startCurrentService()
    }

    private var scanWindow: DispatchTimeInterval = .milliseconds(1_250)

    private func startCurrentService() {
        guard !finished, serviceIndex < serviceTypes.count else {
            finish()
            return
        }
        let serviceType = serviceTypes[serviceIndex]
        let generation = scanGeneration + 1
        scanGeneration = generation
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
                self?.finishCurrentService(error: "局域网节点扫描启动失败（\(Self.numericCode(of: error))）", generation: generation)
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
                    guard self?.scanGeneration == generation else { return }
                    self?.resolveResult(result)
                case .changed(_, let result, _):
                    guard self?.scanGeneration == generation else { return }
                    self?.resolveResult(result)
                default:
                    break
                }
            }
        }
        browser.start(queue: queue)
        queue.asyncAfter(deadline: .now() + scanWindow) { [weak self] in
            self?.finishCurrentService(generation: generation)
        }
    }

    private func finishCurrentService(error: String? = nil, generation: Int? = nil) {
        if finished || (generation != nil && generation != scanGeneration) { return }
        teardown()
        if origins.isEmpty && serviceIndex + 1 < serviceTypes.count {
            serviceIndex += 1
            startCurrentService()
            return
        }
        if origins.isEmpty, let error = error {
            finishWithError(error)
            return
        }
        finish()
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
        // A dual-stack Bonjour service commonly resolves to a scoped fe80::
        // address first. Probe both families so this does not hide its usable
        // IPv4 origin, while retaining routable IPv6-only deployments.
        for version in [NWProtocolIP.Options.Version.v4, .v6] {
            let parameters = NWParameters.tcp
            (parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = version
            resolveEndpoint(result.endpoint, parameters: parameters, txt: txtRecord)
        }
    }

    private func resolveEndpoint(_ endpoint: NWEndpoint, parameters: NWParameters, txt: NWTXTRecord?) {
        let generation = scanGeneration
        let connection = NWConnection(to: endpoint, using: parameters)
        connections.append(connection)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self = self, let connection = connection else { return }
            guard !self.finished, self.scanGeneration == generation else {
                connection.cancel()
                return
            }
            switch state {
            case .ready:
                if let remote = connection.currentPath?.remoteEndpoint,
                   case .hostPort(let host, let port) = remote {
                    self.addOrigin(host: host, port: port, txt: txt)
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
#if DEBUG
        NSLog("MeshXDiscovery resolved host=%@ port=%@", String(describing: host), String(describing: port))
#endif
        guard let origin = MeshXDiscoveryAddress.origin(host: host, port: port, txt: txt) else { return }
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
            connection.stateUpdateHandler = nil
            connection.cancel()
        }
        connections.removeAll()
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
