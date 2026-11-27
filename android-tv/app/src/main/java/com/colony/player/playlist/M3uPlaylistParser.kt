package com.colony.player.playlist

import com.colony.player.model.MediaEntry
import com.colony.player.model.MediaSection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Parser for standard extended M3U and common M3U Plus metadata.
 * Supports HTTP(S) and RTSP media URLs. HLS .m3u8 entries are passed intact
 * to Media3 so ExoPlayer can select its HLS source automatically.
 */
object M3uPlaylistParser {
    fun parse(document: String): List<MediaEntry> {
        val normalized = document.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val firstContent = normalized.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

        require(firstContent.startsWith("#EXTM3U", ignoreCase = true)) { "NOT_M3U" }

        val entries = ArrayList<MediaEntry>()
        var pending: Metadata? = null
        var pendingGroup: String? = null

        normalized.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseMetadata(line, entries.size)
                    pendingGroup = null
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().trim('"', '\'')
                }

                line.startsWith("#") -> Unit

                isSupportedMediaUrl(line) -> {
                    val metadata = pending ?: Metadata(
                        title = "Item ${entries.size + 1}",
                        group = "Uncategorized",
                        logo = null,
                        tvgType = "",
                        tvgId = ""
                    )
                    val group = pendingGroup?.takeIf { it.isNotBlank() }
                        ?: metadata.group.ifBlank { "Uncategorized" }
                    val title = metadata.title.ifBlank { "Item ${entries.size + 1}" }

                    entries += MediaEntry(
                        id = stableId(line, title, group),
                        title = title,
                        streamUrl = line,
                        groupName = group,
                        logoUrl = metadata.logo,
                        section = detectSection(metadata, group, line)
                    )
                    pending = null
                    pendingGroup = null
                }

                else -> {
                    // A non-comment, non-URL line cannot belong to the pending EXTINF.
                    pending = null
                    pendingGroup = null
                }
            }
        }

        require(entries.isNotEmpty()) { "M3U_EMPTY" }
        return entries
    }

    private data class Metadata(
        val title: String,
        val group: String,
        val logo: String?,
        val tvgType: String,
        val tvgId: String
    )

    private fun parseMetadata(line: String, index: Int): Metadata {
        val comma = line.indexOf(',')
        val attributes = if (comma >= 0) line.substring(0, comma) else line
        val displayTitle = if (comma >= 0) line.substring(comma + 1).trim() else ""

        return Metadata(
            title = displayTitle.ifBlank {
                attribute(attributes, "tvg-name").ifBlank { "Item ${index + 1}" }
            },
            group = attribute(attributes, "group-title"),
            logo = attribute(attributes, "tvg-logo").ifBlank { null },
            tvgType = attribute(attributes, "tvg-type"),
            tvgId = attribute(attributes, "tvg-id")
        )
    }

    private fun attribute(source: String, key: String): String {
        val expression = Regex(
            """(?:^|\\s)${Regex.escape(key)}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s]+))""",
            RegexOption.IGNORE_CASE
        )
        val match = expression.find(source) ?: return ""
        return (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
            .orEmpty()
            .trim()
    }

    private fun detectSection(meta: Metadata, group: String, url: String): MediaSection {
        val type = meta.tvgType.lowercase(Locale.ROOT)
        val groupAndPath = "$group $url".lowercase(Locale.ROOT)
        val seriesMarkers = listOf(
            "series", "/series/", "tv show", "season", "episode", "episod",
            "s01e", "s02e", "مسلسل", "مسلسلات", "موسم", "حلقة"
        )
        val movieMarkers = listOf(
            "movie", "/movie/", "movies", "vod", "film", "/films/",
            "cinema", "أفلام", "فيلم", "سينما"
        )

        return when {
            type in setOf("series", "show", "episode") -> MediaSection.SERIES
            type in setOf("movie", "vod", "film") -> MediaSection.MOVIE
            seriesMarkers.any(groupAndPath::contains) -> MediaSection.SERIES
            movieMarkers.any(groupAndPath::contains) -> MediaSection.MOVIE
            else -> MediaSection.LIVE
        }
    }

    private fun isSupportedMediaUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https", "rtsp") &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun stableId(url: String, title: String, group: String): String {
        val source = "$url|$title|$group"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
