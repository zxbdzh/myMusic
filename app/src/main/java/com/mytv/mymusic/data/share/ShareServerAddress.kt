package com.mytv.mymusic.data.share

import java.net.URI
import java.security.MessageDigest

/**
 * 把用户输入的服务地址规范成 origin（`scheme://host[:port]`），不合法返回 null。
 * 规则照桌面版：
 *
 * - 只允许 HTTPS；HTTP 只放行本机回环（本地自建服务调试用，公网必须走 TLS）。
 * - 不接受带账号、路径、查询、片段的地址：分享服务的接口都挂在根路径下，带路径只说明用户贴错了。
 * - 端口等于协议默认值时省掉，避免同一台服务存出两条不同的令牌键。
 */
internal fun normalizeShareServerUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    // 有账号信息、查询或片段：不是 origin
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host ?: return null
    val path = uri.path.orEmpty()
    if (path.isNotEmpty() && path != "/") return null
    when (scheme) {
        "https" -> Unit
        "http" -> if (!shareHostIsLoopback(host)) return null
        else -> return null
    }
    val defaultPort = if (scheme == "https") 443 else 80
    val port = uri.port
    return when {
        port == -1 || port == defaultPort -> "$scheme://$host"
        port in 1..65535 -> "$scheme://$host:$port"
        else -> null
    }
}

/**
 * 是否本机回环。只认 `localhost` 与 IP 字面量，**不做 DNS 解析**：
 * 校验地址时为了判断回环去查一次域名，遇到坏域名会把界面卡住几秒。
 */
private fun shareHostIsLoopback(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare.equals("localhost", ignoreCase = true)) return true
    if (bare == "::1") return true
    val parts = bare.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 127
}

/**
 * 服务令牌在 `SecretStore` 里的名字：按 origin 的 sha256 取 32 位十六进制。
 * 换地址就换一把钥匙，用户改回旧地址时旧令牌还在，不会互相覆盖。
 */
internal fun shareTokenName(origin: String): String =
    "share-token-" + MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)
