package com.colony.player.playlist

import com.colony.player.model.MediaEntry
import com.colony.player.model.MediaSection
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Streaming parser for standard extended M3U and common M3U Plus metadata. */
object M3uPlaylistParser {
    /** Compatibility entry point; production downloads use [parse] with an InputStream. */
    fun parse(document: String): List<MediaEntry> =
        ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)).use { parse(it) }

    /** Reads one line at a time, avoiding a second full in-memory copy of large playlists. */
    fun parse(input: InputStream): List<MediaEntry> {
        val entries = ArrayList<MediaEntry>()
        var pending: Metadata? = null
        var pendingGroup: String? = null
        var headerSeen = false

        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).use { reader ->
            while (true) {
                val raw = reader.readLine() ?: break
                val line = raw.removePrefix("\uFEFF").trim()
                if (line.isEmpty()) continue

                if (!headerSeen) {
                    headerSeen = true
                    require(line.startsWith("#EXTM3U", ignoreCase = true)) { "NOT_M3U" }
                    continue
                }

                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pending = parseMetadata(line, entries.size)
                        pendingGroup = null
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        pendingGroup = line.substringAfter(':').trim().trim('"', '\'')
                    line.startsWith("#") -> Unit
                    isSupportedMediaUrl(line) -> {
                        val metadata = pending ?: Metadata(
                            "Item ${entries.size + 1}", "Uncategorized", null, ""
                        )
                        val rawGroup = pendingGroup?.takeIf { it.isNotBlank() }
                            ?: metadata.group.ifBlank { "OTHER" }
                        val title = metadata.title.ifBlank { "Item ${entries.size + 1}" }
                        val group = normalizeGroupName(rawGroup, title)
                        entries += MediaEntry(
                            id = stableId(line, title, group),
                            title = title,
                            streamUrl = line,
                            groupName = group,
                            logoUrl = metadata.logo,
                            section = detectSection(metadata.type, group, line)
                        )
                        pending = null
                        pendingGroup = null
                    }
                    else -> {
                        pending = null
                        pendingGroup = null
                    }
                }
            }
        }

        require(headerSeen) { "NOT_M3U" }
        require(entries.isNotEmpty()) { "M3U_EMPTY" }
        return entries
    }

    private data class Metadata(
        val title: String,
        val group: String,
        val logo: String?,
        val type: String
    )

    private fun parseMetadata(line: String, index: Int): Metadata {
        val comma = line.indexOf(',')
        val attributes = if (comma >= 0) line.substring(0, comma) else line
        val displayTitle = if (comma >= 0) line.substring(comma + 1).trim() else ""
        return Metadata(
            title = displayTitle.ifBlank { attribute(attributes, "tvg-name").ifBlank { "Item ${index + 1}" } },
            group = attribute(attributes, "group-title"),
            logo = attribute(attributes, "tvg-logo").ifBlank { null },
            type = attribute(attributes, "tvg-type")
        )
    }

    private fun attribute(source: String, key: String): String {
        val expression = Regex(
            """(?:^|\s)${Regex.escape(key)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""",
            RegexOption.IGNORE_CASE
        )
        val match = expression.find(source) ?: return ""
        return (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
            .orEmpty().trim()
    }

    private fun normalizeGroupName(rawGroup: String, title: String): String {
        val value = "$rawGroup $title".lowercase(Locale.ROOT)
        return when {
            listOf("bein sports", "beinsports", "bein sport", "بي ان سبورت").any(value::contains) -> "BEIN SPORTS"
            listOf("mbc", "ام بي سي").any(value::contains) -> "MBC"
            listOf("france", "français", "francais", "tf1", "france 2", "france 3",
                "canal+", "canal plus", "m6", "c8", "tfx").any(value::contains) -> "FRANCE"
            listOf("algeria", "algérie", "algerie", "dz", "entv", "canal algérie",
                "canal algerie", "echorouk", "ennahar", "الشروق", "النهار", "الجزائر").any(value::contains) -> "ALGERIA"
            rawGroup.isBlank() -> "OTHER"
            else -> rawGroup.trim().uppercase(Locale.ROOT)
        }
    }

    private fun detectSection(typeValue: String, group: String, url: String): MediaSection {
        val type = typeValue.lowercase(Locale.ROOT)
        val groupAndPath = "$group $url".lowercase(Locale.ROOT)
        val seriesMarkers = listOf("series", "/series/", "tv show", "season", "episode", "episod",
            "s01e", "s02e", "مسلسل", "مسلسلات", "موسم", "حلقة")
        val movieMarkers = listOf("movie", "/movie/", "movies", "vod", "film", "/films/",
            "cinema", "أفلام", "فيلم", "سينما")
        return when {
            type in setOf("series", "show", "episode") -> MediaSection.SERIES
            type in setOf("movie", "vod", "film") -> MediaSection.MOVIE
            seriesMarkers.any(groupAndPath::contains) -> MediaSection.SERIES
            movieMarkers.any(groupAndPath::contains) -> MediaSection.MOVIE
            else -> MediaSection.LIVE
        }
    }

    private fun isSupportedMediaUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https", "rtsp") && !uri.host.isNullOrBlank()
    } catch (_: Exception) { false }

    private fun stableId(url: String, title: String, group: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$url|$title|$group".toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
