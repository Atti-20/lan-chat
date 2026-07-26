import Foundation

/// 移植自 Android MeshXAuthClient：原生 HTTP 认证客户端。
/// 关键不变量与 Android 完全一致：
/// - Cookie 按规范化 origin 隔离，仅接受认证端点自己的 Set-Cookie；
/// - refresh token 只存在于原生层（Keychain），结构上不可达 JS；
/// - 登录/刷新/退出一律禁止重定向；
/// - 请求成功且带有刷新 Cookie 才提交会话（暂存-提交语义）；
/// - 每个 origin 同一时刻至多一个在途操作（busy 可观测）。
enum MeshXAuthError: Error {
    case busy(String)
    case failure(String)

    var message: String {
        switch self {
        case .busy(let text), .failure(let text):
            return text
        }
    }
}

struct MeshXAuthSession {
    let userId: Int64
    let username: String
    let nickname: String
    let avatar: String?
    let token: String
    let expiresIn: Int64
}

final class MeshXAuthClient {
    static let apiBasePath = "/api/v1"
    private static let refreshCookie = "lanchat_refresh"
    private static let busyMessage = "a native authentication request is already in progress"
    private static let maxBodyBytes = 512 * 1024
    private static let connectTimeout: TimeInterval = 5
    private static let callTimeout: TimeInterval = 12

    private struct StoredCookie: Codable {
        let name: String
        let value: String
        let expiresAt: Double?
    }

    private final class OriginState {
        var inFlight = false
        var loaded = false
        var cookies: [StoredCookie] = []
    }

    private let store = MeshXKeychainStore()
    private let lock = NSLock()
    private var origins: [String: OriginState] = [:]

    // MARK: - Public operations

    func login(
        rawOrigin: String,
        apiBasePath: String,
        username: String,
        password: String,
        deviceName: String
    ) throws -> MeshXAuthSession {
        let origin = try Self.canonicalOrigin(rawOrigin)
        try Self.requireApiBasePath(apiBasePath)
        guard !username.isEmpty, !password.isEmpty else {
            throw MeshXAuthError.failure("username and password are required")
        }
        let state = try begin(origin: origin)
        defer { finish(state: state) }

        let body: [String: Any] = [
            "username": username,
            "password": password,
            "deviceType": "ios",
            "deviceName": Self.normalizedDeviceName(deviceName),
        ]
        let outcome = try execute(origin: origin, path: "/auth/login", body: body, accessToken: nil, sendCookies: [])
        let session = try Self.parseSession(outcome.body)
        guard let refreshed = Self.refreshCookies(from: outcome.setCookies), !refreshed.isEmpty else {
            throw MeshXAuthError.failure("node did not establish a native refresh session")
        }
        commit(origin: origin, state: state, cookies: refreshed)
        return session
    }

    func refresh(rawOrigin: String, apiBasePath: String, deviceName: String) throws -> MeshXAuthSession {
        let origin = try Self.canonicalOrigin(rawOrigin)
        try Self.requireApiBasePath(apiBasePath)
        let state = try begin(origin: origin)
        defer { finish(state: state) }

        let existing = validCookies(state: state)
        guard !existing.isEmpty else {
            throw MeshXAuthError.failure("no native refresh session exists for this node")
        }
        let body: [String: Any] = [
            "deviceType": "ios",
            "deviceName": Self.normalizedDeviceName(deviceName),
        ]
        let outcome = try execute(origin: origin, path: "/auth/refresh", body: body, accessToken: nil, sendCookies: existing)
        let session = try Self.parseSession(outcome.body)
        guard let rotated = Self.refreshCookies(from: outcome.setCookies), !rotated.isEmpty else {
            throw MeshXAuthError.failure("node did not retain a native refresh session")
        }
        commit(origin: origin, state: state, cookies: rotated)
        return session
    }

    func logout(rawOrigin: String, apiBasePath: String, accessToken: String?) throws {
        let origin = try Self.canonicalOrigin(rawOrigin)
        try Self.requireApiBasePath(apiBasePath)
        let state = try begin(origin: origin)
        defer { finish(state: state) }

        let cookies = validCookies(state: state)
        var requestFailure: MeshXAuthError?
        if !cookies.isEmpty || !(accessToken ?? "").isEmpty {
            do {
                _ = try execute(origin: origin, path: "/auth/logout", body: [:], accessToken: accessToken, sendCookies: cookies)
            } catch let error as MeshXAuthError {
                requestFailure = error
            }
        }
        // 网络退出失败也必须清空本地会话（与 Android 相同的降级语义）。
        clearLocked(origin: origin, state: state)
        if let failure = requestFailure { throw failure }
    }

    func clearNodeSession(rawOrigin: String) throws {
        let origin = try Self.canonicalOrigin(rawOrigin)
        lock.lock()
        let state = origins[origin] ?? OriginState()
        origins[origin] = state
        state.loaded = true
        state.cookies = []
        lock.unlock()
        store.remove(origin: origin)
    }

    func isBusy(rawOrigin: String) -> Bool {
        guard let origin = try? Self.canonicalOrigin(rawOrigin) else { return false }
        lock.lock()
        defer { lock.unlock() }
        return origins[origin]?.inFlight ?? false
    }

    // MARK: - Per-origin state

    private func begin(origin: String) throws -> OriginState {
        lock.lock()
        defer { lock.unlock() }
        let state = origins[origin] ?? OriginState()
        origins[origin] = state
        if state.inFlight {
            throw MeshXAuthError.busy(Self.busyMessage)
        }
        if !state.loaded {
            if let blob = store.load(origin: origin),
               let cookies = try? JSONDecoder().decode([StoredCookie].self, from: blob) {
                state.cookies = cookies
            }
            state.loaded = true
        }
        state.inFlight = true
        return state
    }

    private func finish(state: OriginState) {
        lock.lock()
        state.inFlight = false
        lock.unlock()
    }

    private func validCookies(state: OriginState) -> [StoredCookie] {
        lock.lock()
        defer { lock.unlock() }
        let now = Date().timeIntervalSince1970
        return state.cookies.filter { cookie in
            guard let expiresAt = cookie.expiresAt else { return true }
            return expiresAt > now
        }
    }

    private func commit(origin: String, state: OriginState, cookies: [StoredCookie]) {
        lock.lock()
        state.cookies = cookies
        lock.unlock()
        if let blob = try? JSONEncoder().encode(cookies) {
            store.save(origin: origin, blob: blob)
        }
    }

    private func clearLocked(origin: String, state: OriginState) {
        lock.lock()
        state.cookies = []
        lock.unlock()
        store.remove(origin: origin)
    }

    // MARK: - HTTP

    private struct Outcome {
        let body: [String: Any]
        let setCookies: [HTTPCookie]
    }

    /// 拒绝一切重定向：3xx 响应原样返回后按错误处理。
    private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) {
            completionHandler(nil)
        }
    }

    private func execute(
        origin: String,
        path: String,
        body: [String: Any],
        accessToken: String?,
        sendCookies: [StoredCookie]
    ) throws -> Outcome {
        guard let endpoint = URL(string: origin + Self.apiBasePath + path) else {
            throw MeshXAuthError.failure("unable to construct native authentication request")
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = Self.callTimeout
        if let token = accessToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if !sendCookies.isEmpty {
            let header = sendCookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
            request.setValue(header, forHTTPHeaderField: "Cookie")
        }
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            throw MeshXAuthError.failure("unable to construct native authentication request")
        }
        request.httpBody = payload

        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpCookieStorage = nil
        configuration.timeoutIntervalForRequest = Self.callTimeout
        configuration.timeoutIntervalForResource = Self.callTimeout
        let session = URLSession(configuration: configuration, delegate: NoRedirectDelegate(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        var resultData: Data?
        var resultResponse: HTTPURLResponse?
        var resultError: Error?
        let semaphore = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: request) { data, response, error in
            resultData = data
            resultResponse = response as? HTTPURLResponse
            resultError = error
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()

        if let error = resultError as? URLError {
            if error.code == .timedOut {
                throw MeshXAuthError.failure("node authentication request timed out")
            }
            throw MeshXAuthError.failure("unable to connect to the selected node")
        }
        if resultError != nil {
            throw MeshXAuthError.failure("unable to connect to the selected node")
        }
        guard let response = resultResponse else {
            throw MeshXAuthError.failure("unable to connect to the selected node")
        }
        if (300...399).contains(response.statusCode) {
            throw MeshXAuthError.failure("node authentication redirects are not allowed")
        }
        guard let data = resultData, !data.isEmpty else {
            throw MeshXAuthError.failure("node returned an empty authentication response")
        }
        if data.count > Self.maxBodyBytes {
            throw MeshXAuthError.failure("node authentication response was too large")
        }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw MeshXAuthError.failure("node returned an unrecognizable authentication response")
        }
        let code = (json["code"] as? NSNumber)?.intValue ?? -1
        if !(200...299).contains(response.statusCode) || code != 200 {
            let message = (json["msg"] as? String) ?? ""
            if message.isEmpty {
                throw MeshXAuthError.failure("authentication request failed with HTTP \(response.statusCode)")
            }
            throw MeshXAuthError.failure(message)
        }

        // 仅接受与认证端点同源的 Set-Cookie（对应 Android StagedCookieJar 的过滤）。
        let headerFields = (response.allHeaderFields as? [String: String]) ?? [:]
        let cookies = HTTPCookie.cookies(withResponseHeaderFields: headerFields, for: endpoint)
        return Outcome(body: json, setCookies: cookies)
    }

    private static func refreshCookies(from cookies: [HTTPCookie]) -> [StoredCookie]? {
        let matches = cookies.filter { $0.name == refreshCookie && !$0.value.isEmpty }
        guard !matches.isEmpty else { return nil }
        return matches.map { cookie in
            StoredCookie(
                name: cookie.name,
                value: cookie.value,
                expiresAt: cookie.expiresDate?.timeIntervalSince1970
            )
        }
    }

    // MARK: - Parsing & validation

    private static func parseSession(_ envelope: [String: Any]) throws -> MeshXAuthSession {
        guard let data = envelope["data"] as? [String: Any] else {
            throw MeshXAuthError.failure("authentication response did not contain session data")
        }
        guard let userId = (data["userId"] as? NSNumber)?.int64Value, userId > 0,
              let username = data["username"] as? String, !username.isEmpty,
              let token = data["token"] as? String, !token.isEmpty,
              let expiresIn = (data["expiresIn"] as? NSNumber)?.int64Value, expiresIn > 0 else {
            throw MeshXAuthError.failure("authentication response did not contain session data")
        }
        let nickname = (data["nickname"] as? String) ?? username
        let avatar = data["avatar"] as? String
        return MeshXAuthSession(
            userId: userId,
            username: username,
            nickname: nickname,
            avatar: avatar,
            token: token,
            expiresIn: expiresIn
        )
    }

    static func canonicalOrigin(_ rawOrigin: String) throws -> String {
        let trimmed = rawOrigin.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 2_048 else {
            throw MeshXAuthError.failure("node origin is invalid")
        }
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty else {
            throw MeshXAuthError.failure("node origin must use HTTP or HTTPS")
        }
        guard components.user == nil, components.password == nil,
              components.path.isEmpty || components.path == "/",
              components.query == nil, components.fragment == nil else {
            throw MeshXAuthError.failure("node origin must not contain credentials, a path, query, or fragment")
        }
        let bracketedHost = host.contains(":") ? "[\(host)]" : host.lowercased()
        let defaultPort = scheme == "https" ? 443 : 80
        if let port = components.port, port != defaultPort {
            return "\(scheme)://\(bracketedHost):\(port)"
        }
        return "\(scheme)://\(bracketedHost)"
    }

    private static func requireApiBasePath(_ value: String) throws {
        guard value == apiBasePath else {
            throw MeshXAuthError.failure("node API base path is not supported")
        }
    }

    private static func normalizedDeviceName(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "MeshX iOS" }
        return String(trimmed.prefix(100))
    }
}
