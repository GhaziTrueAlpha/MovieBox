package com.moviebox.android.data

import com.moviebox.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class StremioClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()

    private fun normalizeBase(addonUrl: String): String =
        addonUrl.trim().trimEnd('/').removeSuffix("/manifest.json")

    suspend fun searchCatalog(
        catalogAddonUrl: String,
        query: String,
        type: MediaType = MediaType.MOVIE
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBase(catalogAddonUrl)
            val stremioType = if (type == MediaType.SERIES) "series" else "movie"
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$base/catalog/$stremioType/top/search=$encoded.json"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val metas = JSONObject(body).optJSONArray("metas") ?: return@use emptyList()
                buildList {
                    for (i in 0 until metas.length()) {
                        val obj = metas.getJSONObject(i)
                        add(
                            MediaItem(
                                id = obj.optString("id"),
                                title = obj.optString("name", "Untitled"),
                                year = obj.optString("releaseInfo", ""),
                                type = if (obj.optString("type") == "series") MediaType.SERIES else MediaType.MOVIE,
                                posterUrl = obj.optString("poster", ""),
                                overview = obj.optString("description", ""),
                                rating = obj.optString("imdbRating", ""),
                                provider = ProviderOption.STREMIO
                            )
                        )
                    }
                }
            }
        }
    }

    /** Fetches a plain catalog (no search suffix), e.g. /catalog/movie/top.json. */
    suspend fun fetchCatalog(catalogAddonUrl: String, type: MediaType): Result<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = normalizeBase(catalogAddonUrl)
                val stremioType = if (type == MediaType.SERIES) "series" else "movie"
                val url = "$base/catalog/$stremioType/top.json"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val metas = JSONObject(body).optJSONArray("metas") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until metas.length()) {
                            val obj = metas.getJSONObject(i)
                            add(
                                MediaItem(
                                    id = obj.optString("id"),
                                    title = obj.optString("name", "Untitled"),
                                    year = obj.optString("releaseInfo", ""),
                                    type = if (obj.optString("type") == "series") MediaType.SERIES else MediaType.MOVIE,
                                    posterUrl = obj.optString("poster", ""),
                                    overview = obj.optString("description", ""),
                                    rating = obj.optString("imdbRating", ""),
                                    provider = ProviderOption.STREMIO
                                )
                            )
                        }
                    }
                }
            }
        }

    suspend fun fetchEpisodes(catalogAddonUrl: String, seriesId: String): Result<List<EpisodeItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = normalizeBase(catalogAddonUrl)
                val url = "$base/meta/series/$seriesId.json"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val videos = JSONObject(body).optJSONObject("meta")?.optJSONArray("videos")
                        ?: return@use emptyList()
                    buildList {
                        for (i in 0 until videos.length()) {
                            val v = videos.getJSONObject(i)
                            val season = v.optInt("season", 1)
                            val ep = v.optInt("episode", i + 1)
                            val id = v.optString("id").ifBlank { "$seriesId:$season:$ep" }
                            add(EpisodeItem(id = id, season = season, episode = ep, title = v.optString("title", "Episode $ep")))
                        }
                    }.sortedWith(compareBy({ it.season }, { it.episode }))
                }
            }
        }

    suspend fun fetchStreams(streamAddonUrls: List<String>, mediaId: String, type: MediaType): Result<List<StreamSource>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stremioType = if (type == MediaType.SERIES) "series" else "movie"
                coroutineScope {
                    streamAddonUrls.map { rawAddon ->
                        async {
                            val list = mutableListOf<StreamSource>()
                            val base = normalizeBase(rawAddon)
                            if (base.isBlank()) return@async emptyList()
                            val url = "$base/stream/$stremioType/$mediaId.json"
                            runCatching {
                                val request = Request.Builder().url(url).get().build()
                                client.newCall(request).execute().use { response ->
                                    if (!response.isSuccessful) return@use
                                    val body = response.body?.string().orEmpty()
                                    val streams = JSONObject(body).optJSONArray("streams") ?: return@use
                                    for (i in 0 until streams.length()) {
                                        val obj = streams.getJSONObject(i)
                                        val streamUrl = obj.optString("url")
                                        if (streamUrl.isBlank()) continue
                                        val name = obj.optString("name", "Stream ${i + 1}")
                                        val title = obj.optString("title", name)
                                        val quality = Regex("(4K|2160p|1080p|720p|480p)", RegexOption.IGNORE_CASE)
                                            .find("$name $title")?.value?.uppercase() ?: "Auto"

                                        val headers = mutableMapOf<String, String>()
                                        obj.optJSONObject("behaviorHints")
                                            ?.optJSONObject("proxyHeaders")
                                            ?.optJSONObject("request")
                                            ?.let { reqHeaders ->
                                                reqHeaders.keys().forEach { key -> headers[key] = reqHeaders.optString(key) }
                                            }
                                        list.add(
                                            StreamSource(
                                                title = "$name • $title".replace("\n", " "),
                                                url = streamUrl,
                                                quality = quality,
                                                headers = headers,
                                                provider = ProviderOption.STREMIO
                                            )
                                        )
                                    }
                                }
                            }
                            list
                        }
                    }.awaitAll().flatten()
                }
            }
        }
}
