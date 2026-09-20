import Flutter
import UIKit
import UserNotifications
import Network
import MobileCoreServices
import ImageIO
import PhotosUI
import CoreLocation

final class MobileCapabilities: NSObject, FlutterPlugin, FlutterStreamHandler, UNUserNotificationCenterDelegate, UIDocumentPickerDelegate, CLLocationManagerDelegate {
    private static weak var pushPlugin: MobileCapabilities?
    private var pushResult: FlutterResult?
    private var pushTimeout: DispatchWorkItem?
    static func pushRegistered(_ token: Data) {
        UserDefaults.standard.set(token.map { String(format: "%02x", $0) }.joined(), forKey: "meshx-apns-token")
        pushPlugin?.finishPush(success: true)
    }
    static func pushFailed() { pushPlugin?.finishPush(success: false) }
    private func finishPush(success: Bool) {
        guard let result = pushResult else { return }
        pushResult = nil; pushTimeout?.cancel(); pushTimeout = nil
        let defaults = UserDefaults.standard
        guard success, defaults.string(forKey: "meshx-push-owner") == owner,
              let token = defaults.string(forKey: "meshx-apns-token"),
              let scope = defaults.string(forKey: "meshx-push-scope") else { reply(result, "FAILED", "apnsRegistrationFailed"); return }
        result(["status": "SUCCESS", "platform": "APNS", "endpoint": token, "scope": scope])
    }
    private func clearPush() {
        UserDefaults.standard.removeObject(forKey: "meshx-push-owner")
        UserDefaults.standard.removeObject(forKey: "meshx-push-scope")
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        UIApplication.shared.unregisterForRemoteNotifications()
        finishPush(success: false)
    }
    private let locationManager = CLLocationManager()
    private var locationResult: FlutterResult?
    private var locationTimeout: DispatchWorkItem?
    private var sink: FlutterEventSink?
    private let monitor = NWPathMonitor()
    private var hasPath = false
    private var owner: String?
    private var notificationEpoch = UUID().uuidString
    private var pendingTap: [String: String]?
    private var pickerResult: FlutterResult?
    private weak var picker: UIDocumentPickerViewController?
    private weak var photoPicker: UIViewController?
    private var photoImporting = false
    private var photoProgress: Progress?
    private var shareResult: FlutterResult?
    private weak var shareController: UIActivityViewController?
    private let fileLock = NSLock()
    private var fileEpoch = 0
    private var fileLimit = 25 * 1024 * 1024
    private var pickerTimer: DispatchWorkItem?
    private let worker = DispatchQueue(label: "com.meshx.selected-file", qos: .userInitiated)
    private var folder: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("meshx-selected", isDirectory: true) }
    static func register(with registrar: FlutterPluginRegistrar) {
        let plugin = MobileCapabilities()
        pushPlugin = plugin
        registrar.addMethodCallDelegate(plugin, channel: FlutterMethodChannel(name: "com.meshx.mobile/capabilities", binaryMessenger: registrar.messenger()))
        FlutterEventChannel(name: "com.meshx.mobile/capabilities/events", binaryMessenger: registrar.messenger()).setStreamHandler(plugin)
        UNUserNotificationCenter.current().delegate = plugin
        plugin.monitor.pathUpdateHandler = { [weak plugin] _ in
            DispatchQueue.main.async {
                guard let plugin else { return }
                if plugin.hasPath {
                    NotificationCenter.default.post(name: Notification.Name("MeshXNetworkChanged"), object: nil)
                    plugin.sink?(["type": "networkChanged"])
                }
                plugin.hasPath = true
            }
        }
        plugin.monitor.start(queue: DispatchQueue(label: "com.meshx.network-change"))
    }
    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        sink = events
        if let pendingTap { sink?(pendingTap); self.pendingTap = nil }
        return nil
    }
    func onCancel(withArguments arguments: Any?) -> FlutterError? { sink = nil; return nil }
    private func reply(_ result: FlutterResult, _ status: String, _ reason: String = "") { result(["status": status, "reason": reason]) }
    private var presenter: UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.filter { $0.activationState == .foregroundActive }
        var root = scenes.flatMap { $0.windows }.first { $0.isKeyWindow }?.rootViewController
        while let next = root?.presentedViewController { root = next }
        return root
    }
    private func permission(_ settings: UNNotificationSettings) -> String {
        if #available(iOS 14.0, *), settings.authorizationStatus == .ephemeral { return "AVAILABLE" }
        switch settings.authorizationStatus {
        case .notDetermined: return "PERMISSION_REQUIRED"
        case .denied: return "PERMISSION_DENIED"
        case .authorized, .provisional: return settings.alertSetting == .disabled ? "PERMISSION_DENIED" : "AVAILABLE"
        default: return "UNSUPPORTED"
        }
    }
    private func finishLocation(_ status: String, _ location: CLLocation? = nil) {
        guard let result = locationResult else { return }
        locationResult = nil
        locationTimeout?.cancel(); locationTimeout = nil
        locationManager.stopUpdatingLocation()
        if let location {
            result(["status": "SUCCESS", "latitude": location.coordinate.latitude, "longitude": location.coordinate.longitude, "accuracyMeters": location.horizontalAccuracy, "timestamp": Int64(location.timestamp.timeIntervalSince1970 * 1000)])
        } else { reply(result, status) }
    }
    private func acquireLocation() {
        guard locationResult != nil else { return }
        switch locationManager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: locationManager.requestLocation()
        case .denied, .restricted: finishLocation("PERMISSION_DENIED")
        default: break
        }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { acquireLocation() }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, location.horizontalAccuracy >= 0, abs(location.timestamp.timeIntervalSinceNow) <= 60 else { finishLocation("FAILED"); return }
        finishLocation("SUCCESS", location)
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { finishLocation("FAILED") }
    func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any] ?? [:]
        let notifications = UNUserNotificationCenter.current()
        switch call.method {
        case "pushState":
            result(["status": "SUCCESS", "owner": UserDefaults.standard.string(forKey: "meshx-push-owner") as Any? ?? NSNull()])
        case "clearPush":
            clearPush(); reply(result, "SUCCESS")
        case "registerPush":
            guard pushResult == nil else { reply(result, "TEMPORARILY_UNAVAILABLE"); return }
            guard let target = args["owner"] as? String, target == owner else { reply(result, "CANCELLED"); return }
            let defaults = UserDefaults.standard
            if defaults.string(forKey: "meshx-push-owner") != target || defaults.string(forKey: "meshx-push-scope") == nil {
                defaults.set(UUID().uuidString, forKey: "meshx-push-scope")
            }
            defaults.set(target, forKey: "meshx-push-owner")
            pushResult = result
            let timeout = DispatchWorkItem { [weak self] in self?.finishPush(success: false) }
            pushTimeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: timeout)
            UIApplication.shared.registerForRemoteNotifications()

        case "currentLocation":
            guard locationResult == nil else { reply(result, "TEMPORARILY_UNAVAILABLE"); return }
            locationResult = result
            locationManager.delegate = self
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
            let timeout = DispatchWorkItem { [weak self] in self?.finishLocation("TIMEOUT") }
            locationTimeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
            if locationManager.authorizationStatus == .notDetermined { locationManager.requestWhenInUseAuthorization() }
            else { acquireLocation() }

        case "runtimeInfo":
            let bundle = Bundle.main.infoDictionary ?? [:]
            result([
                "status": "SUCCESS",
                "reason": "",
                "appVersion": bundle["CFBundleShortVersionString"] as? String ?? "0",
                "buildNumber": bundle["CFBundleVersion"] as? String ?? "0",
                "osName": UIDevice.current.systemName,
                "osVersion": UIDevice.current.systemVersion
            ])
        case "notificationPermission":
            notifications.getNotificationSettings { settings in
                if settings.authorizationStatus == .notDetermined && args["request"] as? Bool == true {
                    notifications.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
                        DispatchQueue.main.async { self.reply(result, error != nil ? "FAILED" : (granted ? "AVAILABLE" : "PERMISSION_DENIED")) }
                    }
                } else { DispatchQueue.main.async { self.reply(result, self.permission(settings)) } }
            }
        case "notificationOwner":
            if let saved = UserDefaults.standard.string(forKey: "meshx-push-owner"), saved != args["owner"] as? String { clearPush() }
            owner = args["owner"] as? String; notificationEpoch = UUID().uuidString
            notifications.removeAllPendingNotificationRequests(); notifications.removeAllDeliveredNotifications()
            reply(result, "SUCCESS")
        case "notificationShow":
            guard let target = args["owner"] as? String, target == owner,
                  let conversation = args["conversationId"] as? String,
                  let id = args["id"] as? String, !id.isEmpty, id.count <= 256 else { reply(result, "CANCELLED", "sessionChanged"); return }
            let expected = notificationEpoch
            let identifier = expected + "/" + id
            notifications.getNotificationSettings { settings in
                DispatchQueue.main.async {
                    guard self.owner == target && self.notificationEpoch == expected else { self.reply(result, "CANCELLED", "sessionChanged"); return }
                    guard self.permission(settings) == "AVAILABLE" else { self.reply(result, self.permission(settings)); return }
                    let content = UNMutableNotificationContent()
                    content.title = "MeshX"; content.body = "你有新消息，打开 MeshX 查看"
                    var route: [String: Any] = ["meshx_owner": target, "meshx_conversation": conversation]
                    if let messageId = args["messageId"] as? String { route["meshx_message"] = messageId }
                    if let broadcastId = args["broadcastId"] as? NSNumber, broadcastId.int64Value > 0 {
                        route["meshx_broadcast"] = broadcastId.stringValue
                    }
                    content.sound = .default; content.userInfo = route
                    notifications.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { error in
                        DispatchQueue.main.async {
                            if self.notificationEpoch != expected {
                                notifications.removePendingNotificationRequests(withIdentifiers: [identifier]); notifications.removeDeliveredNotifications(withIdentifiers: [identifier])
                                self.reply(result, "CANCELLED", "sessionChanged")
                            } else { self.reply(result, error == nil ? "SUCCESS" : "FAILED") }
                        }
                    }
                }
            }
        case "notificationCancel":
            let identifier = notificationEpoch + "/" + (args["id"] as? String ?? "")
            notifications.removePendingNotificationRequests(withIdentifiers: [identifier]); notifications.removeDeliveredNotifications(withIdentifiers: [identifier]); reply(result, "SUCCESS")
        case "notificationCancelAll":
            if let expected = args["owner"] as? String, expected != owner { reply(result, "CANCELLED", "sessionChanged"); return }
            notifications.removeAllPendingNotificationRequests(); notifications.removeAllDeliveredNotifications(); reply(result, "SUCCESS")
        case "pickFile":
            guard pickerResult == nil, shareResult == nil else { reply(result, "TEMPORARILY_UNAVAILABLE", "pickerBusy"); return }
            guard let view = presenter else { reply(result, "TEMPORARILY_UNAVAILABLE", "background"); return }
            fileLimit = min(25 * 1024 * 1024, max(1, args["maxBytes"] as? Int ?? 25 * 1024 * 1024))
            if args["photos"] as? Bool == true {
                if #available(iOS 14.0, *) { beginPhotoPicker(from: view, result: result) }
                else { reply(result, "UNSUPPORTED", "photoPickerUnavailable") }
                return
            }
            let picker = UIDocumentPickerViewController(documentTypes: ["public.item"], in: .open)
            picker.delegate = self; picker.allowsMultipleSelection = false
            self.picker = picker; pickerResult = result
            view.present(picker, animated: true)
            let timeout = DispatchWorkItem { [weak self] in self?.cancelPicker(status: "TIMEOUT") }
            pickerTimer = timeout; DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(90), execute: timeout)
        case "readFile":
            guard let file = selected(args["handle"]), FileManager.default.isReadableFile(atPath: file.path) else { reply(result, "FAILED", "fileMissing"); return }
            let maxBytes = min(25 * 1024 * 1024, max(0, args["maxBytes"] as? Int ?? 0))
            guard maxBytes > 0,
                  let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  size <= maxBytes else { reply(result, "FAILED", "fileTooLarge"); return }
            let expected = currentEpoch()
            worker.async {
                do {
                    let data = try Data(contentsOf: file, options: .mappedIfSafe)
                    DispatchQueue.main.async {
                        guard self.currentEpoch() == expected else { self.reply(result, "CANCELLED", "sessionChanged"); return }
                        guard data.count == size, data.count <= maxBytes else { self.reply(result, "FAILED", "fileChanged"); return }
                        result(["status": "SUCCESS", "bytes": FlutterStandardTypedData(bytes: data)])
                    }
                } catch {
                    DispatchQueue.main.async { self.reply(result, "FAILED", "fileUnavailable") }
                }
            }
        case "readFileChunk":
            guard let file = selected(args["handle"]), FileManager.default.isReadableFile(atPath: file.path) else { reply(result, "FAILED", "fileMissing"); return }
            let offset = args["offset"] as? Int ?? -1
            let length = args["length"] as? Int ?? 0
            guard let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  offset >= 0, offset <= size, length > 0, length <= 1024 * 1024 else { reply(result, "FAILED", "invalidFileRange"); return }
            let expected = currentEpoch(), count = min(length, size - offset)
            worker.async {
                do {
                    let input = try FileHandle(forReadingFrom: file)
                    defer { input.closeFile() }
                    input.seek(toFileOffset: UInt64(offset))
                    let data = input.readData(ofLength: count)
                    DispatchQueue.main.async {
                        guard self.currentEpoch() == expected else { self.reply(result, "CANCELLED", "sessionChanged"); return }
                        guard data.count == count else { self.reply(result, "FAILED", "fileChanged"); return }
                        result(["status": "SUCCESS", "bytes": FlutterStandardTypedData(bytes: data)])
                    }
                } catch {
                    DispatchQueue.main.async { self.reply(result, "FAILED", "fileUnavailable") }
                }
            }
        case "cacheFile":
            guard let typed = args["bytes"] as? FlutterStandardTypedData,
                  !typed.data.isEmpty, typed.data.count <= 25 * 1024 * 1024 else { reply(result, "FAILED", "fileTooLarge"); return }
            let rawName = args["name"] as? String ?? "MeshX-file"
            let requestedMime = args["mime"] as? String ?? "application/octet-stream"
            let mime = requestedMime.range(of: "^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$", options: .regularExpression) == nil
                ? "application/octet-stream" : requestedMime
            let invalid = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:"))
            var name = String(rawName.unicodeScalars.map { invalid.contains($0) ? "_" : String($0) }.joined().prefix(120))
            if name.isEmpty || name == "." || name == ".." || name == "mime" { name = "MeshX-file" }
            let token = UUID().uuidString
            let directory = folder.appendingPathComponent(token, isDirectory: true)
            let expected = currentEpoch(), data = typed.data
            worker.async {
                do {
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
                    try data.write(to: directory.appendingPathComponent(name), options: [.atomic, .completeFileProtection])
                    try mime.write(to: directory.appendingPathComponent("mime"), atomically: true, encoding: .utf8)
                    DispatchQueue.main.async {
                        guard self.currentEpoch() == expected else { try? FileManager.default.removeItem(at: directory); self.reply(result, "CANCELLED", "sessionChanged"); return }
                        result(["status": "SUCCESS", "handle": token, "name": name, "mime": mime, "size": data.count])
                    }
                } catch {
                    try? FileManager.default.removeItem(at: directory)
                    DispatchQueue.main.async { self.reply(result, "FAILED", "fileUnavailable") }
                }
            }
        case "releaseFile":
            guard let file = selected(args["handle"]) else { reply(result, "FAILED", "fileMissing"); return }
            do { try FileManager.default.removeItem(at: file.deletingLastPathComponent()); reply(result, "SUCCESS") }
            catch { reply(result, "FAILED", "fileUnavailable") }
        case "releaseAllFiles":
            cancelPicker(status: "CANCELLED")
            shareController?.dismiss(animated: false)
            if let pending = shareResult { shareResult = nil; reply(pending, "CANCELLED", "sessionChanged") }
            do {
                if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
                reply(result, "SUCCESS")
            } catch { reply(result, "FAILED", "fileUnavailable") }
        case "shareFile":
            guard pickerResult == nil, shareResult == nil else { reply(result, "TEMPORARILY_UNAVAILABLE", "pickerBusy"); return }
            guard let file = selected(args["handle"]), FileManager.default.isReadableFile(atPath: file.path) else { reply(result, "FAILED", "fileMissing"); return }
            guard let view = presenter else { reply(result, "TEMPORARILY_UNAVAILABLE", "background"); return }
            let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            sheet.popoverPresentationController?.sourceView = view.view
            sheet.popoverPresentationController?.sourceRect = CGRect(x: view.view.bounds.midX, y: view.view.bounds.maxY, width: 1, height: 1)
            shareResult = result; shareController = sheet
            sheet.completionWithItemsHandler = { [weak self] _, completed, _, error in
                guard let self, let pending = self.shareResult else { return }
                self.shareResult = nil
                self.reply(pending, error != nil ? "FAILED" : (completed ? "SUCCESS" : "CANCELLED"), completed ? "systemShareCompleted" : "")
            }
            view.present(sheet, animated: true)
        case "openSettings":
            guard let url = URL(string: UIApplication.openSettingsURLString) else { reply(result, "UNSUPPORTED"); return }
            guard UIApplication.shared.applicationState == .active, presenter != nil else { reply(result, "TEMPORARILY_UNAVAILABLE", "background"); return }
            guard UIApplication.shared.canOpenURL(url) else { reply(result, "FAILED", "settingsUrlUnavailable"); return }
            UIApplication.shared.open(url, options: [:]) { ok in
                self.reply(result, ok ? "SUCCESS" : "FAILED", ok ? "settingsOpenAccepted" : "settingsOpenRejected")
            }
        default: result(FlutterMethodNotImplemented)
        }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completion: @escaping (UNNotificationPresentationOptions) -> Void) {
        guard notification.request.content.userInfo["meshx_owner"] as? String == owner else { completion([]); return }
        completion([.alert, .sound])
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completion: @escaping () -> Void) {
        defer { completion() }
        if let scope = response.notification.request.content.userInfo["meshxScope"] as? String,
           scope == UserDefaults.standard.string(forKey: "meshx-push-scope"),
           let target = UserDefaults.standard.string(forKey: "meshx-push-owner") {
            let event = ["type": "notificationTap", "owner": target, "conversationId": "push-inbox"]
            if sink != nil { sink?(event) } else { pendingTap = event }
            return
        }
        guard let target = response.notification.request.content.userInfo["meshx_owner"] as? String,
              let conversation = response.notification.request.content.userInfo["meshx_conversation"] as? String else { return }
        var event = ["type": "notificationTap", "owner": target, "conversationId": conversation]
        if let messageId = response.notification.request.content.userInfo["meshx_message"] as? String { event["messageId"] = messageId }
        if let broadcast = response.notification.request.content.userInfo["meshx_broadcast"] as? String {
            event["broadcastId"] = broadcast
        }
        if sink != nil { sink?(event) } else { pendingTap = event }
    }
    private func selected(_ value: Any?) -> URL? {
        guard let token = value as? String, UUID(uuidString: token) != nil, !token.contains("/") else { return nil }
        let directory = folder.appendingPathComponent(token, isDirectory: true)
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey]),
              let file = files.first(where: { $0.lastPathComponent != "mime" }),
              (try? file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { return nil }
        return file
    }
    private func currentEpoch() -> Int { fileLock.lock(); defer { fileLock.unlock() }; return fileEpoch }
    private func advanceEpoch() { fileLock.lock(); fileEpoch += 1; fileLock.unlock() }
    private func cancelPicker(status: String) {
        advanceEpoch(); pickerTimer?.cancel(); picker?.dismiss(animated: false)
        photoPicker?.dismiss(animated: false); photoPicker = nil
        photoProgress?.cancel(); photoProgress = nil; photoImporting = false
        if let result = pickerResult { pickerResult = nil; reply(result, status) }
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancelPicker(status: "CANCELLED") }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard pickerResult != nil, let source = urls.first else { cancelPicker(status: "FAILED"); return }
        copySelectedSource(source)
    }
    private func copySelectedSource(_ source: URL, cleanup: URL? = nil) {
        guard pickerResult != nil else { if let cleanup { try? FileManager.default.removeItem(at: cleanup) }; return }
        let expected = currentEpoch(), maxBytes = fileLimit
        let token = UUID().uuidString
        let directory = folder.appendingPathComponent(token, isDirectory: true)
        worker.async {
            defer { if let cleanup { try? FileManager.default.removeItem(at: cleanup) } }
            let scoped = source.startAccessingSecurityScopedResource()
            defer { if scoped { source.stopAccessingSecurityScopedResource() } }
            var outcome: [String: Any] = ["status": "FAILED", "reason": "fileUnreadable"]
            if !scoped && !source.path.hasPrefix(NSHomeDirectory() + "/") {
                outcome = ["status": "PERMISSION_DENIED", "reason": "fileAccessDenied"]
            } else {
                var coordinationError: NSError?
                NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { coordinated in
                    do {
                        let values = try coordinated.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                        guard values.isRegularFile == true else { throw SelectedError.unreadable }
                        if let size = values.fileSize, size > maxBytes { throw SelectedError.tooLarge }
                        var name = String(coordinated.lastPathComponent.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) || "/\\:".unicodeScalars.contains($0) ? "_" : String($0) }.joined().prefix(120))
                        if name.isEmpty || name == "." || name == ".." || name == "mime" { name = "selected-file" }
                        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
                        let destination = directory.appendingPathComponent(name)
                        guard let input = InputStream(url: coordinated), let output = OutputStream(url: destination, append: false) else { throw SelectedError.unreadable }
                        input.open(); output.open(); defer { input.close(); output.close() }
                        var bytes = [UInt8](repeating: 0, count: 32768), size = 0
                        let deadline = Date().addingTimeInterval(30)
                        while true {
                            if self.currentEpoch() != expected { throw SelectedError.cancelled }
                            if Date() > deadline { throw SelectedError.timeout }
                            let count = input.read(&bytes, maxLength: bytes.count)
                            if count < 0 { throw SelectedError.unreadable }; if count == 0 { break }
                            size += count; if size > maxBytes { throw SelectedError.tooLarge }
                            var offset = 0
                            try bytes.withUnsafeBufferPointer { buffer in
                                while offset < count {
                                    let written = output.write(buffer.baseAddress!.advanced(by: offset), maxLength: count - offset)
                                    if written <= 0 { throw SelectedError.unreadable }; offset += written
                                }
                            }
                        }
                        var mime = "application/octet-stream"
                        if let uti = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, coordinated.pathExtension as CFString, nil)?.takeRetainedValue(),
                           let value = UTTypeCopyPreferredTagWithClass(uti, kUTTagClassMIMEType)?.takeRetainedValue() { mime = value as String }
                        input.close(); output.close()
                        if let prepared = try self.prepareImage(destination, maxBytes: maxBytes) {
                            name = prepared.name; mime = prepared.mime; size = prepared.size
                        }
                        if self.currentEpoch() != expected { throw SelectedError.cancelled }
                        if Date() > deadline { throw SelectedError.timeout }
                        try mime.write(to: directory.appendingPathComponent("mime"), atomically: true, encoding: .utf8)
                        outcome = ["status": "SUCCESS", "handle": token, "name": name, "mime": mime, "size": size]
                    } catch SelectedError.tooLarge { outcome = ["status": "FAILED", "reason": "fileTooLarge"] }
                    catch SelectedError.cancelled { outcome = ["status": "CANCELLED"] }
                    catch SelectedError.timeout { outcome = ["status": "TIMEOUT"] }
                    catch { outcome = ["status": "FAILED", "reason": "fileUnreadable"] }
                }
                if coordinationError != nil { outcome = ["status": "FAILED", "reason": "fileUnavailable"] }
            }
            DispatchQueue.main.async {
                guard self.currentEpoch() == expected, let pending = self.pickerResult else {
                    try? FileManager.default.removeItem(at: directory); return
                }
                self.pickerTimer?.cancel(); self.pickerResult = nil
                if outcome["status"] as? String != "SUCCESS" { try? FileManager.default.removeItem(at: directory) }
                pending(outcome)
            }
        }
    }
    // Leave ordinary files and supported, in-limit images byte-for-byte intact.
    // ImageIO downsamples before decoding the full camera image into memory.
    private func prepareImage(_ file: URL, maxBytes: Int) throws -> (name: String, mime: String, size: Int)? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let type = CGImageSourceGetType(source) as String?,
              ["public.jpeg", "public.png", "public.heic", "public.heif"].contains(type),
              CGImageSourceGetCount(source) == 1,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0 else { return nil }
        let heif = type == "public.heic" || type == "public.heif"
        guard heif || Double(width) * Double(height) > 40_000_000 else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 4096,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { throw SelectedError.unreadable }
        let png = type == "public.png"
        let name = String(file.deletingPathExtension().lastPathComponent.prefix(100)) + "-meshx." + (png ? "png" : "jpg")
        let target = file.deletingLastPathComponent().appendingPathComponent(name)
        guard let destination = CGImageDestinationCreateWithURL(target as CFURL, png ? kUTTypePNG : kUTTypeJPEG, 1, nil) else { throw SelectedError.unreadable }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw SelectedError.unreadable }
        let size = try target.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= maxBytes else { throw SelectedError.tooLarge }
        try FileManager.default.removeItem(at: file)
        return (name, png ? "image/png" : "image/jpeg", size)
    }
    private enum SelectedError: Error { case tooLarge, unreadable, cancelled, timeout }
    deinit { monitor.cancel(); advanceEpoch(); pickerTimer?.cancel(); photoProgress?.cancel() }
}

// MARK: - System photo selection; reuses the existing opaque-file pipeline.
@available(iOS 14.0, *)
extension MobileCapabilities: PHPickerViewControllerDelegate {
    private func beginPhotoPicker(from presenter: UIViewController, result: @escaping FlutterResult) {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .images
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let controller = PHPickerViewController(configuration: config)
        controller.delegate = self
        photoPicker = controller; photoImporting = false; pickerResult = result
        presenter.present(controller, animated: true)
        let timeout = DispatchWorkItem { [weak self] in self?.cancelPicker(status: "TIMEOUT") }
        pickerTimer = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(90), execute: timeout)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard picker === photoPicker, pickerResult != nil, !photoImporting else { return }
        guard let item = results.first else { cancelPicker(status: "CANCELLED"); return }
        photoImporting = true
        let expected = currentEpoch(), limit = fileLimit
        let provider = item.itemProvider
        guard let type = provider.registeredTypeIdentifiers.first(where: {
            UTTypeConformsTo($0 as CFString, kUTTypeImage)
        }) else { cancelPicker(status: "FAILED"); return }
        // Dismiss before importing. A provider URL is valid only during its
        // completion callback; copy it synchronously there, never queue the URL.
        picker.dismiss(animated: true)
        let ext = (UTTypeCopyPreferredTagWithClass(type as CFString, kUTTagClassFilenameExtension)?.takeRetainedValue() as String?) ?? "image"
        photoProgress = provider.loadFileRepresentation(forTypeIdentifier: type) { [weak self] url, error in
            guard let self else { return }
            let staging = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("meshx-photo-import", isDirectory: true)
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            do {
                guard error == nil, let url else { throw SelectedError.unreadable }
                guard self.currentEpoch() == expected else { throw SelectedError.cancelled }
                let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard values.isRegularFile == true else { throw SelectedError.unreadable }
                if let size = values.fileSize, size > limit { throw SelectedError.tooLarge }
                try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true,
                    attributes: [.protectionKey: FileProtectionType.complete])
                let safeExt = ext.filter { $0.isLetter || $0.isNumber }
                let destination = staging.appendingPathComponent("photo." + String(safeExt.prefix(12)))
                guard let input = InputStream(url: url), let output = OutputStream(url: destination, append: false) else { throw SelectedError.unreadable }
                input.open(); output.open()
                defer { input.close(); output.close() }
                var bytes = [UInt8](repeating: 0, count: 32768), size = 0
                let deadline = Date().addingTimeInterval(30)
                while true {
                    guard self.currentEpoch() == expected else { throw SelectedError.cancelled }
                    guard Date() < deadline else { throw SelectedError.timeout }
                    let count = input.read(&bytes, maxLength: bytes.count)
                    if count < 0 { throw SelectedError.unreadable }
                    if count == 0 { break }
                    size += count
                    guard size <= limit else { throw SelectedError.tooLarge }
                    try bytes.withUnsafeBufferPointer { buffer in
                        var offset = 0
                        while offset < count {
                            let wrote = output.write(buffer.baseAddress!.advanced(by: offset), maxLength: count - offset)
                            guard wrote > 0 else { throw SelectedError.unreadable }
                            offset += wrote
                        }
                    }
                }
                guard size > 0 else { throw SelectedError.unreadable }
                input.close(); output.close()
                DispatchQueue.main.async {
                    guard self.currentEpoch() == expected, self.pickerResult != nil else {
                        try? FileManager.default.removeItem(at: staging); return
                    }
                    self.photoProgress = nil
                    self.copySelectedSource(destination, cleanup: staging)
                }
            } catch {
                try? FileManager.default.removeItem(at: staging)
                DispatchQueue.main.async {
                    guard self.currentEpoch() == expected, let pending = self.pickerResult else { return }
                    self.pickerTimer?.cancel(); self.pickerResult = nil
                    self.photoProgress = nil; self.photoImporting = false
                    switch error {
                    case SelectedError.cancelled: self.reply(pending, "CANCELLED")
                    case SelectedError.timeout: self.reply(pending, "TIMEOUT")
                    case SelectedError.tooLarge: self.reply(pending, "FAILED", "fileTooLarge")
                    default: self.reply(pending, "FAILED", "photoUnavailable")
                    }
                }
            }
        }
    }
}
