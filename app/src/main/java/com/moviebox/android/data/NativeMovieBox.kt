package com.moviebox.android.data

import com.moviebox.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object NativeMovieBox {
    init {
        System.loadLibrary("moviebox_ffi")
    }

    private external fun nativeHomepage(page: Int): String
    private external fun nativeSearch(provider: String, query: String, page: Int): String
    private external fun nativeDetails(provider: String, id: String): String
    private external fun nativeStreams(provider: String, id: String, season: Int, episode: Int): String
    private external fun nativeResolveUrl(provider: String, url: String): String
    private external fun nativeCaptions(subjectId: String, resourceId: String): String

    suspend fun fetchHomepage(page: Int = 1): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = nativeHomepage(page)
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) error(root.optString("error", "Homepage error"))
            val data = root.opt("data") ?: return@runCatching emptyList()
            val results = mutableListOf<MediaItem>()
            val seen = mutableSetOf<String>()
            collectHomepageSubjects(data, results, seen)
            results
        }
    }

    private fun collectHomepageSubjects(node: Any?, out: MutableList<MediaItem>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val sid = node.optString("subjectId").ifBlank { node.optString("id") }
                val title = node.optString("title")
                val coverObj = node.optJSONObject("cover")
                val poster = coverObj?.optString("url").orEmpty().ifBlank { node.optString("poster") }
                if (sid.isNotBlank() && title.isNotBlank() && poster.startsWith("http") && seen.add(sid)) {
                    val stype = node.optInt("subjectType", 1)
                    out.add(
                        MediaItem(
                            id = sid,
                            title = title,
                            year = node.optString("releaseDate", "").take(4),
                            type = if (stype == 2) MediaType.SERIES else MediaType.MOVIE,
                            posterUrl = poster,
                            overview = node.optString("description", ""),
                            rating = node.optString("imdbRatingValue", ""),
                            provider = ProviderOption.MOVIEBOX
                        )
                    )
                }
                node.keys().forEach { k -> collectHomepageSubjects(node.opt(k), out, seen) }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectHomepageSubjects(node.opt(i), out, seen)
                }
            }
        }
    }

    suspend fun search(provider: ProviderOption, query: String, page: Int = 1): Result<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = nativeSearch(provider.key, query, page)
                val root = JSONObject(raw)
                if (!root.optBoolean("ok", false)) error(root.optString("error", "Search failed"))
                val arr = root.optJSONArray("data") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = firstNonBlank(obj, "id", "subject_id", "subjectId", "url")
                        val title = firstNonBlank(obj, "title", "name")
                        if (id.isBlank() || title.isBlank()) continue
                        val typeStr = firstNonBlank(obj, "media_type", "kind", "type", "subject_type").lowercase()
                        val isSeries = typeStr.contains("series") || typeStr.contains("tv") ||
                            typeStr.contains("show") || obj.optInt("subject_type", 1) == 2
                        add(
                            MediaItem(
                                id = id,
                                title = title,
                                year = firstNonBlank(obj, "year", "release_year", "release_date").take(4),
                                type = if (isSeries) MediaType.SERIES else MediaType.MOVIE,
                                posterUrl = firstNonBlank(obj, "poster_url", "poster", "cover", "image"),
                                overview = firstNonBlank(obj, "overview", "description", "synopsis"),
                                rating = firstNonBlank(obj, "rating", "imdb_rating", "score"),
                                provider = provider
                            )
                        )
                    }
                }
            }
        }

    suspend fun fetchDetails(provider: ProviderOption, id: String): Result<Pair<List<EpisodeItem>, List<StreamSource>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = nativeDetails(provider.key, id)
                val root = JSONObject(raw)
                if (!root.optBoolean("ok", false)) error(root.optString("error", "Details failed"))
                val data = root.optJSONObject("data") ?: return@runCatching (emptyList<EpisodeItem>() to emptyList())

                val episodes = mutableListOf<EpisodeItem>()
                data.optJSONArray("seasons")?.let { seasons ->
                    for (i in 0 until seasons.length()) {
                        val sObj = seasons.optJSONObject(i) ?: continue
                        val sNum = sObj.optInt("season_number", sObj.optInt("season", sObj.optInt("se", i + 1)))
                        val epArr = sObj.optJSONArray("episodes")
                        if (epArr != null && epArr.length() > 0) {
                            for (j in 0 until epArr.length()) {
                                val eObj = epArr.optJSONObject(j)
                                if (eObj != null) {
                                    val eNum = eObj.optInt("episode_number", eObj.optInt("episode", eObj.optInt("ep", j + 1)))
                                    val eTitle = firstNonBlank(eObj, "title", "name").ifBlank { "Episode $eNum" }
                                    val eId = firstNonBlank(eObj, "id").ifBlank { "$id:$sNum:$eNum" }
                                    episodes.add(EpisodeItem(id = eId, season = sNum, episode = eNum, title = eTitle))
                                } else {
                                    val eNum = epArr.optInt(j, j + 1)
                                    episodes.add(EpisodeItem(id = "$id:$sNum:$eNum", season = sNum, episode = eNum, title = "Episode $eNum"))
                                }
                            }
                        } else {
                            val epCount = sObj.optInt("episode_count", sObj.optInt("max_ep", sObj.optInt("maxEp", 0)))
                            for (eNum in 1..epCount) {
                                episodes.add(EpisodeItem(id = "$id:$sNum:$eNum", season = sNum, episode = eNum, title = "Episode $eNum"))
                            }
                        }
                    }
                }

                // Some providers attach episodes flatly
                if (episodes.isEmpty()) {
                    data.optJSONArray("episodes")?.let { epArr ->
                        for (j in 0 until epArr.length()) {
                            val eObj = epArr.optJSONObject(j) ?: continue
                            val sNum = eObj.optInt("season", eObj.optInt("season_number", 1))
                            val eNum = eObj.optInt("episode", eObj.optInt("episode_number", j + 1))
                            val eTitle = firstNonBlank(eObj, "title", "name").ifBlank { "Episode $eNum" }
                            val eId = firstNonBlank(eObj, "id").ifBlank { "$id:$sNum:$eNum" }
                            episodes.add(EpisodeItem(id = eId, season = sNum, episode = eNum, title = eTitle))
                        }
                    }
                }

                val inlineStreams = parseReleasesArray(data.optJSONArray("releases"), provider)
                episodes.sortedWith(compareBy({ it.season }, { it.episode })) to inlineStreams
            }
        }

    suspend fun fetchStreams(
        provider: ProviderOption,
        id: String,
        season: Int,
        episode: Int
    ): Result<List<StreamSource>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = nativeStreams(provider.key, id, season, episode)
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) error(root.optString("error", "No streams available"))
            val arr = root.optJSONArray("data") ?: return@runCatching emptyList()
            parseReleasesArray(arr, provider)
        }
    }

    suspend fun fetchCaptions(subjectId: String, resourceId: String): List<SubtitleTrack> =
        withContext(Dispatchers.IO) {
            if (resourceId.isBlank()) return@withContext emptyList()
            runCatching {
                val raw = nativeCaptions(subjectId, resourceId)
                val root = JSONObject(raw)
                if (!root.optBoolean("ok", false)) return@runCatching emptyList()
                val data = root.optJSONObject("data") ?: return@runCatching emptyList()
                val arr = data.optJSONArray("captions") ?: data.optJSONArray("list") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val url = firstNonBlank(c, "url", "subUrl")
                        if (url.startsWith("http")) {
                            val lang = firstNonBlank(c, "lan", "lang", "language").ifBlank { "en" }
                            val label = firstNonBlank(c, "lanName", "label", "name").ifBlank { lang }
                            add(SubtitleTrack(lang = lang, label = label, url = url))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    suspend fun resolveStreamIfNeeded(stream: StreamSource): StreamSource = withContext(Dispatchers.IO) {
        if (stream.provider != ProviderOption.FOURKHDHUB) return@withContext stream
        val lower = stream.url.lowercase()
        val looksDirect = lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".m3u8") ||
            lower.contains(".mpd") || lower.contains("r2.dev") || lower.contains("workers.dev")
        if (looksDirect) return@withContext stream

        runCatching {
            val raw = nativeResolveUrl(stream.provider.key, stream.url)
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return@runCatching stream
            val data = root.opt("data")
            val resolvedUrl = when (data) {
                is String -> data
                is JSONObject -> firstNonBlank(data, "url", "direct_url", "stream_url")
                else -> ""
            }
            if (resolvedUrl.startsWith("http")) stream.copy(url = resolvedUrl) else stream
        }.getOrDefault(stream)
    }

    private fun parseReleasesArray(arr: JSONArray?, provider: ProviderOption): List<StreamSource> {
        if (arr == null) return emptyList()
        val out = mutableListOf<StreamSource>()
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            val relTitle = firstNonBlank(rel, "title", "name", "label").ifBlank { "Stream ${i + 1}" }
            val quality = firstNonBlank(rel, "quality", "resolution").ifBlank {
                Regex("(4K|2160p|1080p|720p|480p|360p)", RegexOption.IGNORE_CASE)
                    .find(relTitle)?.value?.uppercase() ?: "Auto"
            }
            val sizeBytes = rel.optLong("size_bytes", 0L)
            val sizeText = if (sizeBytes > 0) formatBytes(sizeBytes) else firstNonBlank(rel, "size", "size_label")
            val resourceId = firstNonBlank(rel, "resource_id", "resourceId")

            val baseHeaders = extractHeaders(rel.optJSONObject("headers"))
            val subs = extractSubtitles(rel.optJSONArray("subtitles"))

            val mirrors = rel.optJSONArray("mirrors")
            if (mirrors != null && mirrors.length() > 0) {
                for (mIdx in 0 until mirrors.length()) {
                    val m = mirrors.optJSONObject(mIdx) ?: continue
                    val mUrl = firstNonBlank(m, "url", "direct_url", "link")
                    if (!mUrl.startsWith("http")) continue
                    val mLabel = firstNonBlank(m, "label", "name", "host", "source")
                    val mHeaders = baseHeaders + extractHeaders(m.optJSONObject("headers"))
                    val displayTitle = if (mLabel.isNotBlank() && !relTitle.contains(mLabel, true)) {
                        "$relTitle • $mLabel"
                    } else {
                        relTitle
                    }
                    out.add(
                        StreamSource(
                            title = displayTitle,
                            url = mUrl,
                            quality = quality,
                            sizeText = sizeText,
                            headers = mHeaders,
                            subtitles = subs,
                            resourceId = resourceId,
                            provider = provider
                        )
                    )
                }
            } else {
                val directUrl = firstNonBlank(rel, "url", "direct_url", "stream_url")
                if (directUrl.startsWith("http")) {
                    out.add(
                        StreamSource(
                            title = relTitle,
                            url = directUrl,
                            quality = quality,
                            sizeText = sizeText,
                            headers = baseHeaders,
                            subtitles = subs,
                            resourceId = resourceId,
                            provider = provider
                        )
                    )
                }
            }
        }
        return out
    }

    private fun extractHeaders(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { k ->
            val v = obj.optString(k)
            if (v.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun extractSubtitles(arr: JSONArray?): List<SubtitleTrack> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = firstNonBlank(s, "url")
                if (url.startsWith("http")) {
                    val lang = firstNonBlank(s, "lang", "language", "lan").ifBlank { "en" }
                    val label = firstNonBlank(s, "label", "name").ifBlank { lang }
                    add(SubtitleTrack(lang = lang, label = label, url = url))
                }
            }
        }
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
