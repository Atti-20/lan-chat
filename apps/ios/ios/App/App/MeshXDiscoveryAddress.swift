import Foundation
import Network

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
