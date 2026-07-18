package com.streamflixreborn.streamflix.extensions.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Media Types ─────────────────────────────────────────────────

enum class ExtensionMediaType {
    MOVIE,
    TV_SHOW,
    EPISODE,
}

// ── Search Result Sealed Interface ─────────────────────────────

sealed interface ExtensionSearchResult {
    val id: String
    val title: String
    val poster: String?
    val overview: String?
    val rating: Double?
    val quality: String?
    val year: String?
}

// ── Content Models ─────────────────────────────────────────────

data class ExtensionCategory(
    val name: String,
    val items: List<ExtensionSearchResult>,
)

data class ExtensionMovie(
    override val id: String,
    override val title: String,
    override val overview: String? = null,
    override val poster: String? = null,
    val banner: String? = null,
    val released: String? = null,
    val runtime: Int? = null,
    val trailer: String? = null,
    override val quality: String? = null,
    override val rating: Double? = null,
    val imdbId: String? = null,
    val genres: List<ExtensionGenreRef> = emptyList(),
    val cast: List<ExtensionPeopleRef> = emptyList(),
    val directors: List<ExtensionPeopleRef> = emptyList(),
    val recommendations: List<ExtensionSearchResult> = emptyList(),
    override val year: String? = null,
) : ExtensionSearchResult

data class ExtensionTvShow(
    override val id: String,
    override val title: String,
    override val overview: String? = null,
    override val poster: String? = null,
    val banner: String? = null,
    val released: String? = null,
    val runtime: Int? = null,
    val trailer: String? = null,
    override val quality: String? = null,
    override val rating: Double? = null,
    val imdbId: String? = null,
    val seasons: List<ExtensionSeason> = emptyList(),
    val genres: List<ExtensionGenreRef> = emptyList(),
    val cast: List<ExtensionPeopleRef> = emptyList(),
    val directors: List<ExtensionPeopleRef> = emptyList(),
    val recommendations: List<ExtensionSearchResult> = emptyList(),
    override val year: String? = null,
) : ExtensionSearchResult

data class ExtensionSeason(
    val id: String,
    val number: Int,
    val title: String? = null,
    val poster: String? = null,
    val episodes: List<ExtensionEpisode> = emptyList(),
)

data class ExtensionEpisode(
    val id: String,
    val number: Int,
    val title: String? = null,
    val poster: String? = null,
    val overview: String? = null,
    val released: String? = null,
)

// ── Video / Subtitle / Server ──────────────────────────────────

data class ExtensionVideo(
    val source: String,
    val subtitles: List<ExtensionSubtitle> = emptyList(),
    val headers: Map<String, String>? = null,
    val type: String? = null,
)

data class ExtensionSubtitle(
    val label: String,
    val file: String,
    val isDefault: Boolean = false,
)

data class ExtensionServer(
    val id: String,
    val name: String,
    val src: String = "",
)

// ── Genre / People / References ────────────────────────────────

data class ExtensionGenre(
    val id: String,
    val name: String,
    val shows: List<ExtensionSearchResult> = emptyList(),
)

data class ExtensionPeople(
    val id: String,
    val name: String,
    val image: String? = null,
    val biography: String? = null,
    val filmography: List<ExtensionSearchResult> = emptyList(),
)

data class ExtensionGenreRef(
    val id: String,
    val name: String,
)

data class ExtensionPeopleRef(
    val id: String,
    val name: String,
    val image: String? = null,
)

// ── Repository & Extension Metadata (Cloudstream compatible) ────

@Serializable
data class RepositoryManifest(
    val name: String,
    val description: String = "",
    @SerialName("manifestVersion")
    val manifestVersion: Int = 1,
    @SerialName("pluginLists")
    val pluginLists: List<String> = emptyList(),
)

@Serializable
data class RepositoryPluginEntry(
    val url: String,
    val status: Int = 3,
    val version: Int = 1,
    val name: String,
    @SerialName("internalName")
    val internalName: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    @SerialName("fileSize")
    val fileSize: Long? = null,
    @SerialName("repositoryUrl")
    val repositoryUrl: String? = null,
    val language: String? = null,
    @SerialName("tvTypes")
    val tvTypes: List<String>? = null,
    @SerialName("iconUrl")
    val iconUrl: String? = null,
    @SerialName("apiVersion")
    val apiVersion: Int? = null,
    @SerialName("fileHash")
    val fileHash: String? = null,
)

@Serializable
data class ExtensionManifest(
    @SerialName("pluginClassName")
    val pluginClassName: String,
    val name: String,
    val version: Int,
    @SerialName("requiresResources")
    val requiresResources: Boolean = false,
)
