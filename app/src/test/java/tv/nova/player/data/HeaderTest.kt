package tv.nova.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeaderTest {
    @Test fun pasteConventionAndPlaylistDefaultsAreApplied() {
        val source = StreamSource.parse(
            "http://example.invalid/live.m3u8|User-Agent=My TV&Referer=http://ref.invalid/",
            "Default Agent", "http://default.invalid/",
        )!!
        assertEquals("http://example.invalid/live.m3u8", source.url)
        assertEquals("My TV", source.userAgent)
        assertEquals("http://ref.invalid/", source.referer)
        val defaults = StreamSource.parse("https://example.invalid/live", "Default Agent", null)!!
        assertEquals("Default Agent", defaults.userAgent)
        assertNull(defaults.referer)
        assertNull(StreamSource.parse("file:///private/video"))
    }
}
