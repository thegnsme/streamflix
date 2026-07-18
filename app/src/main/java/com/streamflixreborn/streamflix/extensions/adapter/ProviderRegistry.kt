package com.streamflixreborn.streamflix.extensions.adapter

import com.streamflixreborn.streamflix.extensions.models.ExtensionMediaType
import com.streamflixreborn.streamflix.providers.Provider

/**
 * Merges built-in providers (from [Provider.providers]) with extension
 * providers (from [ExtensionRegistry]) into a single view.
 *
 * This is the replacement for ad-hoc lookups against [Provider.providers].
 * Existing code should call [allProviders] instead of accessing the companion
 * map directly.
 */
object ProviderRegistry {

    /**
     * Returns all available providers (built-in + extension) with their
     * support flags. Extension providers derive their support from
     * [ExtensionMediaType] values reported by the extension.
     */
    fun allProviders(): Map<Provider, Provider.ProviderSupport> = buildMap {
        // Built-in providers from the existing companion map
        putAll(Provider.providers)

        // Extension providers loaded via ExtensionRegistry
        ExtensionRegistry.getAllProviders().forEach { provider ->
            val support = if (provider is ExtensionProviderAdapter) {
                val types = provider.extensionProvider.supportedTypes
                Provider.ProviderSupport(
                    movies = ExtensionMediaType.MOVIE in types,
                    tvShows = ExtensionMediaType.TV_SHOW in types,
                )
            } else {
                // Fallback for CloudstreamAdapter or unknown wrappers
                Provider.ProviderSupport(movies = true, tvShows = true)
            }
            put(provider, support)
        }
    }

    /**
     * Finds a provider by its [name] across both built-in and extension
     * providers. Returns `null` if no provider with that name is registered.
     */
    fun findByName(name: String): Provider? {
        return allProviders().keys.find { it.name == name }
    }

    /**
     * Returns `true` if the given [provider] supports movies.
     * Defaults to `true` when the provider is not registered.
     */
    fun supportsMovies(provider: Provider): Boolean {
        return allProviders()[provider]?.movies ?: true
    }

    /**
     * Returns `true` if the given [provider] supports TV shows.
     * Defaults to `true` when the provider is not registered.
     */
    fun supportsTvShows(provider: Provider): Boolean {
        return allProviders()[provider]?.tvShows ?: true
    }
}
