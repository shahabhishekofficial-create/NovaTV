package tv.nova.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    @Test fun realWorldAttributesCrlfAndExtGrp() {
        val sample = """
            #EXTM3U
            #EXTINF:-1 tvg-id="star.plus" tvg-name="Star Plus" tvg-logo="https://example.invalid/logo.png" group-title="Ignored" quality="1080p",Star Plus HD
            #EXTGRP:Entertainment
            http://example.invalid/star.m3u8
            #EXTINF:-1 tvg-name="Fallback Name",
            #EXTVLCOPT:http-user-agent=Custom Agent
            #EXTVLCOPT:http-referrer=https://example.invalid/
            http://example.invalid/fallback.ts|Referer=https://override.invalid/
        """.trimIndent().replace("\n", "\r\n")
        val result = parse(sample)
        assertEquals(2, result.size)
        assertEquals("Star Plus HD", result[0].name)
        assertEquals("Entertainment", result[0].group)
        assertEquals(Quality.FHD, result[0].quality)
        assertEquals("Custom Agent", result[1].sources[0].userAgent)
        assertEquals("https://example.invalid/", result[1].sources[0].referer)
        assertEquals("Fallback Name", result[1].name)
    }

    @Test fun malformedEntriesAreSkippedWithoutLosingNextChannel() {
        val hugeUrl = "http://example.invalid/" + "x".repeat(20_000)
        val result = parse("#EXTM3U\nnot-a-url\n$hugeUrl\n#EXTINF:-1,Okay\nhttp://example.invalid/ok\n")
        assertEquals(listOf("Okay"), result.map { it.name })
    }

    @Test fun duplicateNamesBecomeFailoverSources() {
        val result = parse("""
            #EXTINF:-1,Star Plus
            http://example.invalid/dead
            #EXTINF:-1,STAR-PLUS
            http://example.invalid/live|User-Agent=TV
        """.trimIndent())
        assertEquals(1, result.size)
        assertEquals(2, result[0].sources.size)
        assertEquals("http://example.invalid/dead", result[0].url)
    }

    @Test fun tenThousandChannelsAreStreamParsed() {
        val input = buildString {
            append("#EXTM3U\n")
            repeat(10_000) { append("#EXTINF:-1 group-title=\"Group ${it % 20}\",Channel ").append(it).append('\n')
                append("http://example.invalid/").append(it).append(".m3u8\n") }
        }
        val result = parse(input)
        assertEquals(10_000, result.size)
        assertEquals("Channel 9999", result.last().name)
    }

    private fun parse(value: String) = M3uParser.parse(value.byteInputStream())
}
