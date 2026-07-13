package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MovieBlastProvider : Provider {

    private const val BASE_URL = "https://app.cloud-mb.xyz"
    override val baseUrl = BASE_URL
    override val name = "MovieBlast"
    override val logo = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/movieblast.png"
    override val language = "te"

    private const val TOKEN = "jdvhhjv255vghhgdhvfch2565656jhdcghfdf"
    private const val HMAC_SECRET = "GJ8reydarI7Jqat9rvbAJKNQ9gY4DoEQF2H5nfuI1gi"

    private val client = NetworkClient.default

    private val homeSections = listOf(
        "New HD Releases" to "api/genres/media/names/New%20HD%20Released/$TOKEN",
        "Latest • Series" to "api/media/seriesEpisodesAll/$TOKEN",
        "Latest" to "api/genres/pinned/all/$TOKEN",
        "Recently Added" to "api/genres/new/all/$TOKEN",
        "Popular • Movies" to "api/genres/popularmovies/all/$TOKEN",
        "Popular • Series" to "api/genres/popularseries/all/$TOKEN",
    )

    private suspend fun apiGet(path: String): JSONObject = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/$path"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/5.0.0-alpha.6")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response from $url")
        JSONObject(body)
    }

    private fun isSeries(raw: JSONObject): Boolean {
        val t = raw.optString("type", "").lowercase()
        val c = raw.optString("content_type", "").lowercase()
        return t in listOf("series", "serie", "tv", "show") || c == "series"
    }

    private fun itemToMovie(raw: JSONObject): Movie? {
        val id = raw.optString("id")
        if (id.isBlank()) return null
        val releaseDate = raw.optString("release_date", "")
        return Movie(
            id = "mb_movie_$id",
            title = raw.optString("name", raw.optString("title", "Unknown")),
            poster = raw.optString("poster_path", ""),
            released = releaseDate.ifBlank { null },
            rating = if (raw.has("vote_average") && !raw.isNull("vote_average")) raw.optDouble("vote_average") else null,
            overview = raw.optString("overview", "").ifBlank { null },
        )
    }

    private fun itemToTvShow(raw: JSONObject): TvShow? {
        val id = raw.optString("id")
        if (id.isBlank()) return null
        val releaseDate = raw.optString("release_date", "")
        return TvShow(
            id = "mb_tv_$id",
            title = raw.optString("name", raw.optString("title", "Unknown")),
            poster = raw.optString("poster_path", ""),
            released = releaseDate.ifBlank { null },
            rating = if (raw.has("vote_average") && !raw.isNull("vote_average")) raw.optDouble("vote_average") else null,
            overview = raw.optString("overview", "").ifBlank { null },
        )
    }

    private fun parseItem(raw: JSONObject): AppAdapter.Item? {
        return if (isSeries(raw)) itemToTvShow(raw) else itemToMovie(raw)
    }

    private suspend fun fetchSectionItems(path: String, maxPages: Int = 3): List<AppAdapter.Item> {
        val allItems = mutableListOf<AppAdapter.Item>()
        for (page in 1..maxPages) {
            try {
                val json = apiGet("$path?page=$page")
                val data = json.optJSONArray("data") ?: break
                if (data.length() == 0) break
                for (i in 0 until data.length()) {
                    val item = parseItem(data.getJSONObject(i)) ?: continue
                    allItems.add(item)
                }
            } catch (_: Exception) { break }
        }
        // ponytail: simple toString dedup since Movie/TvShow have unique IDs
        return allItems.distinctBy { it.toString() }
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        // ponytail: max 3 pages per section to keep it fast
        homeSections.map { (name, path) ->
            async {
                val items = fetchSectionItems(path)
                name to items
            }
        }.mapNotNull { deferred ->
            val (name, items) = deferred.await()
            if (items.isNotEmpty()) Category(name = name, list = items) else null
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return try {
            val safeQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val json = apiGet("api/search/$safeQuery/$TOKEN")
            val arr = json.optJSONArray("search")
                ?: json.optJSONArray("data")
                ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                parseItem(arr.getJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val json = apiGet("api/genres/popularmovies/all/$TOKEN?page=$page")
            val data = json.optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i ->
                itemToMovie(data.getJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val json = apiGet("api/genres/popularseries/all/$TOKEN?page=$page")
            val data = json.optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i ->
                itemToTvShow(data.getJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovie(id: String): Movie {
        val rawId = id.removePrefix("mb_movie_")
        val json = apiGet("api/media/detail/$rawId/$TOKEN")
        val videos = json.optJSONArray("videos") ?: JSONArray()
        val videoLinks = mutableListOf<String>()
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            videoLinks.add(v.optString("link", ""))
        }
        val releaseDate = json.optString("release_date", "")
        return Movie(
            id = id,
            title = json.optString("name", json.optString("title", "Unknown")),
            overview = json.optString("overview", "").ifBlank { null },
            poster = json.optString("poster_path", ""),
            banner = json.optString("backdrop_path", ""),
            released = releaseDate.ifBlank { null },
            rating = if (json.has("vote_average") && !json.isNull("vote_average")) json.optDouble("vote_average") else null,
            cast = parseCast(json.optJSONArray("casterslist")),
            // ponytail: store video links as JSON in imdbId for getServers
            imdbId = JSONArray(videoLinks).toString(),
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val rawId = id.removePrefix("mb_tv_")
        val json = apiGet("api/series/show/$rawId/$TOKEN")
        val seasonsJson = json.optJSONArray("seasons") ?: JSONArray()
        val seasons = (0 until seasonsJson.length()).map { si ->
            val s = seasonsJson.getJSONObject(si)
            val epJson = s.optJSONArray("episodes") ?: JSONArray()
            val episodes = (0 until epJson.length()).map { ei ->
                val e = epJson.getJSONObject(ei)
                val videos = e.optJSONArray("videos") ?: JSONArray()
                val linksArray = JSONArray()
                for (vi in 0 until videos.length()) {
                    linksArray.put(videos.getJSONObject(vi))
                }
                Episode(
                    id = "mb_ep_$rawId:${s.optInt("season_number", 1)}:${e.optInt("episode_number", ei + 1)}:" +
                        java.net.URLEncoder.encode(linksArray.toString(), "UTF-8"),
                    number = e.optInt("episode_number", ei + 1),
                    title = e.optString("name", "Episode ${e.optInt("episode_number", ei + 1)}"),
                    poster = e.optString("still_path_tv", e.optString("still_path", json.optString("poster_path", ""))),
                    overview = e.optString("overview", "").ifBlank { null },
                )
            }
            Season(
                id = "mb_season_${rawId}_${s.optInt("season_number", si + 1)}",
                number = s.optInt("season_number", si + 1),
                episodes = episodes,
            )
        }
        val releaseDate = json.optString("first_air_date", json.optString("release_date", ""))
        return TvShow(
            id = id,
            title = json.optString("name", json.optString("title", "Unknown")),
            overview = json.optString("overview", "").ifBlank { null },
            poster = json.optString("poster_path", ""),
            banner = json.optString("backdrop_path_tv", json.optString("backdrop_path", "")),
            released = releaseDate.ifBlank { null },
            rating = if (json.has("vote_average") && !json.isNull("vote_average")) json.optDouble("vote_average") else null,
            cast = parseCast(json.optJSONArray("casterslist")),
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // seasonId format: mb_season_{rawSeriesId}_{seasonNum}
        return try {
            val parts = seasonId.removePrefix("mb_season_").split("_", limit = 2)
            if (parts.size < 2) return emptyList()
            val seriesId = parts[0]
            val seasonNum = parts[1].toIntOrNull() ?: return emptyList()
            val json = apiGet("api/series/show/$seriesId/$TOKEN")
            val seasons = json.optJSONArray("seasons") ?: return emptyList()
            for (si in 0 until seasons.length()) {
                val s = seasons.getJSONObject(si)
                if (s.optInt("season_number", 0) == seasonNum) {
                    val epJson = s.optJSONArray("episodes") ?: return emptyList()
                    return (0 until epJson.length()).map { ei ->
                        val e = epJson.getJSONObject(ei)
                        val videos = e.optJSONArray("videos") ?: JSONArray()
                        val linksArray = JSONArray()
                        for (vi in 0 until videos.length()) {
                            linksArray.put(videos.getJSONObject(vi))
                        }
                        Episode(
                            id = "mb_ep_${seriesId}:${seasonNum}:${e.optInt("episode_number", ei + 1)}:" +
                                java.net.URLEncoder.encode(linksArray.toString(), "UTF-8"),
                            number = e.optInt("episode_number", ei + 1),
                            title = e.optString("name", "Episode ${e.optInt("episode_number", ei + 1)}"),
                            poster = e.optString("still_path_tv", e.optString("still_path", "")),
                            overview = e.optString("overview", "").ifBlank { null },
                        )
                    }
                }
            }
            emptyList()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val videoLinks = when (videoType) {
                is Video.Type.Movie -> {
                    // Re-fetch movie detail to get video links
                    val rawId = id.removePrefix("mb_movie_")
                    val json = apiGet("api/media/detail/$rawId/$TOKEN")
                    val videos = json.optJSONArray("videos") ?: JSONArray()
                    (0 until videos.length()).map { i ->
                        val v = videos.getJSONObject(i)
                        Triple(v.optString("link", ""), v.optString("server", "MovieBlast"), v.optString("lang", ""))
                    }
                }
                is Video.Type.Episode -> {
                    // Episode ID format: mb_ep_seriesId:seasonNum:epNum:encodedVideoLinksJson
                    val parts = id.split(":", limit = 4)
                    if (parts.size >= 4) {
                        val encodedJson = parts[3]
                        val decoded = java.net.URLDecoder.decode(encodedJson, "UTF-8")
                        val arr = JSONArray(decoded)
                        (0 until arr.length()).mapNotNull { i ->
                            try {
                                val obj = arr.getJSONObject(i)
                                Triple(obj.optString("link", ""), obj.optString("server", "MovieBlast"), obj.optString("lang", ""))
                            } catch (_: Exception) { null }
                        }
                    } else emptyList()
                }
            }
            videoLinks.map { (link, server, lang) ->
                Video.Server(
                    id = link,
                    name = createServerLabel(server, lang, link),
                    src = link,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val rawLink = server.id.ifBlank { server.src }
        val signedUrl = generateSignedUrl(rawLink)
        val headers = mapOf(
            "Accept-Encoding" to "identity",
            "Connection" to "Keep-Alive",
            "Referer" to "MovieBlast",
            "User-Agent" to "MovieBlast",
            "x-request-x" to "com.movieblast",
        )
        return Video(
            source = signedUrl,
            headers = headers,
        )
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        // ponytail: MovieBlast doesn't have genre endpoints, return empty
        return Genre(id = id, name = id)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        // ponytail: MovieBlast doesn't have people endpoints, skip
        throw Exception("Not supported")
    }

    private fun generateSignedUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl
        val url = if (!rawUrl.startsWith("http")) "https://$rawUrl" else rawUrl
        return try {
            val parsedUrl = URL(url)
            val path = parsedUrl.path.ifBlank { "/" }
            val ts = (System.currentTimeMillis() / 1000).toString()
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val sig = mac.doFinal("$path$ts".toByteArray(Charsets.UTF_8))
            val b64 = java.net.URLEncoder.encode(Base64.encodeToString(sig, Base64.NO_WRAP), "UTF-8")
            "$url?verify=$ts-$b64"
        } catch (_: Exception) { url }
    }

    private fun createServerLabel(server: String, lang: String, streamUrl: String): String {
        val parts = mutableListOf<String>()
        if (server.isNotBlank()) parts.add(server)
        if (lang.isNotBlank()) parts.add(lang)
        val quality = matchQuality(server, streamUrl)
        if (quality != "Auto") parts.add(quality)
        return parts.joinToString(" • ").ifEmpty { "MovieBlast" }
    }

    private fun matchQuality(serverLabel: String, streamUrl: String): String {
        val text = "${streamUrl.lowercase()} ${serverLabel.lowercase()}"
        return when {
            "2160" in text || "4k" in text -> "4K"
            "1440" in text -> "1440p"
            "1080" in text || "fullhd" in text -> "1080p"
            "720" in text || "hd" in text -> "720p"
            "480" in text -> "480p"
            "360" in text -> "360p"
            else -> {
                val resMatch = Regex("[_\\-.]?(\\d{3,4})p?[_\\-.]?").find(text)
                resMatch?.groupValues?.getOrNull(1)?.let { "${it}p" } ?: "Auto"
            }
        }
    }

    private fun parseCast(casterslist: JSONArray?): List<People> {
        if (casterslist == null) return emptyList()
        return (0 until casterslist.length()).mapNotNull { i ->
            try {
                val c = casterslist.getJSONObject(i)
                val name = c.optString("original_name", "").ifBlank { return@mapNotNull null }
                People(
                    id = name,
                    name = name,
                    image = c.optString("profile_path", "").ifBlank { null },
                )
            } catch (_: Exception) { null }
        }
    }
}
