// file: app/src/main/java/com/streamflixreborn/streamflix/extensions/cloudstream/CloudstreamModelMapper.kt
package com.streamflixreborn.streamflix.extensions.cloudstream

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow

/**
 * Maps Cloudstream 3 plugin response objects (loaded via reflection) to
 * Streamflix domain models.
 *
 * Every method wraps access in [runCatching] and returns null or empty
 * on failure — a missing field on a CS3 object never crashes the app.
 *
 * @property reflector shared reflector used for all field reads
 */
class CloudstreamModelMapper(
    private val reflector: CloudstreamReflector = CloudstreamReflector(),
) {

    // ── Top-level mappers ─────────────────────────────────────────────

    /**
     * Converts a Cloudstream [LoadResponse] (movie) to a Streamflix [Movie].
     *
     * Expected CS3 fields:
     * - `url` → id
     * - `name` → title
     * - `plot` → overview
     * - `posterUrl` → poster
     * - `bannerUrl` → banner
     * - `rating` → rating (Double)
     * - `year` → released (Int → "YYYY-01-01")
     * - `duration` → runtime (Int, minutes)
     *
     * @param loadResponse the CS3 response object, or null
     * @return mapped Movie, or null if [loadResponse] is null
     */
    fun toStreamflixMovie(loadResponse: Any?): Movie? {
        if (loadResponse == null) return null
        return runCatching {
            val year = reflector.getProperty<Int>(loadResponse, "year")
            Movie(
                id = reflector.getStringProperty(loadResponse, "url") ?: "",
                title = reflector.getStringProperty(loadResponse, "name") ?: "",
                overview = reflector.getStringProperty(loadResponse, "plot") ?: "",
                poster = reflector.getStringProperty(loadResponse, "posterUrl") ?: "",
                banner = reflector.getStringProperty(loadResponse, "bannerUrl") ?: "",
                rating = reflector.getProperty<Double>(loadResponse, "rating"),
                released = year?.let { "$it-01-01" },
                runtime = reflector.getProperty<Int>(loadResponse, "duration"),
            ).apply {
                itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
            }
        }.getOrNull()
    }

    /**
     * Converts a Cloudstream [LoadResponse] (TV series) to a Streamflix [TvShow].
     *
     * Same field mapping as [toStreamflixMovie], plus:
     * - `seasons` → mapped to [Season] list
     *
     * @param loadResponse the CS3 response object, or null
     * @return mapped TvShow, or null if [loadResponse] is null
     */
    fun toStreamflixTvShow(loadResponse: Any?): TvShow? {
        if (loadResponse == null) return null
        return runCatching {
            val year = reflector.getProperty<Int>(loadResponse, "year")
            val rawSeasons: List<*> = reflector.getProperty<List<*>>(loadResponse, "seasons") ?: emptyList<Any>()

            TvShow(
                id = reflector.getStringProperty(loadResponse, "url") ?: "",
                title = reflector.getStringProperty(loadResponse, "name") ?: "",
                overview = reflector.getStringProperty(loadResponse, "plot") ?: "",
                poster = reflector.getStringProperty(loadResponse, "posterUrl") ?: "",
                banner = reflector.getStringProperty(loadResponse, "bannerUrl") ?: "",
                rating = reflector.getProperty<Double>(loadResponse, "rating"),
                released = year?.let { "$it-01-01" },
                runtime = reflector.getProperty<Int>(loadResponse, "duration"),
                seasons = rawSeasons.mapNotNull { seasonData ->
                    toStreamflixSeason(seasonData)
                },
            ).apply {
                itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
            }
        }.getOrNull()
    }

    /**
     * Converts a single Cloudstream [SeasonData] to a Streamflix [Season].
     *
     * Expected CS3 fields:
     * - `url` → id
     * - `name` → title
     * - `season` or `seasonNumber` → number (Int)
     * - `episodes` → list of [EpisodeData] mapped to [Episode]
     *
     * @param seasonData a CS3 season data object, or null
     * @return mapped Season, or null if [seasonData] is null
     */
    fun toStreamflixSeason(seasonData: Any?): Season? {
        if (seasonData == null) return null
        return runCatching {
            val rawEpisodes: List<*> = reflector.getProperty<List<*>>(seasonData, "episodes") ?: emptyList<Any>()
            Season(
                id = reflector.getStringProperty(seasonData, "url") ?: "",
                number = reflector.getProperty<Int>(seasonData, "season")
                    ?: reflector.getProperty<Int>(seasonData, "seasonNumber")
                    ?: 1,
                title = reflector.getStringProperty(seasonData, "name") ?: "",
                poster = reflector.getStringProperty(seasonData, "posterUrl") ?: "",
                episodes = rawEpisodes.mapNotNull { epData ->
                    toStreamflixEpisode(epData)
                },
            ).apply {
                itemType = AppAdapter.Type.SEASON_MOBILE_ITEM
            }
        }.getOrNull()
    }

    /**
     * Converts a single Cloudstream [EpisodeData] to a Streamflix [Episode].
     *
     * Expected CS3 fields:
     * - `url` → id
     * - `episode` or `episodeNumber` → number (Int)
     * - `name` → title
     * - `posterUrl` → poster
     * - `plot` → overview
     * - `airDate` → released (String)
     *
     * @param episodeData a CS3 episode data object, or null
     * @return mapped Episode, or null if [episodeData] is null
     */
    fun toStreamflixEpisode(episodeData: Any?): Episode? {
        if (episodeData == null) return null
        return runCatching {
            Episode(
                id = reflector.getStringProperty(episodeData, "url") ?: "",
                number = reflector.getProperty<Int>(episodeData, "episode")
                    ?: reflector.getProperty<Int>(episodeData, "episodeNumber")
                    ?: 0,
                title = reflector.getStringProperty(episodeData, "name") ?: "",
                poster = reflector.getStringProperty(episodeData, "posterUrl") ?: "",
                overview = reflector.getStringProperty(episodeData, "plot") ?: "",
                released = reflector.getStringProperty(episodeData, "airDate"),
            ).apply {
                itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
            }
        }.getOrNull()
    }

    // ── Collection mappers ────────────────────────────────────────────

    /**
     * Converts a Cloudstream [HomePageResponse] to a list of Streamflix [Category].
     *
     * [mainPageData] can be either:
     * - A [HomePageResponse] object with a `homePageLists` field
     * - A `List<HomePageList>` directly
     *
     * Each [HomePageList] contributes one [Category] whose `list` is populated
     * with the mapped [SearchResponse] / [LoadResponse] items.
     *
     * @param mainPageData raw CS3 homepage response (or null)
     * @param plugin       the CS3 plugin instance (used for fallback)
     * @param reflector    reflector for field access
     * @return list of mapped categories
     */
    @Suppress("UNCHECKED_CAST")
    fun toStreamflixCategories(
        mainPageData: Any?,
        plugin: Any,
        reflector: CloudstreamReflector,
    ): List<Category> {
        if (mainPageData == null) return emptyList()

        return runCatching {
            // Try as direct List<*>, or extract .homePageLists from a response object
            val pageLists: List<*> = when {
                mainPageData is List<*> -> mainPageData
                else -> reflector.getProperty<List<*>>(mainPageData, "homePageLists")
                    ?: reflector.callMethod(mainPageData, "getHomePageLists") as? List<*>
                    ?: return emptyList()
            }

            pageLists.mapNotNull { pageList ->
                if (pageList == null) return@mapNotNull null
                val name = reflector.getStringProperty(pageList, "name") ?: return@mapNotNull null
                val items: List<*> = reflector.getProperty(pageList, "list") ?: emptyList<Any>()

                Category(
                    name = name,
                    list = items.mapNotNull { item ->
                        if (item == null) return@mapNotNull null
                        val type = reflector.getProperty<Enum<*>>(item, "type")
                        val typeName = type?.name ?: ""
                        when {
                            typeName.contains("Movie") -> toStreamflixMovie(item)
                            typeName.contains("Series") || typeName.contains("Anime") ->
                                toStreamflixTvShow(item)
                            else -> toStreamflixMovie(item)
                        }
                    },
                ).apply {
                    itemType = AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Converts a Cloudstream search response to a list of Streamflix
     * [AppAdapter.Item] (either [Movie] or [TvShow]).
     *
     * The [searchResponse] is expected to be a `List<SearchResponse>` /
     * `List<LoadResponse>`. Each item's `type` field determines whether it
     * maps to a Movie or TvShow.
     *
     * @param searchResponse raw CS3 search result, or null
     * @return list of mapped items
     */
    @Suppress("UNCHECKED_CAST")
    fun toStreamflixSearchResults(searchResponse: Any?): List<AppAdapter.Item> {
        val list = when {
            searchResponse == null -> return emptyList()
            searchResponse is List<*> -> searchResponse
            else -> reflector.getProperty<List<*>>(searchResponse, "list")
                ?: return emptyList()
        }

        return list.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val type = reflector.getProperty<Enum<*>>(item, "type")
            val typeName = type?.name ?: ""
            when {
                typeName.contains("Movie") -> toStreamflixMovie(item) as AppAdapter.Item?
                typeName.contains("Series") || typeName.contains("Anime") ->
                    toStreamflixTvShow(item) as AppAdapter.Item?
                else -> toStreamflixMovie(item) as AppAdapter.Item?
            }
        }
    }
}
