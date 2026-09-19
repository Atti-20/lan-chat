import Flutter
import UIKit
import Security

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "MobileCapabilities") {
      MobileCapabilities.register(with: registrar)
    }
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "MobileStorage") {
      MobileStorage.register(with: registrar)
    }
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "FlutterMeshXDiscovery") {
      FlutterMeshXDiscovery.register(with: registrar)
    }
  }
}

// Kept in this compiled source to avoid changing project membership or plugins.
final class MobileStorage: NSObject, FlutterPlugin {
  private let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: "com.meshx.mobile.credential.v1", kSecAttrAccount as String: "active"]
  static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "com.meshx.mobile/storage", binaryMessenger: registrar.messenger())
    registrar.addMethodCallDelegate(MobileStorage(), channel: channel)
  }
  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    func finish(_ status: OSStatus) {
      if status == errSecSuccess { result(nil) }
      else { result(FlutterError(code: "STORAGE_UNAVAILABLE", message: "安全存储不可用，请解锁设备后重试", details: nil)) }
    }
    switch call.method {
    case "dataDirectory":
      do {
        var url = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
          appropriateFor: nil, create: true).appendingPathComponent("meshx-a05")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        result(url.path)
      } catch { result(FlutterError(code: "STORAGE_UNAVAILABLE", message: "本地数据目录不可用", details: nil)) }
    case "readCredential":
      var read = query; read[kSecReturnData as String] = true; read[kSecMatchLimit as String] = kSecMatchLimitOne
      var item: CFTypeRef?
      let status = SecItemCopyMatching(read as CFDictionary, &item)
      if status == errSecItemNotFound { result(nil) }
      else if status == errSecSuccess, let data = item as? Data, let text = String(data: data, encoding: .utf8) { result(text) }
      else { finish(status == errSecSuccess ? errSecDecode : status) }
    case "writeCredential":
      guard let text = call.arguments as? String else { finish(errSecParam); return }
      let attributes: [String: Any] = [kSecValueData as String: Data(text.utf8),
        kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
      let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
      if status == errSecItemNotFound {
        finish(SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil))
      } else { finish(status) }
    case "clearCredential":
      let status = SecItemDelete(query as CFDictionary)
      finish(status == errSecItemNotFound ? errSecSuccess : status)
    default: result(FlutterMethodNotImplemented)
    }
  }
}
