package com.flux.codebuddy

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface ProbeResult {
    data class Reachable(val loginRequired: Boolean) : ProbeResult
    data class Failed(val kind: Failure, val httpCode: Int? = null) : ProbeResult

    enum class Failure {
        NOT_CODEBUDDY,
        HTTP,
        TIMEOUT,
        REFUSED,
        UNKNOWN_HOST,
        TLS,
        GENERIC,
    }
}

/**
 * Blocking reachability check. Call off the main thread.
 *
 * This deliberately does not authenticate. It only confirms that the address
 * answers and looks like the CodeBuddy web UI (or asks for auth).
 */
object ServerProbe {

    fun probe(target: ServerTarget): ProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(target.probeUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 6_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("User-Agent", "CodeBuddyMobile/1.0")
            }
            connection = conn

            val code = conn.responseCode
            if (code == 401 || code == 403) {
                return ProbeResult.Reachable(loginRequired = true)
            }
            if (code !in 200..399) {
                return ProbeResult.Failed(ProbeResult.Failure.HTTP, code)
            }

            val body = readLimited(conn.inputStream, 16 * 1024)
            if (!body.contains("CodeBuddy", ignoreCase = true)) {
                return ProbeResult.Failed(ProbeResult.Failure.NOT_CODEBUDDY)
            }
            val loginRequired = body.contains("login-btn", ignoreCase = true) ||
                body.contains("Password", ignoreCase = true)
            ProbeResult.Reachable(loginRequired = loginRequired)
        } catch (_: SocketTimeoutException) {
            ProbeResult.Failed(ProbeResult.Failure.TIMEOUT)
        } catch (_: UnknownHostException) {
            ProbeResult.Failed(ProbeResult.Failure.UNKNOWN_HOST)
        } catch (_: ConnectException) {
            ProbeResult.Failed(ProbeResult.Failure.REFUSED)
        } catch (_: SSLException) {
            ProbeResult.Failed(ProbeResult.Failure.TLS)
        } catch (_: IOException) {
            ProbeResult.Failed(ProbeResult.Failure.GENERIC)
        } catch (_: Exception) {
            ProbeResult.Failed(ProbeResult.Failure.GENERIC)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): String {
        val buffer = ByteArray(8 * 1024)
        val out = StringBuilder()
        var total = 0
        input.use { stream ->
            while (total < limit) {
                val read = stream.read(buffer, 0, minOf(buffer.size, limit - total))
                if (read <= 0) break
                out.append(String(buffer, 0, read, Charsets.UTF_8))
                total += read
            }
        }
        return out.toString()
    }
}
