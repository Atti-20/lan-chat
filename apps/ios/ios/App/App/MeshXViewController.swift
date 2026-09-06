import Capacitor
import UIKit

/// 对应 Android MainActivity 的插件注册：应用内自定义插件必须在桥接
/// 加载时显式注册（Main.storyboard 的根控制器指向本类）。
class MeshXViewController: CAPBridgeViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        guard let webView = webView else { return }

        // Keep every shared web screen, including fixed overlays, outside the
        // status bar and home indicator. The web viewport uses this safe size.
        let container = UIView()
        container.backgroundColor = .systemBackground
        view = container
        webView.translatesAutoresizingMaskIntoConstraints = false
        // The container and shared CSS explicitly own safe-area and keyboard
        // geometry.  Do not let UIScrollView add a second automatic inset,
        // which can otherwise leave a stale vertical offset after send/focus
        // transitions in a WKWebView.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.automaticallyAdjustsScrollIndicatorInsets = false
        container.addSubview(webView)
        let safeArea = container.safeAreaLayoutGuide
        var constraints = [
            webView.topAnchor.constraint(equalTo: safeArea.topAnchor),
            webView.leadingAnchor.constraint(equalTo: safeArea.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: safeArea.trailingAnchor)
        ]
        // Let UIKit resize the actual WebView with the iPhone keyboard. CSS
        // then receives the matching visual viewport instead of a page that
        // Safari attempts to pan above the visible screen.
        if #available(iOS 15.0, *) {
            constraints.append(webView.bottomAnchor.constraint(equalTo: container.keyboardLayoutGuide.topAnchor))
        } else {
            constraints.append(webView.bottomAnchor.constraint(equalTo: safeArea.bottomAnchor))
        }
        NSLayoutConstraint.activate(constraints)
    }

    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(MeshXAuthPlugin())
        bridge?.registerPluginInstance(MeshXDiscoveryPlugin())
        bridge?.registerPluginInstance(MeshXFilesPlugin())
        bridge?.registerPluginInstance(MeshXAppearancePlugin())
    }
}

/// Kept beside the controller because this bridge only owns native shell appearance.
@objc(MeshXAppearancePlugin)
public class MeshXAppearancePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MeshXAppearancePlugin"
    public let jsName = "MeshXAppearance"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "setTheme", returnType: CAPPluginReturnPromise)
    ]

    @objc func setTheme(_ call: CAPPluginCall) {
        guard let mode = call.getString("mode"), mode == "light" || mode == "dark" else {
            call.reject("主题无效")
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let controller = self?.bridge?.viewController as? CAPBridgeViewController else {
                call.reject("原生窗口尚未就绪")
                return
            }
            let dark = mode == "dark"
            let style: UIUserInterfaceStyle = dark ? .dark : .light
            controller.overrideUserInterfaceStyle = style
            controller.view.window?.overrideUserInterfaceStyle = style
            controller.setStatusBarStyle(dark ? .lightContent : .darkContent)
            let background = dark
                ? UIColor(red: 15 / 255, green: 15 / 255, blue: 16 / 255, alpha: 1)
                : UIColor(red: 237 / 255, green: 240 / 255, blue: 244 / 255, alpha: 1)
            controller.view.backgroundColor = background
            self?.bridge?.webView?.backgroundColor = background
            call.resolve()
        }
    }
}
