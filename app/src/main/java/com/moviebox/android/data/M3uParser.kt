package com.moviebox.android.data

import com.moviebox.android.model.LiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object M3uParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun loadPlaylist(url: String): Result<List<LiveChannel>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                parse(text)
            }
        }
    }

    fun parse(content: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        val lines = content.lines()
        var currentName = "Unknown Channel"
        var currentGroup = "General"
        var currentLogo = ""

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:")) {
                currentName = line.substringAfterLast(",", "Unknown Channel").trim()
                currentGroup = Regex("""group-title="([^"]*)"""")
                    .find(line)?.groupValues?.getOrNull(1)?.ifBlank { "General" } ?: "General"
                currentLogo = Regex("""tvg-logo="([^"]*)"""")
                    .find(line)?.groupValues?.getOrNull(1).orEmpty()
            } else if (!line.startsWith("#")) {
                channels.add(LiveChannel(name = currentName, url = line, group = currentGroup, logoUrl = currentLogo))
                currentName = "Unknown Channel"
                currentGroup = "General"
                currentLogo = ""
            }
        }
        return channels
    }
}
