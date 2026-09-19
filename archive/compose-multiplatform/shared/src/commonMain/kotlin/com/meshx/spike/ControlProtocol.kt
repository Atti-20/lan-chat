package com.meshx.spike

private const val CONTROL_PROTOCOL_VERSION = "2"
private const val CONTROL_API_BASE_PATH = "/api/v2"
private const val CONTROL_INFO_PATH = "/api/v2/control/info"
private const val REFRESH_TRANSPORT = "HTTP_ONLY_COOKIE"
private val identityPattern = Regex("^[a-z0-9][a-z0-9._:-]{2,127}$")

fun controlFromDnsSd(
    serviceName: String,
    port: Int,
    addresses: List<String>,
    properties: Map<String, String>,
): Result<DiscoveredControl> = runCatching {
    require(port in 1..65_535) { "mDNS 端口无效" }
    require(properties["protocolVersion"] == CONTROL_PROTOCOL_VERSION) { "不支持的 Control 协议" }
    require(properties["controlApiBasePath"] == CONTROL_API_BASE_PATH) { "Control API 路径不匹配" }
    require(properties["infoPath"] == CONTROL_INFO_PATH) { "Control 信息路径不匹配" }
    require(properties["desktopAuthSupported"] == "true") { "Control 未启用原生客户端登录" }
    require(properties["refreshTransport"] == REFRESH_TRANSPORT) { "Control 刷新令牌协议不匹配" }

    val controlId = properties.requireIdentity("controlId")
    val organizationId = properties.requireIdentity("organizationId")
    val secure = when (properties["secure"]) {
        "true" -> true
        "false" -> false
        else -> error("mDNS secure 字段无效")
    }
    val address = addresses.firstOrNull(::usableAddress) ?: error("mDNS 未提供可用地址")
    val host = if (':' in address && !address.startsWith("[")) "[$address]" else address
    val scheme = if (secure) "https" else "http"
    val defaultPort = (!secure && port == 80) || (secure && port == 443)
    val origin = if (defaultPort) "$scheme://$host" else "$scheme://$host:$port"

    DiscoveredControl(
        controlId = controlId,
        organizationId = organizationId,
        name = properties["controlName"].safeLabel(serviceName),
        organizationName = properties["organizationName"].safeLabel("Local Organization"),
        origin = origin,
        secure = secure,
        serviceName = serviceName.take(160),
    )
}

private fun Map<String, String>.requireIdentity(key: String): String {
    val value = get(key)?.trim()?.lowercase().orEmpty()
    require(identityPattern.matches(value)) { "mDNS $key 字段无效" }
    return value
}

private fun String?.safeLabel(fallback: String): String =
    this?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: fallback.take(80)

private fun usableAddress(address: String): Boolean {
    val normalized = address.substringBefore('%').lowercase()
    return normalized.isNotBlank() &&
        normalized != "0.0.0.0" &&
        normalized != "127.0.0.1" &&
        normalized != "::" &&
        normalized != "::1"
}
