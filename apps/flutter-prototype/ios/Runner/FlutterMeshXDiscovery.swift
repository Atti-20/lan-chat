import Flutter
import Foundation
import Network
import UIKit

final class FlutterMeshXDiscovery: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var sink: FlutterEventSink?
    private var scan: MeshXDiscoveryScanSession?
    private var session = 0
    private var observers: [NSObjectProtocol] = []
    static func register(with registrar: FlutterPluginRegistrar) {
        let plugin = FlutterMeshXDiscovery()
        registrar.addMethodCallDelegate(plugin, channel: FlutterMethodChannel(name: "com.meshx.prototype/discovery", binaryMessenger: registrar.messenger()))
        FlutterEventChannel(name: "com.meshx.prototype/discovery/events", binaryMessenger: registrar.messenger()).setStreamHandler(plugin)
        for (name, reason) in [(UIApplication.didEnterBackgroundNotification, "background"),
                               (Notification.Name("MeshXNetworkChanged"), "networkChanged")] {
            plugin.observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak plugin] _ in
                plugin?.scan?.stop(status: "TEMPORARILY_UNAVAILABLE", reason: reason)
            })
        }
    }
    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? { sink = events; return nil }
    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        sink = nil; scan?.stop(status: "CANCELLED", reason: "listenerDetached"); return nil
    }
    func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any] ?? [:]
        switch call.method {
        case "start":
            guard UIApplication.shared.applicationState != .background else {
                result(["status": "TEMPORARILY_UNAVAILABLE", "reason": "background"]); return
            }
            // iOS exposes no permission-status API. Do not trigger a first prompt on auto-resume.
            if args["requestPermission"] as? Bool != true && !UserDefaults.standard.bool(forKey: "meshx.lanPreviouslyAvailable") {
                result(["status": "PERMISSION_REQUIRED"]); return
            }
            scan?.stop(status: "CANCELLED", reason: "replaced")
            session = args["session"] as? Int ?? 0
            let owner = session
            let window = min(30000, max(1000, args["windowMs"] as? Int ?? 8000))
            let next = MeshXDiscoveryScanSession(window: window) { [weak self] nodes, status, reason, complete in
                guard let self, self.session == owner else { return }
                if status == "PERMISSION_DENIED" { UserDefaults.standard.set(false, forKey: "meshx.lanPreviouslyAvailable") }
                self.sink?(["session": owner, "nodes": nodes, "status": status, "reason": reason, "complete": complete])
                if complete { self.scan = nil }
            }
            scan = next
            result(["status": "AVAILABLE"])
            next.start()
        case "stop":
            if let expected = args["session"] as? Int, expected != session { result(["status": "SUCCESS"]); return }
            scan?.stop(status: args["cancelled"] as? Bool == true ? "CANCELLED" : "SUCCESS", reason: "stopped")
            result(["status": "SUCCESS"])
        case "prepareConnection": result(["status": "AVAILABLE"])
        default: result(FlutterMethodNotImplemented)
        }
    }
    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        scan?.stop(status: "CANCELLED", reason: "disposed")
    }
}

private final class MeshXDiscoveryScanSession {
    private var browsers: [NWBrowser] = []
    private var connections: [UUID: NWConnection] = [:]
    private var present = Set<NWEndpoint>()
    private var revisions: [NWEndpoint: Int] = [:]
    private var nodes: [NWEndpoint: [String: [String: String]]] = [:]
    private var finished = false
    private var ready = 0
    private var failed = 0
    private var resolutionFailures = 0
    private var status = "SUCCESS"
    private var timer: DispatchWorkItem?
    private let window: Int
    private let emit: ([[String: String]], String, String, Bool) -> Void
    init(window: Int, emit: @escaping ([[String: String]], String, String, Bool) -> Void) { self.window = window; self.emit = emit }
    private var snapshot: [[String: String]] { Array(nodes.values.flatMap { $0.values }.prefix(128)) }
    func start() {
        for type in ["_meshx-control._tcp", "_lanchat._tcp"] {
            let browser = NWBrowser(for: .bonjourWithTXTRecord(type: type, domain: "local."), using: NWParameters())
            browsers.append(browser)
            browser.stateUpdateHandler = { [weak self] state in
                guard let self, !self.finished else { return }
                switch state {
                case .ready:
                    self.ready += 1
                    UserDefaults.standard.set(true, forKey: "meshx.lanPreviouslyAvailable")
                    self.emit(self.snapshot, self.status, "snapshot", false)
                case .waiting(let error), .failed(let error):
                    if case .dns(let code) = error, code == -65570 {
                        self.stop(status: "PERMISSION_DENIED", reason: "localNetworkDenied")
                    } else {
                        self.status = "TEMPORARILY_UNAVAILABLE"
                        self.emit(self.snapshot, self.status, "networkUnavailable", false)
                        if case .failed = state { self.failed += 1; if self.failed == 2 { self.stop(status: self.status, reason: "discoveryStartFailed") } }
                    }
                default: break
                }
            }
            browser.browseResultsChangedHandler = { [weak self] _, changes in
                guard let self, !self.finished else { return }
                for change in changes {
                    switch change {
                    case .added(let value), .changed(_, let value, _):
                        guard self.present.count < 128 || self.present.contains(value.endpoint) else { continue }
                        self.present.insert(value.endpoint)
                        let revision = (self.revisions[value.endpoint] ?? 0) + 1
                        self.revisions[value.endpoint] = revision
                        self.nodes[value.endpoint] = [:]
                        self.resolve(value, revision: revision)
                    case .removed(let value):
                        self.present.remove(value.endpoint); self.nodes.removeValue(forKey: value.endpoint)
                        self.revisions[value.endpoint] = (self.revisions[value.endpoint] ?? 0) + 1
                    default: break
                    }
                }
                self.emit(self.snapshot, self.status, "snapshot", false)
            }
            browser.start(queue: .main)
        }
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, !self.finished else { return }
            let outcome = self.ready == 0 ? "TEMPORARILY_UNAVAILABLE" : (self.snapshot.isEmpty && self.resolutionFailures > 0 ? "FAILED" : self.status)
            self.stop(status: outcome, reason: self.resolutionFailures > 0 && self.snapshot.isEmpty ? "resolutionFailed" : "windowComplete")
        }
        timer = timeout; DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(window), execute: timeout)
    }
    private func resolve(_ result: NWBrowser.Result, revision: Int) {
        var txt: NWTXTRecord?
        if case .bonjour(let value) = result.metadata { txt = value }
        for family in [NWProtocolIP.Options.Version.v4, .v6] {
            if connections.count >= 64 { resolutionFailures += 1; break }
            let parameters = NWParameters.tcp
            (parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = family
            let id = UUID(), connection = NWConnection(to: result.endpoint, using: parameters)
            connections[id] = connection
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                guard let self, let connection, !self.finished, self.present.contains(result.endpoint), self.revisions[result.endpoint] == revision else {
                    connection?.cancel(); return
                }
                switch state {
                case .ready:
                    if case .hostPort(let host, let port) = connection.currentPath?.remoteEndpoint,
                       let origin = MeshXDiscoveryAddress.origin(host: host, port: port, txt: txt) {
                        let identifier = String((txt?.dictionary["nodeId"] ?? txt?.dictionary["controlId"] ?? origin).prefix(160))
                        let name = String((txt?.dictionary["nodeName"] ?? txt?.dictionary["controlName"] ?? "MeshX 节点").prefix(80))
                        self.nodes[result.endpoint, default: [:]][origin] = ["id": identifier, "name": name, "origin": origin]
                        self.emit(self.snapshot, self.status, "snapshot", false)
                    }
                    connection.cancel(); self.connections.removeValue(forKey: id)
                case .failed:
                    self.resolutionFailures += 1; connection.cancel(); self.connections.removeValue(forKey: id)
                default: break
                }
            }
            connection.start(queue: .main)
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(3)) { [weak self, weak connection] in
                if let value = self?.connections.removeValue(forKey: id) { value.cancel(); self?.resolutionFailures += 1 }
                connection?.stateUpdateHandler = nil
            }
        }
    }
    func stop(status: String, reason: String) {
        if finished { return }
        finished = true; timer?.cancel()
        let finalNodes = reason == "windowComplete" ? snapshot : []
        for browser in browsers { browser.stateUpdateHandler = nil; browser.browseResultsChangedHandler = nil; browser.cancel() }
        browsers.removeAll()
        for connection in connections.values { connection.stateUpdateHandler = nil; connection.cancel() }
        connections.removeAll(); nodes.removeAll(); present.removeAll()
        emit(finalNodes, status, reason, true)
    }
}
/// Produces origins that can survive the shared WHATWG URL and native HTTP path.
enum MeshXDiscoveryAddress {
    static func origin(host: NWEndpoint.Host, port: NWEndpoint.Port, txt: NWTXTRecord?) -> String? {
        guard port.rawValue > 0 else { return nil }
        guard var address = advertisedHost(txt) ?? literal(host), !address.isEmpty else { return nil }
        if address.contains(":") { address = "[\(address)]" }
        let secure = (txt?.dictionary["secure"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let scheme = secure.caseInsensitiveCompare("true") == .orderedSame ? "https" : "http"
        return "\(scheme)://\(address):\(port.rawValue)"
    }

    private static func advertisedHost(_ txt: NWTXTRecord?) -> String? {
        guard let value = txt?.dictionary["advertisedHost"] else { return nil }
        let host = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let valid = host.range(of: "^[A-Za-z0-9.-]{1,253}$", options: .regularExpression) != nil
            && !host.hasPrefix(".") && !host.contains("..")
        return valid ? host : nil
    }

    private static func literal(_ host: NWEndpoint.Host) -> String? {
        switch host {
        case .ipv4(let address):
            // Bonjour supplies scoped IPv4 too (192.168.0.100%en0).
            // Use address bytes, not its diagnostic description, in a URL.
            return address.rawValue.map(String.init).joined(separator: ".")
        case .ipv6(let address):
            // fe80::/10 requires its interface scope. Removing it creates a
            // different, unusable origin; retaining it is rejected by WHATWG
            // URL. Resolve the service's IPv4 address separately instead, or
            // use an advertised DNS hostname. Routable IPv6 remains supported.
            guard !address.isLinkLocal else { return nil }
            return address.debugDescription.split(separator: "%", maxSplits: 1).first.map(String.init)
        case .name(let name, _):
            return name
        @unknown default:
            return nil
        }
    }
}
