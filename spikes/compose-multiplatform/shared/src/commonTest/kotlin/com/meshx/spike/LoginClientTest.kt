package com.meshx.spike

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoginClientTest {
    @Test
    fun parsesMeshXLoginEnvelopeWithoutExposingRefreshCookie() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/auth/login", request.url.encodedPath)
            respond(
                content = """{"code":200,"msg":"success","data":{"userId":7,"username":"atti","nickname":"Atti","token":"access-only","expiresIn":3600}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.SetCookie to listOf("lanchat_refresh=secret; HttpOnly; SameSite=Strict"),
                ),
            )
        }
        val result = LoginClient(platformHttpClient(engine), "desktop", "test-device")
            .login("http://127.0.0.1:8080", "atti", "password")

        val success = assertIs<LoginResult.Success>(result)
        assertEquals(7, success.session.userId)
        assertEquals("access-only", success.session.token)
    }

    @Test
    fun returnsServerMessageForRejectedLogin() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"code":500,"msg":"用户名或密码错误","data":null}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = LoginClient(platformHttpClient(engine), "android", "test-device")
            .login("http://127.0.0.1:8080", "atti", "bad")

        assertEquals("用户名或密码错误", assertIs<LoginResult.Failure>(result).message)
    }
}
