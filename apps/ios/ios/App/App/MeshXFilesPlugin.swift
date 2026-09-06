import Capacitor
import Foundation
import UIKit

/**
 * Lets the user explicitly choose the destination before an attachment is
 * written, mirroring the Android Storage Access Framework flow.
 *
 * 对应 Android 版 MeshXFilesPlugin（SAF/ACTION_CREATE_DOCUMENT）的 iOS 移植：
 * - Android 先弹选择器再把下载流写入所选文档；iOS 的导出选择器
 *   （UIDocumentPickerViewController(forExporting:)）要求文件先存在，
 *   因此顺序反转为“先下载到临时文件，再让用户挑选位置”。
 *   结果契约不变：{cancelled: true} 或 {location: 展示名}。
 * - Android 的 mimeType 用于配置 ACTION_CREATE_DOCUMENT；iOS 导出选择器
 *   直接依据临时文件本身推断类型，故该参数在 iOS 上不参与逻辑。
 */
@objc(MeshXFilesPlugin)
public class MeshXFilesPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MeshXFilesPlugin"
    public let jsName = "MeshXFiles"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "save", returnType: CAPPluginReturnPromise)
    ]

    static let fallbackFileName = "MeshX 文件"
    // Java 侧 READ_TIMEOUT_MS = 60_000；URLSession 的 timeoutIntervalForRequest
    // 是数据空闲计时器，同时覆盖连接建立阶段（Java 的 15s 连接超时在
    // URLSession 上没有独立开关，此处以更宽松的一档为准）。
    static let requestTimeout: TimeInterval = 60

    private let sessionsLock = NSLock()
    private var sessions: [UUID: MeshXFilesSaveSession] = [:]

    @objc func save(_ call: CAPPluginCall) {
        guard let downloadUrl = Self.downloadUrl(from: call.getString("url")) else {
            call.reject("文件地址无效")
            return
        }
        let fileName = Self.safeFileName(call.getString("name", Self.fallbackFileName))

        // 选择器展示期间必须保活调用：bridge 在方法返回后保存该 call，
        // 会话结束时置回 false 并 releaseCall 释放。
        call.keepAlive = true

        let sessionId = UUID()
        let session = MeshXFilesSaveSession(plugin: self, call: call, fileName: fileName) { [weak self] in
            guard let self = self else { return }
            self.sessionsLock.lock()
            self.sessions.removeValue(forKey: sessionId)
            self.sessionsLock.unlock()
        }
        sessionsLock.lock()
        sessions[sessionId] = session
        sessionsLock.unlock()
        session.start(downloadUrl: downloadUrl)
    }

    // 镜像 Java isDownloadUrl：仅接受带主机名的 http/https 地址。
    private static func downloadUrl(from rawUrl: String?) -> URL? {
        guard let rawUrl = rawUrl,
              !rawUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let url = URL(string: rawUrl),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host,
              !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        return url
    }

    // 镜像 Java safeFileName：替换文件系统保留字符，截断到 120 字符。
    private static func safeFileName(_ value: String?) -> String {
        let normalized = (value ?? "")
            .replacingOccurrences(
                of: "[\\\\/:*?\"<>|\\p{Cntrl}]",
                with: "_",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty { return fallbackFileName }
        return String(normalized.prefix(120))
    }
}

/**
 * 单次保存会话：下载 → 暂存 → 导出选择器 → 一次性回传结果。
 * 完成状态只在主线程变更，保证 CAPPluginCall 绝不会被二次决议。
 */
private final class MeshXFilesSaveSession: NSObject, URLSessionTaskDelegate, UIDocumentPickerDelegate {
    private enum SaveError: LocalizedError {
        case httpStatus(Int)
        case destinationUnavailable

        var errorDescription: String? {
            switch self {
            case .httpStatus(let status):
                // 与 Java 的 IllegalStateException 文案一致，作为底层原因
                // 附在 “保存文件失败” 拒绝里。
                return "文件请求失败：HTTP \(status)"
            case .destinationUnavailable:
                return "无法打开所选保存位置"
            }
        }
    }

    private weak var plugin: MeshXFilesPlugin?
    private let call: CAPPluginCall
    private let fileName: String
    private let onFinish: () -> Void
    private var stagingDirectory: URL?
    private var finished = false

    init(plugin: MeshXFilesPlugin, call: CAPPluginCall, fileName: String, onFinish: @escaping () -> Void) {
        self.plugin = plugin
        self.call = call
        self.fileName = fileName
        self.onFinish = onFinish
        super.init()
    }

    func start(downloadUrl: URL) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = MeshXFilesPlugin.requestTimeout
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        var request = URLRequest(url: downloadUrl)
        // 与 Android 相同：禁用内容编码协商，按原始字节落盘。
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let task = session.downloadTask(with: request) { [weak self] location, response, error in
            session.finishTasksAndInvalidate()
            self?.handleDownloadResult(location: location, response: response, error: error)
        }
        task.resume()
    }

    // 与 Android 的 setInstanceFollowRedirects(false) 一致：不跟随跳转，
    // 3xx 响应会落入下方的状态码校验。
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    private func handleDownloadResult(location: URL?, response: URLResponse?, error: Error?) {
        if let error = error {
            finishWithError(error)
            return
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard (200..<300).contains(status), let location = location else {
            finishWithError(SaveError.httpStatus(status))
            return
        }
        do {
            // 下载产物必须在回调返回前搬离系统临时位置；以请求的文件名
            // 暂存，导出选择器会把它作为默认文件名展示给用户。
            let staging = FileManager.default.temporaryDirectory
                .appendingPathComponent("meshx-files-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
            let exported = staging.appendingPathComponent(fileName, isDirectory: false)
            try FileManager.default.moveItem(at: location, to: exported)
            stagingDirectory = staging
            DispatchQueue.main.async { [weak self] in
                self?.presentPicker(for: exported)
            }
        } catch {
            finishWithError(error)
        }
    }

    private func presentPicker(for fileURL: URL) {
        if finished { return }
        guard let viewController = plugin?.bridge?.viewController else {
            finishWithError(SaveError.destinationUnavailable)
            return
        }
        // asCopy: false —— 系统把暂存文件移动到用户选定的位置，
        // 等价于 SAF 把字节写入用户选定的文档。
        let picker = UIDocumentPickerViewController(forExporting: [fileURL], asCopy: false)
        picker.delegate = self
        viewController.present(picker, animated: true, completion: nil)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        // Android 返回 SAF 文档的 DISPLAY_NAME（找不到时回退“所选位置”）；
        // 这里取目标文件名作为展示值——完整路径在 iOS 上是文件提供者的
        // 容器内部路径，不适合面向用户展示。
        finishWithResult(["location": urls.first?.lastPathComponent ?? "所选位置"])
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finishWithResult(["cancelled": true])
    }

    private func finishWithResult(_ payload: PluginCallResultData) {
        complete { call in
            call.resolve(payload)
        }
    }

    private func finishWithError(_ error: Error) {
        complete { call in
            call.reject("保存文件失败", nil, error)
        }
    }

    private func complete(_ deliver: @escaping (CAPPluginCall) -> Void) {
        let work = { [self] in
            if finished { return }
            finished = true
            cleanupStagingDirectory()
            call.keepAlive = false
            deliver(call)
            plugin?.bridge?.releaseCall(call)
            onFinish()
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }

    private func cleanupStagingDirectory() {
        guard let staging = stagingDirectory else { return }
        stagingDirectory = nil
        // 导出成功时文件已被系统移走，这里清理的是暂存目录本身；
        // 取消或失败时连同残留文件一并删除。
        try? FileManager.default.removeItem(at: staging)
    }
}
