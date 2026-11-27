package com.colony.player.model

enum class MediaSection { LIVE, MOVIE, SERIES }

data class MediaEntry(
    val id: String,
    val title: String,
    val streamUrl: String,
    val groupName: String,
    val logoUrl: String?,
    val section: MediaSection
)

data class PlaylistSource(val id: String, val name: String, val type: String, val url: String)
