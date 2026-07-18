package com.streamflixreborn.streamflix.extensions.models

/**
 * Interface that extension APKs must implement.
 * Extension developers compile against this interface (published as an SDK artifact).
 *
 * All methods are [suspend] to support coroutine-based implementations.
 */
interface ExtensionProvider {
    val name: String
    val logo: String
    val language: String
    val supportedTypes: Set<ExtensionMediaType>

    suspend fun getHome(): List<ExtensionCategory>

    suspend fun search(query: String, page: Int): List<ExtensionSearchResult>

    suspend fun getMovies(page: Int): List<ExtensionMovie>

    suspend fun getTvShows(page: Int): List<ExtensionTvShow>

    suspend fun getMovieDetail(id: String): ExtensionMovie

    suspend fun getTvShowDetail(id: String): ExtensionTvShow

    suspend fun getEpisodesBySeason(seasonId: String): List<ExtensionEpisode>

    suspend fun getServers(id: String, type: ExtensionMediaType): List<ExtensionServer>

    suspend fun getVideo(server: ExtensionServer): ExtensionVideo

    suspend fun getGenre(id: String, page: Int): ExtensionGenre

    suspend fun getPeople(id: String, page: Int): ExtensionPeople
}
