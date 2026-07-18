package com.streamflixreborn.streamflix.extensions.adapter

import com.streamflixreborn.streamflix.extensions.models.ExtensionMediaType
import com.streamflixreborn.streamflix.providers.Provider

/**
 * Merges built-in providers with extension providers into a single view.
 *
 * This file intentionally avoids referencing [Provider.ProviderSupport]
 * to prevent a circular compile dependency (Provider → ProviderRegistry).
 * Support flags are returned as a simple [SupportFlags] value.
 */
object ProviderRegistry {

    /** Lightweight support flags, mirroring [Provider.ProviderSupport]. */
    data class SupportFlags(
        val movies: Boolean,
        val tvShows: Boolean,
    )

    /**
     * Returns all available providers (built-in + extension).
     * Extension providers derive their support from [ExtensionMediaType].
     */
    fun allProviders(): Map<Provider, SupportFlags> = buildMap {
        putAll(Provider.providers.mapValues {
            SupportFlags(movies = it.value.movies, tvShows = it.value.tvShows)
        })
        ExtensionRegistry.getAllProviders().forEach { provider ->
            val flags = if (provider is ExtensionProviderAdapter) {
                val types = provider.extensionProvider.supportedTypes
                SupportFlags(
                    movies = ExtensionMediaType.MOVIE in types,
                    tvShows = ExtensionMediaType.TV_SHOW in types,
                )
            } else {
                SupportFlags(movies = true, tvShows = true)
            }
            put(provider, flags)
        }
    }

    fun findByName(name: String): Provider? {
        return allProviders().keys.find { it.name == name }
    }

    fun supportsMovies(provider: Provider): Boolean {
        return allProviders()[provider]?.movies ?: true
    }

    fun supportsTvShows(provider: Provider): Boolean {
        return allProviders()[provider]?.tvShows ?: true
    }
}
