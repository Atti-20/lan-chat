import Foundation
import Security

/// 按规范化 origin 隔离的刷新会话存储。iOS 侧由 Keychain 提供设备绑定加密
/// （对应 Android 的 EncryptedOriginSessionStore + Keystore AES-GCM）；
/// kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly 禁止会话随备份迁移到其他设备。
final class MeshXKeychainStore {
    private let service = "com.atti20.lanchat.meshx-auth"

    func load(origin: String) -> Data? {
        var query = baseQuery(origin: origin)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else {
            // 读取失败按无会话处理（fail closed）；损坏条目由下一次 save 覆盖。
            if status != errSecItemNotFound { remove(origin: origin) }
            return nil
        }
        return data
    }

    func save(origin: String, blob: Data) {
        var attributes = baseQuery(origin: origin)
        attributes[kSecValueData as String] = blob
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let update: [String: Any] = [
                kSecValueData as String: blob,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ]
            SecItemUpdate(baseQuery(origin: origin) as CFDictionary, update as CFDictionary)
        }
    }

    func remove(origin: String) {
        SecItemDelete(baseQuery(origin: origin) as CFDictionary)
    }

    private func baseQuery(origin: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: origin,
        ]
    }
}
