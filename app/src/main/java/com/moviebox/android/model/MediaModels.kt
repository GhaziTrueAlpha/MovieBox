package com.moviebox.android.model

enum class ProviderOption(val key: String, val label: String) {
    MOVIEBOX("moviebox", "MovieBox"),
    FOURKHDHUB("4khdhub", "4KHDHub"),
    DRAMACHI("dramachi", "Dramachi"),
    STREMIO("stremio", "Addons")
}

enum class MediaType(val label: String) {
    MOVIE("Movie"),
    SERIES("Series")
}

enum class AppTheme(val label: String) {
    MOCHA("Catppuccin Mocha"),
    TOKYO_NIGHT("Tokyo Night"),
    NORD("Nord")
}

data class MediaItem(
    val id: String,
    val title: String,
    val year: String = "",
    val type: MediaType = MediaType.MOVIE,
    val posterUrl: String = "",
    val overview: String = "",
    val rating: String = "",
    val provider: ProviderOption = ProviderOption.MOVIEBOX
)

data class EpisodeItem(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String
)

data class StreamSource(
    val title: String,
    val url: String,
    val quality: String = "Auto",
    val sizeText: String = "",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val resourceId: String = "",
    val provider: ProviderOption = ProviderOption.MOVIEBOX
)

data class SubtitleTrack(
    val lang: String,
    val label: String,
    val url: String
)

data class LiveChannel(
    val name: String,
    val url: String,
    val group: String = "General",
    val logoUrl: String = ""
)

data class DownloadTask(
    val id: String,
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED
)

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED
}
