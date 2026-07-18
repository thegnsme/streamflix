package com.streamflixreborn.streamflix.extensions.adapter

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extensions.models.ExtensionMediaType
import com.streamflixreborn.streamflix.extensions.models.ExtensionProvider
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider

/**
 * Adapts an [ExtensionProvider] (from an extension APK) to the existing
 * [Provider] interface.
 *
 * Every overridden method wraps its extension call in [runCatching] so that
 * errors in extension code never crash the host app:
 * - Collection-returning methods return an empty list on error.
 * - Single-item methods re-throw so the ViewModel can handle empty-state UI.
 */
class ExtensionProviderAdapter(
    /** The extension provider instance loaded from the APK. Public for [ProviderRegistry] support-type detection. */
    val extensionProvider: ExtensionProvider,
    private val mapper: ExtensionModelMapper = ExtensionModelMapper(),
) : Provider {

    override val baseUrl: String get() = ""
    override val name: String get() = extensionProvider.name
    override val logo: String get() = extensionProvider.logo
    override val language: String get() = extensionProvider.language

    override suspend fun getHome(): List<Category> = runCatching {
        extensionProvider.getHome().map { mapper.toStreamflixCategory(it) }
    }.getOrDefault(emptyList())

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = runCatching {
        extensionProvider.search(query, page).map { mapper.toStreamflixItem(it) }
    }.getOrDefault(emptyList())

    override suspend fun getMovies(page: Int): List<Movie> = runCatching {
        extensionProvider.getMovies(page).map { mapper.toStreamflixMovie(it) }
    }.getOrDefault(emptyList())

    override suspend fun getTvShows(page: Int): List<TvShow> = runCatching {
        extensionProvider.getTvShows(page).map { mapper.toStreamflixTvShow(it) }
    }.getOrDefault(emptyList())

    override suspend fun getMovie(id: String): Movie = runCatching {
        mapper.toStreamflixMovie(extensionProvider.getMovieDetail(id))
    }.getOrThrow()

    override suspend fun getTvShow(id: String): TvShow = runCatching {
        mapper.toStreamflixTvShow(extensionProvider.getTvShowDetail(id))
    }.getOrThrow()

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = runCatching {
        extensionProvider.getEpisodesBySeason(seasonId).map { mapper.toStreamflixEpisode(it) }
    }.getOrDefault(emptyList())

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = runCatching {
        val mediaType = when (videoType) {
            is Video.Type.Movie -> ExtensionMediaType.MOVIE
            is Video.Type.Episode -> ExtensionMediaType.TV_SHOW
        }
        extensionProvider.getServers(id, mediaType).map { mapper.toStreamflixServer(it) }
    }.getOrDefault(emptyList())

    override suspend fun getVideo(server: Video.Server): Video = runCatching {
        val extServer = mapper.fromStreamflixServer(server)
        mapper.toStreamflixVideo(extensionProvider.getVideo(extServer))
    }.getOrThrow()

    override suspend fun getGenre(id: String, page: Int): Genre = runCatching {
        mapper.toStreamflixGenre(extensionProvider.getGenre(id, page))
    }.getOrThrow()

    override suspend fun getPeople(id: String, page: Int): People = runCatching {
        mapper.toStreamflixPeople(extensionProvider.getPeople(id, page))
    }.getOrThrow()
}
