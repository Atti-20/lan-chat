import Capacitor
import UIKit

/// 对应 Android MainActivity 的插件注册：应用内自定义插件必须在桥接
/// 加载时显式注册（Main.storyboard 的根控制器指向本类）。
class MeshXViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(MeshXAuthPlugin())
        bridge?.registerPluginInstance(MeshXDiscoveryPlugin())
        bridge?.registerPluginInstance(MeshXFilesPlugin())
    }
}
