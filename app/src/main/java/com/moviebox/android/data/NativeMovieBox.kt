package com.moviebox.android.data

import android.content.Context
import android.util.Log
import com.moviebox.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI bridge over the vendored MovieBox-TUI Rust core (libmoviebox_ffi.so).
 * Every native call returns JSON: {"ok":true,"data":...} or {"ok":false,"error":"msg"}.
 */
object NativeMovieBox {
    private const val TAG = "NativeMovieBox"
    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    external fun nativeInit(context: Context, dataDir: String)
    private external fun nativeHomepage(page: Int): String
    private external fun nativeSearch(provider: String, query: String, page: Int): String
    private external fun nativeDetails(provider: String, id: String): String
    private external fun nativeStreams(provider: String, id: String, season: Int, episode: Int): String
    private external fun nativeResolve(
        provider: String, subjectId: String, releaseJson: String, failedUrl: String
    ): String
    private external fun nativeCaptions(subjectId: String, resourceId: String): String

    fun init(context: Context, dataDir: String) {
        if (loaded) return
        try {
            System.loadLibrary("moviebox_ffi")
            nativeInit(context, dataDir)
            loaded = true
        } catch (t: Throwable) {
            loadError = t.message
            Log.e(TAG, "moviebox_ffi unavailable", t)
        }
    }

    fun isAvailable(): Boolean = loaded
    fun availabilityError(): String? = loadError

    private fun checkNative() {
        if (!loaded) error("Native engine not loaded: ${loadError ?: "libmoviebox_ffi.so missing"}")
    }

    private fun parseEnvelope(raw: String): JSONObject {
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) error(root.optString("error", "Native call failed"))
        return root
    }

    suspend fun fetchHomepage(page: Int = 1): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        runCatching {
            checkNative()
            val arr = parseEnvelope(nativeHomepage(page)).optJSONObject("data")?.optJSONArray("items")
                ?: return@runCatching emptyList()
            parseCatalogItems(arr, ProviderOption.MOVIEBOX)
        }
    }

    suspend fun search(provider: ProviderOption, query: String, page: Int = 1): Result<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                checkNative()
                val arr = parseEnvelope(nativeSearch(provider.key, query, page))
                    .optJSONObject("data")?.optJSONArray("items") ?: return@runCatching emptyList()
                parseCatalogItems(arr, provider)
            }
        }

    suspend fun fetchDetails(
        provider: ProviderOption, id: String
    ): Result<Pair<List<EpisodeItem>, List<StreamSource>>> = withContext(Dispatchers.IO) {
        runCatching {
            checkNative()
            val data = parseEnvelope(nativeDetails(provider.key, id)).optJSONObject("data")
                ?: return@runCatching (emptyList<EpisodeItem>() to emptyList())

            val episodes = mutableListOf<EpisodeItem>()
            data.optJSONArray("episodes")?.let { epArr ->
                for (i in 0 until epArr.length()) {
                    val e = epArr.optJSONObject(i) ?: continue
                    val sNum = e.optInt("season", 1)
                    val eNum = e.optInt("number", e.optInt("episode", i + 1))
                    val eTitle = firstNonBlank(e, "title").ifBlank { "Episode $eNum" }
                    episodes.add(EpisodeItem(id = "$id:$sNum:$eNum", season = sNum, episode = eNum, title = eTitle))
                }
            }
            val inlineStreams = if (data.optString("media_type", "movie") == "movie") {
                parseReleasesArray(data.optJSONArray("releases"), provider)
            } else emptyList()
            episodes.sortedWith(compareBy({ it.season }, { it.episode })) to inlineStreams
        }
    }

    suspend fun fetchStreams(
        provider: ProviderOption, id: String, season: Int, episode: Int
    ): Result<List<StreamSource>> = withContext(Dispatchers.IO) {
        runCatching {
            checkNative()
            val data = parseEnvelope(nativeStreams(provider.key, id, season, episode))
                .optJSONObject("data") ?: return@runCatching emptyList()
            parseReleasesArray(data.optJSONArray("releases"), provider)
        }
    }

    /** Resolves an indirect mirror URL to a playable direct URL via the Rust resolver. */
    suspend fun resolveStreamIfNeeded(
        stream: StreamSource, subjectId: String = "", failedUrl: String = ""
    ): StreamSource = withContext(Dispatchers.IO) {
        if (!loaded || stream.resourceJson.isBlank()) return@withContext stream

        val lower = stream.url.lowercase()
        val looksDirect = lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".m3u8") ||
            lower.contains(".mpd") || lower.contains("r2.dev") || lower.contains("workers.dev") ||
            lower.contains("cloudfront") || lower.contains("googlevideo")
        if (looksDirect) return@withContext stream

        runCatching {
            val data = parseEnvelope(nativeResolve(stream.provider.key, subjectId, stream.resourceJson, failedUrl))
                .optJSONObject("data") ?: return@runCatching stream
            val url = firstNonBlank(data, "url")
            if (url.isBlank()) return@runCatching stream
            var next = stream.copy(url = url)
            val hdrs = data.optJSONArray("headers")
            if (hdrs != null) {
                val merged = LinkedHashMap(stream.headers)
                for (i in 0 until hdrs.length()) {
                    val h = hdrs.optJSONObject(i) ?: continue
                    val k = h.optString("k", "")
                    if (k.isNotBlank()) merged[k] = h.optString("v", "")
                }
                next = next.copy(headers = merged)
            }
            next
        }.getOrDefault(stream)
    }

    suspend fun fetchCaptions(subjectId: String, resourceId: String): List<SubtitleTrack> =
        withContext(Dispatchers.IO) {
            if (!loaded || resourceId.isBlank()) return@withContext emptyList()
            runCatching {
                val data = parseEnvelope(nativeCaptions(subjectId, resourceId)).optJSONObject("data")
                    ?: return@runCatching emptyList()
                val arr = data.optJSONArray("captions") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val url = firstNonBlank(c, "url")
                        if (url.startsWith("http")) {
                            val label = firstNonBlank(c, "lanName", "lan").ifBlank { "CC" }
                            add(SubtitleTrack(lang = label.lowercase(), label = label, url = url))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    private fun parseCatalogItems(arr: JSONArray, provider: ProviderOption): List<MediaItem> = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = firstNonBlank(obj, "id")
            val title = firstNonBlank(obj, "title")
            if (id.isBlank() || title.isBlank()) continue
            val typeStr = obj.optString("type", "movie").lowercase()
            add(
                MediaItem(
                    id = id,
                    title = title,
                    year = firstNonBlank(obj, "year"),
                    type = if (typeStr == "series" || typeStr == "tv") MediaType.SERIES else MediaType.MOVIE,
                    posterUrl = firstNonBlank(obj, "poster", "poster_url"),
                    overview = firstNonBlank(obj, "overview", "description"),
                    rating = firstNonBlank(obj, "rating", "imdb_rating"),
                    provider = provider
                )
            )
        }
    }

    private fun parseReleasesArray(arr: JSONArray?, provider: ProviderOption): List<StreamSource> {
        if (arr == null) return emptyList()
        val out = mutableListOf<StreamSource>()
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            val relTitle = firstNonBlank(rel, "title", "filename", "name").ifBlank { "Stream ${i + 1}" }
            val quality = firstNonBlank(rel, "quality").ifBlank { "Auto" }
            val sizeBytes = rel.optLong("size_bytes", 0L)
            val sizeText = if (sizeBytes > 0) formatBytes(sizeBytes) else ""
            val resourceId = firstNonBlank(rel, "resource_id")
            val releaseJson = rel.toString()

            val mirrors = rel.optJSONArray("mirrors")
            if (mirrors == null || mirrors.length() == 0) continue
            for (mIdx in 0 until mirrors.length()) {
                val m = mirrors.optJSONObject(mIdx) ?: continue
                val mUrl = firstNonBlank(m, "resolver_url", "url")
                if (!mUrl.startsWith("http")) continue
                val mLabel = firstNonBlank(m, "label", "name")
                val mHeaders = mirrorHeaders(m.optJSONArray("headers")) // handles [[k,v]] pairs (raw Release) and {{"k","v"}}
                val displayTitle = if (mLabel.isNotBlank() && !relTitle.contains(mLabel, true)) {
                    "$relTitle • $mLabel"
                } else relTitle
                out.add(
                    StreamSource(
                        title = displayTitle,
                        url = mUrl,
                        quality = quality,
                        sizeText = sizeText,
                        headers = mHeaders,
                        resourceId = if (resourceId.isNotBlank() && mIdx == 0) resourceId else "",
                        resourceJson = releaseJson,
                        provider = provider
                    )
                )
            }
        }
        return out
    }

    private fun mirrorHeaders(arr: JSONArray?): Map<String, String> {
        if (arr == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            when (val e = arr.opt(i)) {
                // raw serde shape: pair arrays ["key", "value"]
                is JSONArray -> {
                    val k = e.optString(0, "")
                    if (k.isNotBlank()) map[k] = e.optString(1, "")
                }
                // normalized shape: {"k":..,"v":..}
                is JSONObject -> {
                    val k = e.optString("k", "")
                    if (k.isNotBlank()) map[k] = e.optString("v", "")
                }
                else -> {}
            }
        }
        return map
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = obj.optString(k, "").trim()
            if (v.isNotEmpty() && v != "null") return v
        }
        return ""
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0)
        else String.format("%.0f MB", mb)
    }
}
