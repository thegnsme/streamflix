package com.streamflixreborn.streamflix.extensions.loader

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.extensions.models.ExtensionManifest
import dalvik.system.PathClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom exception for extension loading failures.
 * Carries the underlying cause for diagnostics without leaking it in the Result type.
 */
class PluginLoadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Loads .cs3 extension files into isolated [PathClassLoader] instances.
 *
 * This is the most safety-critical component of the extension system. Every public
 * method returns [Result] — the app must never crash, even on corrupt files,
 * missing manifests, or reflection errors.
 *
 * ## Thread safety
 *
 * All load/unload/reload operations are serialized via [Mutex] so only one
 * operation runs at a time. Query methods ([getLoadedExtensions], [isLoaded],
 * [getPluginByProviderName]) are lock-free and read from a [ConcurrentHashMap].
 *
 * ## Lifecycle hooks (reflection-based)
 *
 * - **`load()`** — called after instantiation if the plugin class defines it.
 *   Optional; absence is silently ignored.
 * - **`beforeUnload()`** — called before cleanup if the plugin class defines it.
 *   Optional; absence is silently ignored.
 *
 * ## Android 14+ DEX requirements
 *
 * DEX files must be read-only on Android 14+. The loader calls
 * [File.setReadOnly] before creating the [PathClassLoader]; a failure here is
 * non-fatal (logged as a warning).
 */
class ExtensionLoader(private val context: Context) {

    /** All currently loaded plugins, keyed by their file path. */
    private val loadedPlugins = ConcurrentHashMap<String, PluginClassLoader>()

    /** Serializes load/unload/reload operations for thread safety. */
    private val mutex = Mutex()

    /** Reusable manifest parser instance. */
    private val manifestParser = ManifestParser()

    companion object {
        private const val TAG = "ExtensionLoader"
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    /**
     * Loads a .cs3 (or .zip) extension file into an isolated classloader.
     *
     * @param filePath Absolute path to the extension archive on disk.
     * @return [Result.success] with the [PluginClassLoader] wrapper, or
     *         [Result.failure] with a [PluginLoadException] describing the error.
     */
    suspend fun loadExtension(filePath: String): Result<PluginClassLoader> {
        return mutex.withLock {
            loadExtensionInternal(filePath)
        }
    }

    /**
     * Internal load — no mutex. Used by [reloadExtension] which holds the mutex
     * for the entire unload+load sequence.
     */
    private suspend fun loadExtensionInternal(filePath: String): Result<PluginClassLoader> {
        return try {
            // ── 1. File validation ────────────────────────────────────
            val file = File(filePath)
            require(file.exists()) { "File not found: $filePath" }
            require(file.isFile) { "Path is not a file: $filePath" }
            val extension = filePath.substringAfterLast('.', "")
            require(extension == "cs3" || extension == "zip") {
                "Unsupported file extension '.$extension' — expected .cs3 or .zip"
            }

            // ── 2. Set file read-only (Android 14+ DEX requirement) ───
            try {
                file.setReadOnly()
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not set read-only on $filePath", e)
            }

            // ── 3. Parse and validate manifest ────────────────────────
            val manifest = manifestParser.parse(filePath).getOrElse { error ->
                throw PluginLoadException("Failed to parse manifest from $filePath", error)
            }
            require(manifest.pluginClassName.isNotBlank()) {
                "manifest.pluginClassName is blank in $filePath"
            }
            require(manifest.version >= 1) {
                "manifest.version must be >= 1, got ${manifest.version} in $filePath"
            }

            // ── 4. Create PathClassLoader ─────────────────────────────
            val classLoader = PathClassLoader(filePath, context.classLoader)

            // ── 5. Load plugin class ──────────────────────────────────
            val pluginClass = try {
                classLoader.loadClass(manifest.pluginClassName)
            } catch (e: ClassNotFoundException) {
                throw PluginLoadException(
                    "Plugin class '${manifest.pluginClassName}' not found in $filePath", e
                )
            } catch (e: LinkageError) {
                throw PluginLoadException(
                    "Failed to link plugin class '${manifest.pluginClassName}' in $filePath", e
                )
            }

            // ── 6. Instantiate via no-arg constructor ─────────────────
            val instance = try {
                val constructor = pluginClass.getDeclaredConstructor()
                constructor.newInstance()
            } catch (e: NoSuchMethodException) {
                throw PluginLoadException(
                    "Plugin class '${manifest.pluginClassName}' has no public no-arg constructor", e
                )
            } catch (e: InstantiationException) {
                throw PluginLoadException(
                    "Cannot instantiate abstract class '${manifest.pluginClassName}'", e
                )
            } catch (e: IllegalAccessException) {
                throw PluginLoadException(
                    "Plugin constructor is not accessible: '${manifest.pluginClassName}'", e
                )
            }

            // ── 7. Call load() lifecycle hook (optional) ──────────────
            try {
                val loadMethod = pluginClass.getMethod("load")
                loadMethod.invoke(instance)
            } catch (_: NoSuchMethodException) {
                // load() is optional — skip gracefully
            } catch (e: Exception) {
                Log.w(TAG, "Plugin '${manifest.name}'.load() threw an exception", e)
                // Non-fatal: the plugin is still usable, log and continue
            }

            // ── 8. Wrap and store ─────────────────────────────────────
            val wrapper = PluginClassLoader(filePath, context.classLoader, manifest).also {
                it.pluginInstance = instance
            }
            loadedPlugins[filePath] = wrapper

            Log.i(TAG, "Loaded '${manifest.name}' v${manifest.version} from $filePath")
            Result.success(wrapper)

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load extension: $filePath", e)
            loadedPlugins.remove(filePath)
            Result.failure(
                if (e is PluginLoadException) e
                else PluginLoadException("Unexpected error loading $filePath", e)
            )
        }
    }

    // ── Unload ───────────────────────────────────────────────────────────────

    /**
     * Unloads a previously loaded extension.
     *
     * Calls `beforeUnload()` on the plugin instance (if the method exists),
     * removes the plugin from internal maps, and releases the instance for GC.
     *
     * @param filePath Absolute path of the extension to unload.
     * @return [Result.success] if successfully unloaded (or not loaded),
     *         [Result.failure] with a [PluginLoadException] on error.
     */
    suspend fun unloadExtension(filePath: String): Result<Unit> {
        return mutex.withLock {
            try {
                val wrapper = loadedPlugins[filePath]
                    ?: return@withLock Result.failure(
                        PluginLoadException("Extension not loaded: $filePath")
                    )

                // Call beforeUnload() lifecycle hook (optional)
                wrapper.pluginInstance?.let { instance ->
                    try {
                        val method = instance::class.java.getMethod("beforeUnload")
                        method.invoke(instance)
                    } catch (_: NoSuchMethodException) {
                        // beforeUnload() is optional — skip gracefully
                    } catch (e: Exception) {
                        Log.w(TAG, "Plugin '${wrapper.name}'.beforeUnload() threw", e)
                        // Non-fatal: continue with cleanup
                    }
                }

                loadedPlugins.remove(filePath)
                wrapper.cleanup()

                Log.i(TAG, "Unloaded '${wrapper.name}' from $filePath")
                Result.success(Unit)

            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload extension: $filePath", e)
                loadedPlugins.remove(filePath)
                Result.failure(
                    if (e is PluginLoadException) e
                    else PluginLoadException("Unexpected error unloading $filePath", e)
                )
            }
        }
    }

    // ── Reload ───────────────────────────────────────────────────────────────

    /**
     * Unloads then reloads an extension atomically under the mutex.
     * Useful for updates, recovery from transient errors, or config changes.
     *
     * @param filePath Absolute path of the extension to reload.
     * @return [Result.success] with the new [PluginClassLoader], or
     *         [Result.failure] with a [PluginLoadException] on error.
     */
    suspend fun reloadExtension(filePath: String): Result<PluginClassLoader> {
        return mutex.withLock {
            try {
                // Unload existing (if any)
                loadedPlugins.remove(filePath)?.cleanup()

                // Load fresh
                loadExtensionInternal(filePath)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to reload extension: $filePath", e)
                Result.failure(
                    if (e is PluginLoadException) e
                    else PluginLoadException("Unexpected error reloading $filePath", e)
                )
            }
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * Returns a snapshot of all currently loaded extension wrappers.
     * The returned list is a copy — modifications do not affect internal state.
     */
    fun getLoadedExtensions(): List<PluginClassLoader> =
        loadedPlugins.values.toList()

    /**
     * Returns true if the given file path is currently loaded.
     */
    fun isLoaded(filePath: String): Boolean =
        loadedPlugins.containsKey(filePath)

    /**
     * Finds a loaded plugin by its provider name (from the extension manifest).
     * Returns null if no plugin with that name is currently loaded.
     *
     * Note: provider names are unique within the registry but this method
     * returns the first match if duplicates somehow exist (defensive).
     */
    fun getPluginByProviderName(name: String): PluginClassLoader? =
        loadedPlugins.values.find { it.name == name }
}
