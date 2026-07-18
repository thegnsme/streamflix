package com.streamflixreborn.streamflix.extensions.adapter

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extensions.models.ExtensionCategory
import com.streamflixreborn.streamflix.extensions.models.ExtensionEpisode
import com.streamflixreborn.streamflix.extensions.models.ExtensionGenre
import com.streamflixreborn.streamflix.extensions.models.ExtensionMovie
import com.streamflixreborn.streamflix.extensions.models.ExtensionPeople
import com.streamflixreborn.streamflix.extensions.models.ExtensionSearchResult
import com.streamflixreborn.streamflix.extensions.models.ExtensionSeason
import com.streamflixreborn.streamflix.extensions.models.ExtensionServer
import com.streamflixreborn.streamflix.extensions.models.ExtensionTvShow
import com.streamflixreborn.streamflix.extensions.models.ExtensionVideo
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video

/**
 * Maps extension data classes (ExtensionMovie, ExtensionTvShow, etc.)
 * to the Streamflix model classes (Movie, TvShow, etc.).
 *
 * All methods are synchronous — no coroutine or I/O work.
 */
class ExtensionModelMapper {

    fun toStreamflixCategory(ext: ExtensionCategory): Category = Category(
        name = ext.name,
        list = ext.items.map { toStreamflixItem(it) },
    ).apply {
        itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
    }

    fun toStreamflixItem(result: ExtensionSearchResult): AppAdapter.Item = when (result) {
        is ExtensionMovie -> toStreamflixMovie(result)
        is ExtensionTvShow -> toStreamflixTvShow(result)
    }

    fun toStreamflixMovie(ext: ExtensionMovie): Movie = Movie(
        id = ext.id,
        title = ext.title,
        overview = ext.overview,
        released = ext.released,
        runtime = ext.runtime,
        trailer = ext.trailer,
        quality = ext.quality,
        rating = ext.rating,
        poster = ext.poster,
        banner = ext.banner,
        imdbId = ext.imdbId,
        providerName = null,
        genres = ext.genres.map { Genre(it.id, it.name) },
        directors = ext.directors.map { People(it.id, it.name, it.image) },
        cast = ext.cast.map { People(it.id, it.name, it.image) },
        recommendations = ext.recommendations.map { toStreamflixItem(it) as Show },
        isFavorite = false,
    ).apply {
        // ponytail: set default type; caller overrides per context (grid, swiper, detail)
        itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
    }

    fun toStreamflixTvShow(ext: ExtensionTvShow): TvShow = TvShow(
        id = ext.id,
        title = ext.title,
        overview = ext.overview,
        released = ext.released,
        runtime = ext.runtime,
        trailer = ext.trailer,
        quality = ext.quality,
        rating = ext.rating,
        poster = ext.poster,
        banner = ext.banner,
        imdbId = ext.imdbId,
        providerName = null,
        seasons = ext.seasons.map { toStreamflixSeason(it) },
        genres = ext.genres.map { Genre(it.id, it.name) },
        directors = ext.directors.map { People(it.id, it.name, it.image) },
        cast = ext.cast.map { People(it.id, it.name, it.image) },
        recommendations = ext.recommendations.map { toStreamflixItem(it) as Show },
        isFavorite = false,
    ).apply {
        // ponytail: set default type; caller overrides per context
        itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
    }

    fun toStreamflixEpisode(ext: ExtensionEpisode): Episode = Episode(
        id = ext.id,
        number = ext.number,
        title = ext.title,
        released = ext.released,
        poster = ext.poster,
        overview = ext.overview,
        tvShow = null,
        season = null,
    ).apply {
        itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
    }

    fun toStreamflixSeason(ext: ExtensionSeason): Season = Season(
        id = ext.id,
        number = ext.number,
        title = ext.title,
        poster = ext.poster,
        tvShow = null,
        episodes = ext.episodes.map { toStreamflixEpisode(it) },
    ).apply {
        itemType = AppAdapter.Type.SEASON_MOBILE_ITEM
    }

    fun toStreamflixGenre(ext: ExtensionGenre): Genre = Genre(
        id = ext.id,
        name = ext.name,
        shows = ext.shows.map { toStreamflixItem(it) as Show },
    ).apply {
        itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
    }

    fun toStreamflixPeople(ext: ExtensionPeople): People = People(
        id = ext.id,
        name = ext.name,
        image = ext.image,
        biography = ext.biography,
        placeOfBirth = null,
        birthday = null,
        deathday = null,
        filmography = ext.filmography.map { toStreamflixItem(it) as Show },
    ).apply {
        itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
    }

    fun toStreamflixServer(ext: ExtensionServer): Video.Server = Video.Server(
        id = ext.id,
        name = ext.name,
        src = ext.src,
    )

    fun fromStreamflixServer(server: Video.Server): ExtensionServer = ExtensionServer(
        id = server.id,
        name = server.name,
        src = server.src,
    )

    fun toStreamflixVideo(ext: ExtensionVideo): Video = Video(
        source = ext.source,
        subtitles = ext.subtitles.map { sub ->
            Video.Subtitle(
                label = sub.label,
                file = sub.file,
                default = sub.isDefault,
            )
        },
        headers = ext.headers,
        type = ext.type,
    )
}
