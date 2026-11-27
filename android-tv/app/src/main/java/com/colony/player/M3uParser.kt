package com.colony.player

/**
 * Parses extended M3U playlists without relying on a fixed line-ending style.
 * Metadata belongs to the next media URL; comments and VLC options are ignored.
 */
object M3uParser {
    fun parse(source: String): List<MediaEntry> {
        val result = ArrayList<MediaEntry>()
        var title = ""
        var group = "General"
        var logo = ""
        val text = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val comma = line.indexOf(',')
                val attributes = if (comma >= 0) line.substring(0, comma) else line
                val displayName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                title = displayName.ifBlank { attribute(attributes, "tvg-name") }.ifBlank { "Item ${result.size + 1}" }
                group = attribute(attributes, "group-title").ifBlank { "General" }
                logo = attribute(attributes, "tvg-logo")
                continue
            }
            if (line.startsWith("#")) continue
            if (!line.startsWith("http://", true) && !line.startsWith("https://", true)) continue
            val kind = classify(group, title, line)
            result.add(MediaEntry(title.ifBlank { "Item ${result.size + 1}" }, line, group, logo, kind))
            title = ""
            group = "General"
            logo = ""
        }
        return result
    }

    private fun attribute(line: String, name: String): String {
        val match = Regex("""(?:^|\\s)${Regex.escape(name)}\\s*=\\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun classify(group: String, title: String, url: String): String {
        val value = "$group $title $url".lowercase()
        val seriesWords = listOf("series", "serial", "tv show", "season", "episode", "مسلسل", "مسلسلات", "حلقة", "موسم")
        val movieWords = listOf("movie", "movies", "vod", "film", "cinema", "أفلام", "فيلم", "سينما")
        return when {
            seriesWords.any(value::contains) -> "series"
            movieWords.any(value::contains) -> "movie"
            else -> "live"
        }
    }
}
