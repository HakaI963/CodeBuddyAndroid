package com.flux.codebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsCodecTest {

    private fun entry(url: String, title: String = "", at: Long = 1L) =
        RecentEntry(url, title, at)

    @Test
    fun encodeDecodeRoundTrips() {
        val entries = listOf(
            entry("http://192.168.1.100:7890/?folder=%2Fhome%2Fflux#/chat", "Auth flow", 42L),
            entry("http://192.168.1.100:7890/", "Home", 7L),
        )
        assertEquals(entries, RecentsCodec.decode(RecentsCodec.encode(entries)))
    }

    @Test
    fun decodeToleratesGarbage() {
        assertTrue(RecentsCodec.decode(null).isEmpty())
        assertTrue(RecentsCodec.decode("").isEmpty())
        assertTrue(RecentsCodec.decode("not json").isEmpty())
        assertTrue(RecentsCodec.decode("[{\"url\":\"\"}]").isEmpty())
    }

    @Test
    fun touchMovesToFrontWithoutDuplicating() {
        val a = entry("http://h/a", "A")
        val b = entry("http://h/b", "B")
        val result = RecentsCodec.touch(listOf(a, b), "http://h/b", "B2", now = 99L)
        assertEquals(listOf("http://h/b", "http://h/a"), result.map { it.url })
        assertEquals("B2", result.first().title)
        assertEquals(99L, result.first().updatedAt)
    }

    @Test
    fun touchKeepsExistingTitleWhenNewOneIsBlank() {
        val a = entry("http://h/a", "Kept")
        val result = RecentsCodec.touch(listOf(a), "http://h/a", null)
        assertEquals("Kept", result.single().title)
        val blank = RecentsCodec.touch(listOf(a), "http://h/a", "   ")
        assertEquals("Kept", blank.single().title)
    }

    @Test
    fun listIsBounded() {
        var entries = emptyList<RecentEntry>()
        repeat(RecentsCodec.MAX_ENTRIES + 5) { i ->
            entries = RecentsCodec.touch(entries, "http://h/$i", "T$i", now = i.toLong())
        }
        assertEquals(RecentsCodec.MAX_ENTRIES, entries.size)
        assertEquals("http://h/${RecentsCodec.MAX_ENTRIES + 4}", entries.first().url)
    }

    @Test
    fun blankUrlIsIgnored() {
        val entries = listOf(entry("http://h/a", "A"))
        assertEquals(entries, RecentsCodec.touch(entries, "   "))
    }

    @Test
    fun renameOnlyAffectsMatchingEntry() {
        val entries = listOf(entry("http://h/a", "A"), entry("http://h/b", "B"))
        val renamed = RecentsCodec.rename(entries, "http://h/b", "  New  ")
        assertEquals("New", renamed[1].title)
        assertEquals("A", renamed[0].title)
        assertEquals(entries, RecentsCodec.rename(entries, "http://h/missing", "X"))
        assertEquals(entries, RecentsCodec.rename(entries, "http://h/a", "   "))
    }

    @Test
    fun retainOriginDropsOtherServers() {
        val entries = listOf(
            entry("http://192.168.1.100:7890/a"),
            entry("http://192.168.1.100:7890/b"),
            entry("https://example.com/c"),
            entry("http://10.0.0.2:7890/d"),
        )
        val kept = RecentsCodec.retainOrigin(entries, "http://192.168.1.100:7890")
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.url.contains("192.168.1.100") })
    }

    @Test
    fun originElidesDefaultPorts() {
        assertEquals("https://example.com", RecentsCodec.originOf("https://example.com/a?b=1"))
        assertEquals("https://example.com", RecentsCodec.originOf("https://example.com:443/a"))
        assertEquals("http://example.com", RecentsCodec.originOf("http://example.com:80/a"))
        assertEquals("http://h:7890", RecentsCodec.originOf("http://h:7890/a"))
        assertEquals("http://[::1]:7890", RecentsCodec.originOf("http://[::1]:7890/"))
        assertNull(RecentsCodec.originOf("file:///etc/passwd"))
        assertNull(RecentsCodec.originOf("not a url"))
    }

    @Test
    fun labelPrefersFolderThenRouteThenHost() {
        assertEquals(
            "/home/flux",
            RecentsCodec.labelOf("http://h:7890/?folder=%2Fhome%2Fflux#/chat"),
        )
        assertEquals("#/chat/abc", RecentsCodec.labelOf("http://h:7890/#/chat/abc"))
        assertEquals("h:7890", RecentsCodec.labelOf("http://h:7890/"))
        assertEquals("/workspace", RecentsCodec.labelOf("http://h:7890/workspace"))
    }
}
