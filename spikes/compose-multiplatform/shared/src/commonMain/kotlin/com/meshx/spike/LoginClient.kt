package com.meshx.spike

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val meshXJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun platformHttpClient(engine: io.ktor.client.engine.HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(meshXJson)
    }
    install(HttpCookies)
}

@Serializable
private data class LoginRequest(
    val username: String,
    val password: String,
    val deviceType: String,
    val deviceName: String,
)

@Serializable
private data class ApiEnvelope<T>(
    val code: Int,
    val msg: String = "",
    val data: T? = null,
    val requestId: String? = null,
)

@Serializable
private data class LoginPayload(
    val userId: Long,
    val username: String,
    val nickname: String? = null,
    val avatar: String? = null,
    val token: String,
    val expiresIn: Long,
)

data class AuthSession(
    val userId: Long,
    val username: String,
    val nickname: String,
    val avatar: String?,
    val token: String,
    val expiresIn: Long,
)

sealed interface LoginResult {
    data class Success(val session: AuthSession) : LoginResult
    data class Failure(val message: String, val requestId: String? = null) : LoginResult
}

class LoginClient(
    private val client: HttpClient,
    private val deviceType: String,
    private val deviceName: String,
) {
    suspend fun login(originInput: String, usernameInput: String, password: String): LoginResult {
        val origin = runCatching { normalizeControlOrigin(originInput) }
            .getOrElse { return LoginResult.Failure(it.message ?: "Control 地址无效") }
        val username = usernameInput.trim()
        if (username.isEmpty()) return LoginResult.Failure("请输入用户名")
        if (password.isEmpty()) return LoginResult.Failure("请输入密码")

        return try {
            val response = client.post("$origin/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username, password, deviceType, deviceName.take(120)))
            }
            val envelope = response.body<ApiEnvelope<LoginPayload>>()
            val payload = envelope.data
            if (!response.status.isSuccess() || envelope.code != 200 || payload == null) {
                LoginResult.Failure(envelope.msg.ifBlank { "登录失败（${envelope.code}）" }, envelope.requestId)
            } else if (payload.token.isBlank() || payload.expiresIn <= 0) {
                LoginResult.Failure("Control 返回了无效会话", envelope.requestId)
            } else {
                LoginResult.Success(
                    AuthSession(
                        userId = payload.userId,
                        username = payload.username,
                        nickname = payload.nickname ?: payload.username,
                        avatar = payload.avatar,
                        token = payload.token,
                        expiresIn = payload.expiresIn,
                    ),
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            LoginResult.Failure("无法连接 Control，或响应格式不兼容")
        }
    }

}

internal fun normalizeControlOrigin(input: String): String {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "请输入 Control 地址" }
    val candidate = if ("://" in trimmed) trimmed else "http://$trimmed"
    val parsed = Url(candidate)
    require(parsed.protocol.name == "http" || parsed.protocol.name == "https") { "仅支持 HTTP/HTTPS Control" }
    require(parsed.host.isNotBlank()) { "Control 地址缺少主机名" }
    require(parsed.user == null && parsed.password == null) { "Control 地址不能包含账号信息" }

    return URLBuilder(parsed).apply {
        encodedPath = ""
        parameters.clear()
        fragment = ""
    }.buildString().removeSuffix("/")
}
