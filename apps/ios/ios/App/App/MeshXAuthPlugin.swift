import Capacitor
import Foundation

/// JS 只拿 access token、用户资料与 expiresIn；refresh token 留在原生层。
/// 忙碌时快速失败（AUTH_BUSY），与 Android 插件语义一致。
@objc(MeshXAuthPlugin)
public class MeshXAuthPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MeshXAuthPlugin"
    public let jsName = "MeshXAuth"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "login", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "refresh", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "logout", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearNodeSession", returnType: CAPPluginReturnPromise),
    ]

    private let client = MeshXAuthClient()
    private let queue = DispatchQueue(label: "com.atti20.lanchat.meshx-auth", qos: .userInitiated)

    @objc func login(_ call: CAPPluginCall) {
        guard let origin = call.getString("origin"),
              let apiBasePath = call.getString("apiBasePath"),
              let username = call.getString("username"),
              let password = call.getString("password"),
              let deviceName = call.getString("deviceName") else {
            call.reject("login requires origin, apiBasePath, username, password and deviceName")
            return
        }
        run(call, origin: origin) {
            let session = try self.client.login(
                rawOrigin: origin,
                apiBasePath: apiBasePath,
                username: username,
                password: password,
                deviceName: deviceName
            )
            call.resolve(Self.sessionPayload(session))
        }
    }

    @objc func refresh(_ call: CAPPluginCall) {
        guard let origin = call.getString("origin"),
              let apiBasePath = call.getString("apiBasePath"),
              let deviceName = call.getString("deviceName") else {
            call.reject("refresh requires origin, apiBasePath and deviceName")
            return
        }
        run(call, origin: origin) {
            let session = try self.client.refresh(
                rawOrigin: origin,
                apiBasePath: apiBasePath,
                deviceName: deviceName
            )
            call.resolve(Self.sessionPayload(session))
        }
    }

    @objc func logout(_ call: CAPPluginCall) {
        guard let origin = call.getString("origin"),
              let apiBasePath = call.getString("apiBasePath") else {
            call.reject("logout requires origin and apiBasePath")
            return
        }
        let accessToken = call.getString("accessToken")
        run(call, origin: origin) {
            try self.client.logout(rawOrigin: origin, apiBasePath: apiBasePath, accessToken: accessToken)
            call.resolve()
        }
    }

    @objc func clearNodeSession(_ call: CAPPluginCall) {
        guard let origin = call.getString("origin") else {
            call.reject("clearNodeSession requires origin")
            return
        }
        queue.async {
            do {
                try self.client.clearNodeSession(rawOrigin: origin)
                call.resolve()
            } catch let error as MeshXAuthError {
                call.reject(error.message)
            } catch {
                call.reject("unable to clear the native node session")
            }
        }
    }

    private func run(_ call: CAPPluginCall, origin: String, _ operation: @escaping () throws -> Void) {
        if client.isBusy(rawOrigin: origin) {
            call.reject("a native authentication request is already in progress", "AUTH_BUSY")
            return
        }
        queue.async {
            do {
                try operation()
            } catch let error as MeshXAuthError {
                switch error {
                case .busy(let message):
                    call.reject(message, "AUTH_BUSY")
                case .failure(let message):
                    call.reject(message)
                }
            } catch {
                call.reject("native authentication request failed")
            }
        }
    }

    /// 载荷键集合固定为 {userId, username, nickname, avatar?, token, expiresIn}。
    private static func sessionPayload(_ session: MeshXAuthSession) -> [String: Any] {
        var payload: [String: Any] = [
            "userId": session.userId,
            "username": session.username,
            "nickname": session.nickname,
            "token": session.token,
            "expiresIn": session.expiresIn,
        ]
        if let avatar = session.avatar { payload["avatar"] = avatar }
        return payload
    }
}
