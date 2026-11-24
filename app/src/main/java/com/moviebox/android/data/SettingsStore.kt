package com.moviebox.android.data

import android.content.Context
import android.content.SharedPreferences
import com.moviebox.android.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists settings, favorites and recents in SharedPreferences (JSON blobs),
 * mirroring the TUI's favorites/history/config behaviour.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("moviebox", Context.MODE_PRIVATE)

    var useExternalPlayer: Boolean
        get() = prefs.getBoolean("use_external_player", false)
        set(value) = prefs.edit().putBoolean("use_external_player", value).apply()

    var selectedTheme: AppTheme
        get() = AppTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: AppTheme.MOCHA
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var streamAddonUrl: String
        get() = prefs.getString("stream_addon_url", "").orEmpty()
        set(value) = prefs.edit().putString("stream_addon_url", value).apply()

    var playlistUrl: String
        get() = prefs.getString("playlist_url", "").orEmpty()
        set(value) = prefs.edit().putString("playlist_url", value).apply()

    fun loadFavorites(): List<MediaItem> = parseItems(prefs.getString("favorites", null))

    fun saveFavorites(items: List<MediaItem>) {
        prefs.edit().putString("favorites", toJson(items)).apply()
    }

    fun loadRecents(): List<MediaItem> = parseItems(prefs.getString("recents", null))

    fun saveRecents(items: List<MediaItem>) {
        prefs.edit().putString("recents", toJson(items)).apply()
    }

    private fun toJson(items: List<MediaItem>): String {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("title", it.title).put("year", it.year)
                    .put("type", if (it.type == MediaType.SERIES) "series" else "movie")
                    .put("poster", it.posterUrl).put("overview", it.overview).put("rating", it.rating)
                    .put("provider", it.provider.name)
            )
        }
        return arr.toString()
    }

    private fun parseItems(raw: String?): List<MediaItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val provName = o.optString("provider", "MOVIEBOX")
                    val provider = ProviderOption.entries.firstOrNull { it.name == provName } ?: ProviderOption.MOVIEBOX
                    val isSeries = o.optString("type", "movie") == "series"
                    add(
                        MediaItem(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            year = o.optString("year", ""),
                            type = if (isSeries) MediaType.SERIES else MediaType.MOVIE,
                            posterUrl = o.optString("poster", ""),
                            overview = o.optString("overview", ""),
                            rating = o.optString("rating", ""),
                            provider = provider
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
