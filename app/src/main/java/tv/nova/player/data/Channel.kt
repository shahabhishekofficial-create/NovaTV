package tv.nova.player.data

import java.util.Locale

/** A single playable URL plus headers scoped to that URL. */
data class StreamSource(
    val url: String,
    val userAgent: String? = null,
    val referer: String? = null,
) {
    companion object {
        /** Parses the common `url|User-Agent=…&Referer=…` convention. */
        fun parse(raw: String, defaultUserAgent: String? = null, defaultReferer: String? = null): StreamSource? {
            val pipe = raw.indexOf('|')
            val url = (if (pipe >= 0) raw.substring(0, pipe) else raw).trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            var userAgent = defaultUserAgent.cleanHeader()
            var referer = defaultReferer.cleanHeader()
            if (pipe >= 0) {
                raw.substring(pipe + 1).split('&').forEach { pair ->
                    val equals = pair.indexOf('=')
                    if (equals <= 0) return@forEach
                    val key = pair.substring(0, equals).trim()
                    val value = pair.substring(equals + 1).trim().cleanHeader()
                    when (key.lowercase(Locale.ROOT)) {
                        "user-agent", "useragent", "ua" -> userAgent = value
                        "referer", "referrer" -> referer = value
                    }
                }
            }
            return StreamSource(url, userAgent, referer)
        }
    }
}

private fun String?.cleanHeader(): String? = this
    ?.replace('\r', ' ')
    ?.replace('\n', ' ')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

enum class Quality(val label: String, val rank: Int) {
    UNKNOWN("", 0), SD("SD", 1), HD("HD", 2), FHD("FHD", 3), UHD("4K", 4);

    companion object {
        private val uhd = Regex("(?i)(?<![A-Z0-9])(UHD|4\\s?K|2160P?)(?![A-Z0-9])")
        private val fhd = Regex("(?i)(?<![A-Z0-9])(FHD|FULL[ _-]?HD|1080P?)(?![A-Z0-9])")
        private val hd = Regex("(?i)(?<![A-Z0-9])(HD|720P?)(?![A-Z0-9])")
        private val sd = Regex("(?i)(?<![A-Z0-9])(SD|480P?|360P?)(?![A-Z0-9])")

        fun normalize(vararg values: String?): Quality {
            val text = values.filterNotNull().joinToString(" ")
            return when {
                uhd.containsMatchIn(text) -> UHD
                fhd.containsMatchIn(text) -> FHD
                hd.containsMatchIn(text) || text.trim().endsWith("HD", true) -> HD
                sd.containsMatchIn(text) -> SD
                else -> UNKNOWN
            }
        }
    }
}

data class Channel(
    val name: String,
    val logoUrl: String?,
    val group: String,
    val quality: Quality,
    val sources: List<StreamSource>,
) {
    val url: String get() = sources.first().url
    val qualityLabel: String get() = quality.label

    init {
        require(sources.isNotEmpty()) { "A channel needs at least one source" }
    }
}
