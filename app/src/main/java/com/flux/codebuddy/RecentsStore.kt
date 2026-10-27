package com.flux.codebuddy

import android.content.Context

/**
 * Persistent, bounded list of recently opened pages. Stored as JSON in the same
 * SharedPreferences file as the server configuration.
 *
 * All ordering/pruning rules live in [RecentsCodec] so they can be unit tested
 * without Android. Only URLs on the configured server origin are kept; the list
 * is a navigation aid, never a credential store.
 */
class RecentsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<RecentEntry> = RecentsCodec.decode(prefs.getString(KEY_RECENTS, null))

    fun touch(url: String, title: String? = null) {
        persist(RecentsCodec.touch(list(), url, title))
    }

    fun rename(url: String, title: String) {
        persist(RecentsCodec.rename(list(), url, title))
    }

    fun retainOrigin(origin: String) {
        persist(RecentsCodec.retainOrigin(list(), origin))
    }

    fun clear() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    private fun persist(entries: List<RecentEntry>) {
        prefs.edit().putString(KEY_RECENTS, RecentsCodec.encode(entries)).apply()
    }

    private companion object {
        const val PREFS_NAME = "codebuddy_mobile"
        const val KEY_RECENTS = "recent_entries"
    }
}
