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

  override func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    super.application(application, didRegisterForRemoteNotificationsWithDeviceToken: deviceToken)
    MobileCapabilities.pushRegistered(deviceToken)
  }
  override func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
    super.application(application, didFailToRegisterForRemoteNotificationsWithError: error)
    MobileCapabilities.pushFailed()
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "MeshXGlass") {
      MeshXGlassPlugin.register(with: registrar)
    }
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

// MARK: - MeshX native navigation/control layer (Liquid Glass, iOS 26+)
// Kept in this already-compiled file; no project membership or engine changes.
final class MeshXGlassPlugin: NSObject, FlutterPlugin, FlutterPlatformViewFactory,
    UIAdaptivePresentationControllerDelegate {
    private let messenger: FlutterBinaryMessenger
    private let channel: FlutterMethodChannel
    private var registrar: FlutterPluginRegistrar?
    private var observers: [NSObjectProtocol] = []
    private var menu: UIAlertController?
    private var menuResult: FlutterResult?
    private var menuTimer: DispatchWorkItem?
    private let controls = NSHashTable<MeshXGlassNativeView>.weakObjects()

    private init(registrar: FlutterPluginRegistrar) {
        self.registrar = registrar
        messenger = registrar.messenger()
        channel = FlutterMethodChannel(name: "com.meshx.mobile/glass", binaryMessenger: messenger)
        super.init()
        for name in [UIAccessibility.reduceTransparencyStatusDidChangeNotification,
                     UIAccessibility.reduceMotionStatusDidChangeNotification,
                     UIAccessibility.darkerSystemColorsStatusDidChangeNotification,
                     UIContentSizeCategory.didChangeNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                guard let self else { return }
                for view in self.controls.allObjects { view.refreshAccessibility() }
                self.channel.invokeMethod("accessibilityChanged", arguments: self.capabilities)
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: .main) { [weak self] _ in self?.finishMenu(nil, animated: false) })
    }

    static func register(with registrar: FlutterPluginRegistrar) {
        let plugin = MeshXGlassPlugin(registrar: registrar)
        registrar.addMethodCallDelegate(plugin, channel: plugin.channel)
        registrar.register(plugin, withId: "com.meshx.mobile/glass-view")
        registrar.publish(plugin)
    }

    private var capabilities: [String: Any] {
        var liquid = false
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) { liquid = true }
        #endif
        var photos = false
        if #available(iOS 14.0, *) { photos = true }
        return ["version": 1, "available": true, "liquidGlass": liquid,
            "photoPicker": photos,
            "reduceTransparency": UIAccessibility.isReduceTransparencyEnabled,
            "reduceMotion": UIAccessibility.isReduceMotionEnabled,
            "increaseContrast": UIAccessibility.isDarkerSystemColorsEnabled]
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol { FlutterStandardMessageCodec.sharedInstance() }
    func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        let control = MeshXGlassPlatformView(frame: frame, id: viewId,
            parameters: args as? [String: Any] ?? [:], messenger: messenger)
        controls.add(control.nativeView)
        return control
    }

    func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? [String: Any] ?? [:]
        switch call.method {
        case "capabilities": result(capabilities)
        case "dismissMenu": finishMenu(nil, animated: false); result(nil)
        case "attachmentMenu":
            guard arguments["version"] as? Int == 1 else {
                result(FlutterError(code: "BAD_VERSION", message: "菜单版本不兼容", details: nil)); return
            }
            guard menuResult == nil else {
                result(FlutterError(code: "PRESENTATION_BUSY", message: "请先关闭当前菜单", details: nil)); return
            }
            // Resolve the engine's own controller at call time, not an arbitrary
            // app window. A headless engine cannot present UI.
            guard let presenter = registrar?.viewController,
                  presenter.viewIfLoaded?.window != nil,
                  presenter.presentedViewController == nil,
                  UIApplication.shared.applicationState == .active else {
                result(FlutterError(code: "PRESENTATION_UNAVAILABLE", message: "当前无法打开菜单", details: nil)); return
            }
            let sheet = UIAlertController(title: "发送附件", message: nil, preferredStyle: .actionSheet)
            sheet.overrideUserInterfaceStyle = arguments["dark"] as? Bool == true ? .dark : .light
            if arguments["photos"] as? Bool == true {
                sheet.addAction(UIAlertAction(title: "照片", style: .default) { [weak self] _ in self?.finishMenu("photos") })
            }
            sheet.addAction(UIAlertAction(title: "文件", style: .default) { [weak self] _ in self?.finishMenu("file") })
            sheet.addAction(UIAlertAction(title: "取消", style: .cancel) { [weak self] _ in self?.finishMenu(nil) })
            sheet.popoverPresentationController?.sourceView = presenter.view
            sheet.popoverPresentationController?.sourceRect = CGRect(
                x: presenter.view.bounds.midX, y: presenter.view.bounds.maxY - presenter.view.safeAreaInsets.bottom,
                width: 1, height: 1)
            menu = sheet; menuResult = result
            presenter.present(sheet, animated: !UIAccessibility.isReduceMotionEnabled)
            sheet.presentationController?.delegate = self
            let timeout = DispatchWorkItem { [weak self] in self?.finishMenu(nil, animated: false) }
            menuTimer = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 90, execute: timeout)
        default: result(FlutterMethodNotImplemented)
        }
    }

    private func finishMenu(_ selection: String?, animated: Bool = true) {
        guard let pending = menuResult else { return }
        menuResult = nil; menuTimer?.cancel(); menuTimer = nil
        let current = menu; menu = nil
        // Complete only after dismissal. Dart may immediately open a file picker.
        if let current, current.presentingViewController != nil {
            current.dismiss(animated: animated && !UIAccessibility.isReduceMotionEnabled) { pending(selection) }
        } else { pending(selection) }
    }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { finishMenu(nil, animated: false) }
    func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        finishMenu(nil, animated: false)
        observers.forEach(NotificationCenter.default.removeObserver); observers.removeAll()
        self.registrar = nil
    }
    deinit { observers.forEach(NotificationCenter.default.removeObserver); menuTimer?.cancel() }
}

private final class MeshXGlassPlatformView: NSObject, FlutterPlatformView {
    let nativeView: MeshXGlassNativeView
    private let channel: FlutterMethodChannel
    private var revision = -1
    init(frame: CGRect, id: Int64, parameters: [String: Any], messenger: FlutterBinaryMessenger) {
        channel = FlutterMethodChannel(name: "com.meshx.mobile/glass/view/\(id)", binaryMessenger: messenger)
        nativeView = MeshXGlassNativeView(frame: frame, kind: parameters["kind"] as? String ?? "surface")
        super.init()
        nativeView.emitAction = { [weak self] id in self?.channel.invokeMethod("action", arguments: ["id": id]) }
        nativeView.emitHeight = { [weak self] height in self?.channel.invokeMethod("height", arguments: height) }
        nativeView.update(parameters)
        channel.setMethodCallHandler { [weak self] call, result in
            guard let self else { result(nil); return }
            guard call.method == "update", let args = call.arguments as? [String: Any], args["version"] as? Int == 1 else {
                result(FlutterMethodNotImplemented); return
            }
            let next = args["revision"] as? Int ?? 0
            if next >= self.revision { self.revision = next; self.nativeView.update(args) }
            result(nil)
        }
    }
    func view() -> UIView { nativeView }
    deinit { channel.setMethodCallHandler(nil) }
}

private final class MeshXGlassNativeView: UIView, UITabBarDelegate {
    private let kind: String
    private let effectView = UIVisualEffectView()
    private let button = UIButton(type: .system)
    private let tabBar = UITabBar()
    private var parameters: [String: Any] = [:]
    private var reportedHeight: CGFloat = 0
    var emitAction: ((String) -> Void)?
    var emitHeight: ((CGFloat) -> Void)?

    init(frame: CGRect, kind: String) {
        self.kind = kind
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        switch kind {
        case "navigation":
            tabBar.delegate = self
            let labels = ["消息", "联系人", "群聊", "广播"]
            let symbols = ["bubble.left.and.bubble.right", "person.crop.rectangle", "person.2", "bell"]
            tabBar.items = zip(labels, symbols).enumerated().map { index, pair in
                let item = UITabBarItem(title: pair.0, image: UIImage(systemName: pair.1), tag: index)
                item.accessibilityIdentifier = "meshx-tab-\(index)"
                return item
            }
            addSubview(tabBar)
            // No barTintColor, appearance background or fixed corner override:
            // standard UITabBar supplies its native material and selection lens.
        case "button":
            button.addTarget(self, action: #selector(pressed), for: .touchUpInside)
            addSubview(button)
        default:
            isUserInteractionEnabled = false
            accessibilityElementsHidden = true
            addSubview(effectView)
        }
    }
    required init?(coder: NSCoder) { fatalError("MeshXGlassNativeView is created by Flutter") }

    func update(_ value: [String: Any]) {
        var next = value
        next.removeValue(forKey: "revision")
        if NSDictionary(dictionary: parameters).isEqual(to: next) { return }
        parameters = next
        overrideUserInterfaceStyle = value["dark"] as? Bool == true ? .dark : .light
        if let number = value["tint"] as? NSNumber {
            let argb = number.uint32Value
            tintColor = UIColor(red: CGFloat((argb >> 16) & 255) / 255,
                green: CGFloat((argb >> 8) & 255) / 255, blue: CGFloat(argb & 255) / 255,
                alpha: CGFloat((argb >> 24) & 255) / 255)
        }
        refreshAccessibility()
    }

    func refreshAccessibility() {
        let opaque = UIAccessibility.isReduceTransparencyEnabled || UIAccessibility.isDarkerSystemColorsEnabled || parameters["opaque"] as? Bool == true
        switch kind {
        case "navigation":
            tabBar.tintColor = tintColor
            let selected = parameters["selected"] as? Int ?? 0
            if let items = tabBar.items, items.indices.contains(selected) { tabBar.selectedItem = items[selected] }
            let counts = parameters["counts"] as? [Int] ?? []
            for (index, item) in (tabBar.items ?? []).enumerated() {
                let count = counts.indices.contains(index) ? counts[index] : -1
                item.badgeValue = count > 0 ? (count > 99 ? "99+" : String(count)) : nil
                item.accessibilityValue = count < 0 ? "数量更新中" : count == 0 ? nil : "\(count) 条待处理"
            }
        case "button":
            let prominent = parameters["prominent"] as? Bool == true
            let allowed: Set<String> = ["chevron.left", "person.crop.circle", "ellipsis", "arrow.clockwise", "moon", "plus", "xmark.circle", "arrow.up"]
            let symbol = parameters["symbol"] as? String ?? "ellipsis"
            let image = UIImage(systemName: allowed.contains(symbol) ? symbol : "ellipsis")
            if #available(iOS 15.0, *) {
                var configuration = prominent ? UIButton.Configuration.filled() : UIButton.Configuration.gray()
                #if compiler(>=6.2)
                if #available(iOS 26.0, *), !opaque {
                    configuration = prominent ? .prominentGlass() : .glass()
                }
                #endif
                configuration.cornerStyle = .capsule
                configuration.baseForegroundColor = prominent ? .white : .label
                if prominent { configuration.baseBackgroundColor = tintColor }
                configuration.image = image
                button.configuration = configuration
            } else {
                button.setImage(image, for: .normal)
                button.tintColor = prominent ? .white : .label
                button.backgroundColor = prominent ? tintColor : .secondarySystemBackground
                button.layer.cornerRadius = min(bounds.width, bounds.height) / 2
            }
            button.isEnabled = parameters["enabled"] as? Bool == true
            button.accessibilityLabel = parameters["label"] as? String ?? "操作"
        default:
            let requested = (parameters["radius"] as? NSNumber)?.doubleValue ?? 24
            let radius = requested.isFinite ? min(100, max(0, requested)) : 24
            effectView.backgroundColor = opaque ? .secondarySystemBackground : .clear
            if opaque {
                effectView.effect = nil
            } else {
                #if compiler(>=6.2)
                if #available(iOS 26.0, *) {
                    effectView.effect = UIGlassEffect(style: .regular)
                } else { effectView.effect = UIBlurEffect(style: .systemMaterial) }
                #else
                effectView.effect = UIBlurEffect(style: .systemMaterial)
                #endif
            }
            #if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                effectView.cornerConfiguration = .corners(radius: .fixed(CGFloat(radius)))
            } else { effectView.layer.cornerRadius = CGFloat(radius); effectView.clipsToBounds = true }
            #else
            effectView.layer.cornerRadius = CGFloat(radius); effectView.clipsToBounds = true
            #endif
            effectView.layer.borderWidth = opaque ? 1 : 0
            effectView.layer.borderColor = UIColor.separator.cgColor
        }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        effectView.frame = bounds; button.frame = bounds; tabBar.frame = bounds
        guard kind == "navigation", bounds.width > 0 else { return }
        let measured = tabBar.sizeThatFits(CGSize(width: bounds.width, height: 0)).height
        if measured.isFinite, measured > 0, measured < 300, abs(measured - reportedHeight) > 0.5 {
            reportedHeight = measured
            DispatchQueue.main.async { [weak self] in self?.emitHeight?(measured) }
        }
    }
    @objc private func pressed() { if button.isEnabled { emitAction?("activate") } }
    func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) { emitAction?(String(item.tag)) }
}
