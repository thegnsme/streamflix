package com.streamflixreborn.streamflix.extensions.loader

import com.streamflixreborn.streamflix.extensions.models.ExtensionManifest
import dalvik.system.PathClassLoader

/**
 * Wraps a [PathClassLoader] for a single extension plugin with lifecycle management.
 *
 * Each plugin gets its own [PathClassLoader] instance for class isolation — plugin
 * classes cannot interfere with each other or with the host app. The [manifest]
 * provides metadata (name, version, plugin class name) used at registration time.
 *
 * After [cleanup] is called, the plugin instance is released for garbage collection.
 * The underlying classloader becomes collectible once all references to this wrapper
 * and its loaded classes are dropped (Android classloaders cannot be forcibly unloaded).
 */
class PluginClassLoader(
    filePath: String,
    parent: ClassLoader,
    val manifest: ExtensionManifest,
) {

    /** The underlying Android DEX classloader for this plugin. */
    internal val delegate: PathClassLoader = PathClassLoader(filePath, parent)

    /** The loaded plugin instance, or null if not yet loaded or after [cleanup]. */
    internal var pluginInstance: Any? = null

    /**
     * Returns the plugin instance cast to [T], or null if:
     * - The plugin has not been loaded yet.
     * - [cleanup] has been called.
     * - The instance is not an instance of [T].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getPlugin(): T? = pluginInstance as? T

    /** The display name of this plugin from its manifest. */
    val name: String get() = manifest.name

    /** The version code of this plugin from its manifest. */
    val version: Int get() = manifest.version

    /** Whether the plugin has been loaded and not yet cleaned up. */
    val isLoaded: Boolean get() = pluginInstance != null

    /**
     * Releases the plugin instance for garbage collection.
     *
     * Call this when unloading the plugin so the plugin class and its objects
     * can be GC'd. The [delegate] classloader reference is kept as part of this
     * object — it becomes collectible when all references to this [PluginClassLoader]
     * are dropped.
     */
    fun cleanup() {
        pluginInstance = null
    }
}
