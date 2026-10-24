package tv.nova.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val channels = listOf(
        channel("Star", "General", Quality.SD),
        channel("Star Plus HD", "Entertainment", Quality.HD),
        channel("StarPlus", "Entertainment", Quality.UNKNOWN),
        channel("STAR PLUS", "General", Quality.UNKNOWN),
        channel("Star Sports 1 HD", "Sports", Quality.HD),
        channel("Ten 2 HD", "Sports", Quality.HD),
        channel("Ten Sports", "Sports", Quality.SD),
        channel("News", "Star News Group", Quality.UNKNOWN),
    )
    private val documents = Search.documents(channels)

    @Test fun mandatoryQueriesReturnRelevantChannels() {
        assertTrue(names("star").containsAll(listOf("Star Plus HD", "StarPlus", "STAR PLUS")))
        assertTrue(names("plus").containsAll(listOf("Star Plus HD", "StarPlus", "STAR PLUS")))
        assertEquals(setOf("Star Plus HD", "StarPlus", "STAR PLUS"), names("star plus").toSet())
        assertTrue(names("sports").containsAll(listOf("Star Sports 1 HD", "Ten 2 HD", "Ten Sports")))
        assertTrue(names("ten 2").contains("Ten 2 HD"))
        assertTrue(names("hd").containsAll(listOf("Star Plus HD", "Star Sports 1 HD", "Ten 2 HD")))
    }

    @Test fun rankingIsExactThenPrefixThenNameThenOtherFields() {
        val result = names("star")
        assertEquals("Star", result.first())
        assertTrue(result.indexOf("Star Plus HD") < result.indexOf("News"))
        assertEquals("News", result.last())
    }

    @Test fun punctuationAndCaseAreNormalized() {
        val document = Search.documents(listOf(channel("A & B-Plus", "Kids", Quality.HD)))
        assertEquals(setOf("A & B-Plus"), Search.search(document, "a and b plus").map { it.name }.toSet())
    }

    @Test fun hdFilterRemovesUnknownAndLowerQuality() {
        val result = Search.search(documents, "star", hdOnly = true).map { it.name }
        assertTrue("Star Plus HD" in result)
        assertTrue("StarPlus" !in result)
        assertTrue("Star" !in result)
    }

    private fun names(query: String) = Search.search(documents, query).map { it.name }
    private fun channel(name: String, group: String, quality: Quality) = Channel(
        name = name, logoUrl = null, group = group, quality = quality,
        sources = listOf(StreamSource("http://example.invalid/${name.hashCode()}")),
    )
}
