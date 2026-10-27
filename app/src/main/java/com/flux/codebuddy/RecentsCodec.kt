package com.flux.codebuddy

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * One conversation the user has opened, so the home screen can jump straight
 * back to it instead of reloading the site root.
 */
data class RecentEntry(
    val url: String,
    val title: String,
    val updatedAt: Long,
)

/**
 * Pure-JVM storage format and list logic for [RecentEntry], kept free of
 * Android classes so the ordering and pruning rules can be unit tested.
 */
object RecentsCodec {

    const val MAX_ENTRIES = 12

    fun encode(entries: List<RecentEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put(KEY_URL, entry.url)
                    .put(KEY_TITLE, entry.title)
                    .put(KEY_UPDATED_AT, entry.updatedAt),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<RecentEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString(KEY_URL).takeIf { it.isNotBlank() } ?: continue
                    add(
                        RecentEntry(
                            url = url,
                            title = item.optString(KEY_TITLE),
                            updatedAt = item.optLong(KEY_UPDATED_AT),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Moves [url] to the front, keeping at most [MAX_ENTRIES] entries. A blank
     * [title] keeps the title that is already stored, so a route change that
     * cannot read `document.title` does not erase a good label.
     */
    fun touch(
        entries: List<RecentEntry>,
        url: String,
        title: String? = null,
        now: Long = System.currentTimeMillis(),
    ): List<RecentEntry> {
        if (url.isBlank()) return entries
        val existing = entries.firstOrNull { it.url == url }
        val resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing?.title.orEmpty()
        val result = ArrayList<RecentEntry>(minOf(entries.size + 1, MAX_ENTRIES))
        result.add(RecentEntry(url, resolvedTitle, now))
        for (entry in entries) {
            if (entry.url == url) continue
            result.add(entry)
            if (result.size >= MAX_ENTRIES) break
        }
        return result
    }

    fun rename(entries: List<RecentEntry>, url: String, title: String): List<RecentEntry> {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return entries
        val index = entries.indexOfFirst { it.url == url }
        if (index < 0) return entries
        return entries.toMutableList().also { it[index] = it[index].copy(title = trimmed) }
    }

    /** Drops entries that point at a different server after the URL changes. */
    fun retainOrigin(entries: List<RecentEntry>, origin: String): List<RecentEntry> =
        entries.filter { originOf(it.url) == origin }

    /**
     * `scheme://host[:port]` for an absolute URL, with the default port elided,
     * or null when the string is not an absolute http(s) URL.
     */
    fun originOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT) ?: return null
        if (host.isBlank()) return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        val defaultPort = if (scheme == "https") 443 else 80
        val hostForUrl = if (host.contains(':')) "[$host]" else host
        return if (port == defaultPort) "$scheme://$hostForUrl" else "$scheme://$hostForUrl:$port"
    }

    /** Human-friendly label for a URL: folder, then route, then host. */
    fun labelOf(url: String): String {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return url
        }
        val folder = queryParam(uri.rawQuery, "folder")
        if (!folder.isNullOrBlank()) return folder
        val fragment = uri.rawFragment
        if (!fragment.isNullOrBlank()) return "#$fragment"
        val path = uri.path
        if (!path.isNullOrBlank() && path != "/") return path
        // Root URL: show the authority (host:port) so two servers are
        // distinguishable in the list.
        return originOf(url)?.substringAfter("://") ?: uri.host ?: url
    }

    private fun queryParam(rawQuery: String?, key: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        for (part in rawQuery.split('&')) {
            val name = part.substringBefore('=')
            if (!name.equals(key, ignoreCase = true)) continue
            val value = part.substringAfter('=', "")
            return try {
                java.net.URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
                value
            }
        }
        return null
    }

    private const val KEY_URL = "url"
    private const val KEY_TITLE = "title"
    private const val KEY_UPDATED_AT = "updated_at"
}
