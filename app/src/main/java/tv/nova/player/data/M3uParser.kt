package tv.nova.player.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.util.Locale

/** Streaming M3U parser. One malformed or enormous line never aborts the rest of the file. */
object M3uParser {
    private const val MAX_LINE_CHARS = 16_384
    private val attribute = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(
        input: InputStream,
        defaultUserAgent: String? = null,
        defaultReferer: String? = null,
    ): List<Channel> {
        val reader = CappedLineReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8)), MAX_LINE_CHARS)
        val channels = ArrayList<Channel>()
        val byName = HashMap<String, Int>()
        var info = ""
        var attributes = emptyMap<String, String>()
        var group = ""
        var optionUserAgent: String? = null
        var optionReferer: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            if (reader.lastLineTooLong) {
                info = ""; attributes = emptyMap(); group = ""; optionUserAgent = null; optionReferer = null
                continue
            }
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF", true) -> {
                    info = line
                    attributes = attribute.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                }
                line.startsWith("#EXTGRP", true) -> group = line.substringAfter(':', "").trim()
                line.startsWith("#EXTVLCOPT:http-user-agent", true) -> optionUserAgent = line.substringAfter('=', "").trim()
                line.startsWith("#EXTVLCOPT:http-referrer", true) -> optionReferer = line.substringAfter('=', "").trim()
                line.startsWith("#EXTVLCOPT:http-referer", true) -> optionReferer = line.substringAfter('=', "").trim()
                line.startsWith("#") || line.isEmpty() -> Unit
                else -> {
                    val parsedSource = StreamSource.parse(line, defaultUserAgent, defaultReferer)
                    val source = parsedSource?.copy(
                        userAgent = optionUserAgent ?: parsedSource.userAgent,
                        referer = optionReferer ?: parsedSource.referer,
                    )
                    if (source != null) {
                        val comma = commaOutsideQuotes(info)
                        val displayName = if (comma >= 0) info.substring(comma + 1).trim() else ""
                        val name = displayName.ifBlank { attributes["tvg-name"]?.trim().orEmpty() }
                            .ifBlank { fallbackName(source.url) }
                        val channelGroup = group.ifBlank { attributes["group-title"]?.trim().orEmpty() }.ifBlank { "Other" }
                        val quality = Quality.normalize(
                            attributes["quality"], attributes["tvg-quality"], attributes["resolution"], name,
                        )
                        val logo = attributes["tvg-logo"]?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                        val channel = Channel(name, logo, channelGroup, quality, listOf(source))
                        val key = Search.fields(name).compact
                        val existingIndex = byName[key]
                        if (existingIndex == null) {
                            byName[key] = channels.size
                            channels += channel
                        } else {
                            val existing = channels[existingIndex]
                            if (source !in existing.sources) {
                                channels[existingIndex] = existing.copy(sources = existing.sources + source)
                            }
                        }
                    }
                    info = ""; attributes = emptyMap(); group = ""; optionUserAgent = null; optionReferer = null
                }
            }
        }
        return channels
    }

    private fun commaOutsideQuotes(value: String): Int {
        var quoted = false
        value.forEachIndexed { index, char ->
            when (char) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return index
            }
        }
        return -1
    }

    private fun fallbackName(url: String): String = try {
        val parsed = URL(url)
        parsed.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { parsed.host }
    } catch (_: Exception) {
        "Unnamed channel"
    }
}

/** Buffered chunk reader with a hard per-line allocation cap and CRLF support. */
private class CappedLineReader(
    private val reader: BufferedReader,
    private val maxChars: Int,
) {
    private val buffer = CharArray(8192)
    private var position = 0
    private var limit = 0
    private var eof = false
    private var skipLf = false
    var lastLineTooLong: Boolean = false
        private set

    fun readLine(): String? {
        if (eof && position >= limit) return null
        val out = StringBuilder()
        var tooLong = false
        var readAny = false
        while (true) {
            if (position >= limit) {
                limit = reader.read(buffer)
                position = 0
                if (limit < 0) {
                    eof = true
                    lastLineTooLong = tooLong
                    return if (readAny) out.toString() else null
                }
            }
            val char = buffer[position++]
            if (skipLf) {
                skipLf = false
                if (char == '\n') continue
            }
            readAny = true
            when (char) {
                '\n' -> {
                    lastLineTooLong = tooLong
                    return out.toString()
                }
                '\r' -> {
                    skipLf = true
                    lastLineTooLong = tooLong
                    return out.toString()
                }
                else -> if (out.length < maxChars) out.append(char) else tooLong = true
            }
        }
    }
}
