package com.colony.player.playlist

import com.colony.player.model.MediaEntry
import com.colony.player.model.MediaSection
import java.security.MessageDigest

/**
 * Independent M3U/M3U Plus parser.
 * EXTINF metadata is attached to the next HTTP media URI; blank lines and
 * playlist directives are ignored. Non-M3U documents are rejected explicitly.
 */
object M3uPlaylistParser {
    fun parse(document: String): List<MediaEntry> {
        val normalized = document.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val firstContent = normalized.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (!firstContent.startsWith("#EXTM3U", ignoreCase = true)) {
            throw IllegalArgumentException("NOT_M3U")
        }

        val entries = ArrayList<MediaEntry>()
        var pending: Metadata? = null
        normalized.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseMetadata(line, entries.size)
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> Unit
                line.startsWith("#") -> Unit
                isHttpUrl(line) -> {
                    val metadata = pending ?: Metadata("Channel ${entries.size + 1}", "Uncategorized", null, "")
                    entries += MediaEntry(
                        id = stableId(line),
                        title = metadata.title.ifBlank { "Item ${entries.size + 1}" },
                        streamUrl = line,
                        groupName = metadata.group.ifBlank { "Uncategorized" },
                        logoUrl = metadata.logo,
                        section = detectSection(metadata, line)
                    )
                    pending = null
                }
                else -> pending = null
            }
        }
        if (entries.isEmpty()) throw IllegalArgumentException("M3U_EMPTY")
        return entries
    }

    private data class Metadata(
        val title: String,
        val group: String,
        val logo: String?,
        val extra: String
    )

    private fun parseMetadata(line: String, index: Int): Metadata {
        val comma = line.indexOf(',')
        val attributes = if (comma >= 0) line.substring(0, comma) else line
        val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
        return Metadata(
            title = title.ifBlank { attribute(attributes, "tvg-name").ifBlank { "Item ${index + 1}" } },
            group = attribute(attributes, "group-title").ifBlank { "Uncategorized" },
            logo = attribute(attributes, "tvg-logo").ifBlank { null },
            extra = listOf(
                attribute(attributes, "tvg-type"),
                attribute(attributes, "tvg-id"),
                attribute(attributes, "tvg-name")
            ).joinToString(" ")
        )
    }

    private fun attribute(source: String, key: String): String {
        val expression = Regex("""(?:^|\\s)${Regex.escape(key)}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s]+))""", RegexOption.IGNORE_CASE)
        val match = expression.find(source) ?: return ""
        return (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value).orEmpty().trim()
    }

    private fun detectSection(meta: Metadata, url: String): MediaSection {
        val value = "${meta.group} ${meta.title} ${meta.extra} $url".lowercase()
        val series = listOf("series", "tv show", "season", "episode", "episod", "مسلسل", "مسلسلات", "موسم", "حلقة")
        val movies = listOf("movie", "movies", "vod", "film", "cinema", "أفلام", "فيلم", "سينما")
        return when {
            series.any(value::contains) -> MediaSection.SERIES
            movies.any(value::contains) -> MediaSection.MOVIE
            else -> MediaSection.LIVE
        }
    }

    private fun isHttpUrl(value: String) =
        value.startsWith("https://", true) || value.startsWith("http://", true)

    private fun stableId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
