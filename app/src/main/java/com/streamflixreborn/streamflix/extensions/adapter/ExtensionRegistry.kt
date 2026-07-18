package com.streamflixreborn.streamflix.extensions.adapter

import com.streamflixreborn.streamflix.extensions.cloudstream.CloudstreamAdapter
import com.streamflixreborn.streamflix.providers.Provider

/**
 * Central registry of all loaded extension providers.
 * Thread-safe singleton analogous to Cloudstream's APIHolder.
 */
object ExtensionRegistry {

    private val providerAdapters = mutableListOf<ExtensionProviderAdapter>()
    private val cloudstreamAdapters = mutableListOf<CloudstreamAdapter>()

    @Synchronized
    fun registerAdapter(adapter: ExtensionProviderAdapter) {
        providerAdapters.add(adapter)
    }

    @Synchronized
    fun registerCloudstream(adapter: CloudstreamAdapter) {
        cloudstreamAdapters.add(adapter)
    }

    @Synchronized
    fun unregisterAdapter(adapter: ExtensionProviderAdapter) {
        providerAdapters.remove(adapter)
    }

    @Synchronized
    fun unregisterCloudstream(adapter: CloudstreamAdapter) {
        cloudstreamAdapters.remove(adapter)
    }

    /** All extension-based Providers (wrapped in adapters). */
    @Synchronized
    fun getAllProviders(): List<Provider> = providerAdapters + cloudstreamAdapters

    @Synchronized
    fun getProviderCount(): Int = providerAdapters.size + cloudstreamAdapters.size

    @Synchronized
    fun clear() {
        providerAdapters.clear()
        cloudstreamAdapters.clear()
    }
}
