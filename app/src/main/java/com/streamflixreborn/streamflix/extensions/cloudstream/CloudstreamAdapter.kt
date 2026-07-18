// file: app/src/main/java/com/streamflixreborn/streamflix/extensions/cloudstream/CloudstreamAdapter.kt
package com.streamflixreborn.streamflix.extensions.cloudstream

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapts a Cloudstream 3 [MainAPI] plugin to the Streamflix [Provider] interface.
 *
 * Cloudstream plugins implement `com.lagradost.cloudstream3.MainAPI` but that
 * SDK is not available at compile time — all interaction is done through
 * [CloudstreamReflector] which wraps every call in [runCatching].
 *
 * Every method in this adapter runs on [Dispatchers.IO] and catches all
 * exceptions so a misbehaving plugin never crashes the host app.
 *
 * @param pluginInstance the CS3 plugin instance (loaded via PathClassLoader)
 * @param classLoader    the plugin's classloader (for CS3 SDK class resolution)
 * @param reflector      shared reflection helper
 * @param mapper         model mapper for CS3 → Streamflix conversion
 */
class CloudstreamAdapter(
    private val pluginInstance: Any,
    @Suppress("UNUSED_PARAMETER") private val classLoader: ClassLoader,
    private val reflector: CloudstreamReflector = CloudstreamReflector(),
    private val mapper: CloudstreamModelMapper = CloudstreamModelMapper(),
) : Provider {

    // ── Provider metadata (property-based) ────────────────────────────

    override val name: String
        get() = reflector.getStringProperty(pluginInstance, "name") ?: "Unknown"

    override val language: String
        get() = reflector.getStringProperty(pluginInstance, "lang") ?: "en"

    override val logo: String
        get() = ""

    override val baseUrl: String
        get() = reflector.getStringProperty(pluginInstance, "mainUrl") ?: ""

    // ── Home / Browse ─────────────────────────────────────────────────

    /** Fetches the plugin's main page categories via [CloudstreamReflector.getMainPage]. */
    override suspend fun getHome(): List<Category> = withContext(Dispatchers.IO) {
        runCatching {
            reflector.getMainPage(pluginInstance)
        }.getOrDefault(emptyList())
    }

    // ── Search ────────────────────────────────────────────────────────

    /**
     * Calls the CS3 [search] method via reflection.
     *
     * CS3 signature: `suspend fun search(query: String, page: Int): List<SearchResponse>`
     */
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Try non-suspend first, then suspend
                val result = try {
                    pluginInstance::class.java
                        .getMethod("search", String::class.java, Integer.TYPE)
                        .invoke(pluginInstance, query, page)
                } catch (_: NoSuchMethodException) {
                    reflector.callSuspendMethod<Any>(pluginInstance, "search", query, page)
                }
                mapper.toStreamflixSearchResults(result)
            }.getOrDefault(emptyList())
        }

    // ── Movies ────────────────────────────────────────────────────────

    /**
     * Cloudstream plugins do not have a dedicated [getMovies] method.
     * Returns empty list. Override in a subclass if the plugin exposes one.
     */
    // ponytail: CS3 has no getMovies(), empty list is the safe default
    override suspend fun getMovies(page: Int): List<Movie> = withContext(Dispatchers.IO) {
        emptyList()
    }

    /** Loads a single movie via CS3's [load] method (suspend or non-suspend). */
    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        runCatching {
            val result = try {
                pluginInstance::class.java
                    .getMethod("load", String::class.java)
                    .invoke(pluginInstance, id)
            } catch (_: NoSuchMethodException) {
                reflector.callSuspendMethod<Any>(pluginInstance, "load", id)
            }
            mapper.toStreamflixMovie(result)
        }.getOrThrow()
    }

    // ── TV Shows ──────────────────────────────────────────────────────

    /**
     * Cloudstream plugins do not have a dedicated [getTvShows] method.
     * Returns empty list. Override in a subclass if the plugin exposes one.
     */
    // ponytail: CS3 has no getTvShows(), empty list is the safe default
    override suspend fun getTvShows(page: Int): List<TvShow> = withContext(Dispatchers.IO) {
        emptyList()
    }

    /** Loads a single TV show via CS3's [load] method (suspend or non-suspend). */
    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        runCatching {
            val result = try {
                pluginInstance::class.java
                    .getMethod("load", String::class.java)
                    .invoke(pluginInstance, id)
            } catch (_: NoSuchMethodException) {
                reflector.callSuspendMethod<Any>(pluginInstance, "load", id)
            }
            mapper.toStreamflixTvShow(result)
        }.getOrThrow()
    }

    // ── Episodes ──────────────────────────────────────────────────────

    /**
     * Navigates the CS3 season/episode structure to find episodes for the given
     * season ID.
     *
     * Since CS3 season IDs are the season's `url`, we locate the season by
     * loading the parent show (via its `url` prefix — everything before `/season/`)
     * and then find the matching season.
     */
    // ponytail: naive approach — re-loads the parent show each call.
    // Optimise with a cache when profiling shows this is a bottleneck.
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Derive the show URL from the season ID (CS3 pattern: "/show/123/season/1")
                val showUrl = seasonId.substringBefore("/season/")
                    .ifEmpty { return@runCatching emptyList<Episode>() }

                val showResult = try {
                    pluginInstance::class.java
                        .getMethod("load", String::class.java)
                        .invoke(pluginInstance, showUrl)
                } catch (_: NoSuchMethodException) {
                    reflector.callSuspendMethod<Any>(pluginInstance, "load", showUrl)
                }

                val rawSeasons = reflector.getProperty<List<*>>(showResult, "seasons")
                    ?: return@runCatching emptyList<Episode>()

                rawSeasons.firstNotNullOfOrNull { seasonData ->
                    if (seasonData == null) return@firstNotNullOfOrNull null
                    val seasonUrl = reflector.getStringProperty(seasonData, "url") ?: ""
                    if (seasonUrl != seasonId) return@firstNotNullOfOrNull null
                    val rawEpisodes = reflector.getProperty<List<*>>(seasonData, "episodes")
                        ?: return@firstNotNullOfOrNull emptyList<Episode>()
                    rawEpisodes.mapNotNull { epData ->
                        mapper.toStreamflixEpisode(epData)
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

    // ── Servers / Video ───────────────────────────────────────────────

    /**
     * Fetches available video sources for a given content ID.
     *
     * The [videoType] indicates whether [id] refers to a Movie or an Episode.
     * Delegates to [CloudstreamReflector.loadLinks] which handles CS3's
     * callback-based link loading pattern.
     */
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        withContext(Dispatchers.IO) {
            runCatching {
                reflector.loadLinks(pluginInstance, id, videoType)
            }.getOrDefault(emptyList())
        }

    /**
     * Resolves a server to a playable [Video].
     *
     * Cloudstream's [ExtractorLink] objects already contain the direct video URL
     * in the `url` property, which gets stored in [Video.Server.video] during
     * [loadLinks] processing.
     */
    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        runCatching {
            // ponytail: Cloudstream extractors populate server.video during loadLinks.
            // If missing, return an empty Video and let the player surface the error.
            server.video ?: Video(source = server.src.ifEmpty { "" })
        }.getOrThrow()
    }

    // ── Genre / People ────────────────────────────────────────────────

    /**
     * Cloudstream plugins do not expose a dedicated genre endpoint.
     * Returns a placeholder Genre with empty show list.
     */
    // ponytail: CS3 has no getGenre(), return empty placeholder.
    // Wire to search(query, page) if the plugin supports genre filtering.
    override suspend fun getGenre(id: String, page: Int): Genre = withContext(Dispatchers.IO) {
        runCatching {
            Genre(id = id, name = id, shows = emptyList()).apply {
                itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
            }
        }.getOrThrow()
    }

    /**
     * Cloudstream plugins do not expose a dedicated people endpoint.
     * Returns a placeholder People with empty filmography.
     */
    // ponytail: CS3 has no getPeople(), return empty placeholder.
    override suspend fun getPeople(id: String, page: Int): People = withContext(Dispatchers.IO) {
        runCatching {
            People(id = id, name = id).apply {
                itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            }
        }.getOrThrow()
    }
}
