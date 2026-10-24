package com.flux.codebuddy

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale

/**
 * Parsed, normalized server address.
 *
 * [startUrl] is what the WebView loads (query and fragment are preserved).
 * [probeUrl] is what the reachability test requests (no query/fragment).
 * [origin] is the WebView document-start-script origin.
 */
data class ServerTarget(
    val startUrl: String,
    val probeUrl: String,
    val origin: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val isPrivateHost: Boolean,
    val displayHost: String,
) {
    val isHttps: Boolean get() = scheme == "https"
}

enum class UrlError {
    EMPTY,
    INVALID,
    CREDENTIALS,
    PASSWORD_QUERY,
    PUBLIC_HTTP,
    BAD_PORT,
    BAD_PATH,
}

sealed interface UrlResult {
    data class Ok(val target: ServerTarget) : UrlResult
    data class Error(val reason: UrlError) : UrlResult
}

/**
 * Pure-JVM URL policy, kept free of Android classes so it can be unit tested.
 *
 * Security policy:
 *  - only http/https
 *  - no credentials in the URL
 *  - no password query parameter (the web UI login is used instead)
 *  - plain HTTP only for loopback / RFC1918 / link-local / CGNAT / IPv6
 *    ULA / .local-style hosts; public hosts must use HTTPS
 */
object ServerUrl {

    fun parse(rawInput: String): UrlResult {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return UrlResult.Error(UrlError.EMPTY)

        // A bare "192.168.1.100:7890" is a common input; default to HTTP for it.
        // Public hosts will then be rejected by the HTTP policy below.
        val withScheme = if (raw.contains("://")) raw else "http://$raw"

        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return UrlResult.Error(UrlError.INVALID)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return UrlResult.Error(UrlError.INVALID)
        if (scheme != "http" && scheme != "https") return UrlResult.Error(UrlError.INVALID)
        if (uri.userInfo != null) return UrlResult.Error(UrlError.CREDENTIALS)

        val rawHost = uri.host ?: return UrlResult.Error(UrlError.INVALID)
        val host = rawHost.removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (host.isBlank() || host.any { it.isWhitespace() }) return UrlResult.Error(UrlError.INVALID)

        val port = if (uri.port == -1) defaultPort(scheme) else uri.port
        if (port !in 1..65535) return UrlResult.Error(UrlError.BAD_PORT)

        val path = uri.rawPath ?: ""
        if (path.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlResult.Error(UrlError.BAD_PATH)
        }

        val query = uri.rawQuery
        if (query != null && hasPasswordParam(query)) {
            return UrlResult.Error(UrlError.PASSWORD_QUERY)
        }

        val privateHost = isPrivateHost(host)
        if (scheme == "http" && !privateHost) return UrlResult.Error(UrlError.PUBLIC_HTTP)

        val hostForUrl = if (host.contains(':')) "[$host]" else host
        val nonDefaultPort = port != defaultPort(scheme)
        val portSuffix = if (nonDefaultPort) ":$port" else ""
        val origin = "$scheme://$hostForUrl$portSuffix"
        val normalizedPath = if (path.isBlank()) "/" else path
        val probeUrl = "$origin$normalizedPath"
        val displayHost = hostForUrl + portSuffix

        val startUrl = buildString {
            append(origin)
            append(normalizedPath)
            if (query != null) append('?').append(query)
            val fragment = uri.rawFragment
            if (fragment != null) append('#').append(fragment)
        }

        return UrlResult.Ok(
            ServerTarget(
                startUrl = startUrl,
                probeUrl = probeUrl,
                origin = origin,
                scheme = scheme,
                host = host,
                port = port,
                isPrivateHost = privateHost,
                displayHost = displayHost,
            ),
        )
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    private fun hasPasswordParam(rawQuery: String): Boolean {
        return rawQuery.split('&').any { part ->
            val rawKey = part.substringBefore('=')
            val key = try {
                URLDecoder.decode(rawKey, "UTF-8")
            } catch (_: Exception) {
                rawKey
            }
            key.equals("password", ignoreCase = true) || key.equals("passwd", ignoreCase = true)
        }
    }

    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.removeSurrounding("[", "]").lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") ||
            host.endsWith(".internal") || host.endsWith(".localhost")
        ) {
            return true
        }

        // Single-label hostnames are local (mDNS/DHCP/router DNS).
        if (!host.contains('.') && !host.contains(':')) return true

        parseIpv4(host)?.let { (a, b) ->
            return a == 10 ||
                a == 127 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }

        if (host.contains(':')) {
            if (host.startsWith("fc") || host.startsWith("fd")) return true // ULA fc00::/7
            if (host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb")
            ) {
                return true // link-local fe80::/10
            }
            if (host.startsWith("::ffff:")) return isPrivateHost(host.removePrefix("::ffff:"))
        }

        return false
    }

    private fun parseIpv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets[0] to octets[1]
    }
}
