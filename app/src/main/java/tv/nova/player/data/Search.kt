package tv.nova.player.data

import java.util.Locale

/** Pure, allocation-conscious channel search. Documents are built once after each catalogue load. */
object Search {
    data class Fields(val text: String, val compact: String)
    data class Document(
        val channel: Channel,
        val name: Fields,
        val group: Fields,
        val quality: Fields,
        val order: Int,
    )

    fun documents(channels: List<Channel>): List<Document> = channels.mapIndexed { index, channel ->
        Document(
            channel = channel,
            name = fields(channel.name),
            group = fields(channel.group),
            quality = fields(channel.quality.label),
            order = index,
        )
    }

    fun fields(value: String): Fields {
        val text = normalize(value)
        return Fields(text, text.filter { it != ' ' })
    }

    /** Lowercases, expands &/+, ignores common punctuation, and tokenizes on all other separators. */
    fun normalize(value: String): String {
        val out = StringBuilder(value.length)
        value.lowercase(Locale.ROOT).forEach { char ->
            when {
                char == '&' -> out.append(" and ")
                char == '+' -> out.append(" plus ")
                char == '.' || char == ',' || char == '\'' || char == '-' -> out.append(' ')
                Character.isLetterOrDigit(char) -> out.append(char)
                else -> out.append(' ')
            }
        }
        return out.toString().trim().replace(Regex("\\s+"), " ")
    }

    fun search(
        documents: List<Document>,
        query: String,
        hdOnly: Boolean = false,
    ): List<Channel> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) {
            return documents.filter { !hdOnly || it.channel.quality.rank >= Quality.HD.rank }.map { it.channel }
        }
        val queryFields = fields(normalizedQuery)
        val tokens = normalizedQuery.split(' ').filter { it.isNotEmpty() }.distinct()
        if (tokens.isEmpty()) return emptyList()
        val matches = ArrayList<Ranked>()
        for (document in documents) {
            val channel = document.channel
            if (hdOnly && channel.quality.rank < Quality.HD.rank) continue
            if (!tokens.all { token -> contains(document, token) }) continue
            val rank = when {
                document.name.text == normalizedQuery || document.name.compact == queryFields.compact -> 0
                document.name.text.startsWith(normalizedQuery) || document.name.compact.startsWith(queryFields.compact) -> 1
                tokens.all { document.name.compact.contains(it) } -> 2
                else -> 3
            }
            matches += Ranked(channel, rank, document.order, document.name.text)
        }
        matches.sortWith(compareBy(Ranked::rank, Ranked::name, Ranked::order))
        return matches.map { it.channel }
    }

    private fun contains(document: Document, token: String): Boolean =
        document.name.compact.contains(token) ||
            document.group.compact.contains(token) ||
            document.quality.compact.contains(token)

    private data class Ranked(
        val channel: Channel,
        val rank: Int,
        val order: Int,
        val name: String,
    )
}
