import Foundation
import Network

@main
enum MeshXDiscoveryAddressTests {
    static func main() {
        let port = NWEndpoint.Port(rawValue: 18080)!
        func origin(_ host: NWEndpoint.Host, _ txt: NWTXTRecord? = nil) -> String? {
            MeshXDiscoveryAddress.origin(host: host, port: port, txt: txt)
        }
        let ipv4 = NWEndpoint.Host.ipv4(IPv4Address("192.168.0.100")!)
        let local = NWEndpoint.Host.ipv6(IPv6Address("fe80::1")!)
        precondition(origin(ipv4) == "http://192.168.0.100:18080")
        precondition(origin(NWEndpoint.Host("192.168.0.100%en0")) == "http://192.168.0.100:18080", "Bonjour's IPv4 interface is not part of the HTTP hostname")
        precondition(origin(local) == nil, "Never strip the interface from a link-local address")
        precondition(origin(.ipv6(IPv6Address("fd12::1")!)) == "http://[fd12::1]:18080")
        precondition(origin(.ipv6(IPv6Address("2001:db8::1")!)) == "http://[2001:db8::1]:18080")
        precondition(origin(local, NWTXTRecord(["advertisedHost": "meshx.local", "secure": "TRUE"])) == "https://meshx.local:18080")
        precondition(origin(ipv4, NWTXTRecord(["advertisedHost": "bad/host"])) == "http://192.168.0.100:18080")
        precondition(origin(ipv4, NWTXTRecord(["advertisedHost": "user@meshx.local"])) == "http://192.168.0.100:18080")
        precondition(origin(ipv4, NWTXTRecord(["advertisedHost": "..local"])) == "http://192.168.0.100:18080")
        precondition(origin(.name("meshx.local", nil)) == "http://meshx.local:18080")
        print("MeshX discovery address tests: 10 passed")
    }
}
