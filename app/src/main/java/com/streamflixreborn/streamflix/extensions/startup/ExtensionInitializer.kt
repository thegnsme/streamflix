// file: extensions/startup/ExtensionInitializer.kt
package com.streamflixreborn.streamflix.extensions.startup

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.extensions.adapter.ExtensionProviderAdapter
import com.streamflixreborn.streamflix.extensions.adapter.ExtensionRegistry
import com.streamflixreborn.streamflix.extensions.cloudstream.CloudstreamAdapter
import com.streamflixreborn.streamflix.extensions.db.ExtensionDao
import com.streamflixreborn.streamflix.extensions.db.ExtensionDatabase
import com.streamflixreborn.streamflix.extensions.db.ExtensionRepoEntity
import com.streamflixreborn.streamflix.extensions.db.InstalledExtensionEntity
import com.streamflixreborn.streamflix.extensions.db.RepositoryDao
import com.streamflixreborn.streamflix.extensions.loader.ExtensionLoader
import com.streamflixreborn.streamflix.extensions.loader.PluginClassLoader
import com.streamflixreborn.streamflix.extensions.manager.ExtensionManager
import com.streamflixreborn.streamflix.extensions.models.ExtensionProvider
import com.streamflixreborn.streamflix.extensions.repo.RepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Bootstraps the extension system on app launch.
 *
 * Designed to be called from [StreamFlixApp.onCreate] inside a
 * `Dispatchers.IO` coroutine. Initialization follows this sequence:
 *
 * 1. Initialize the Room database ([ExtensionDatabase])
 * 2. Ensure the extensions storage directory exists
 * 3. Create the core components (RepositoryManager, ExtensionLoader, ExtensionManager)
 * 4. Seed default repositories if this is the first launch
 * 5. Load previously installed (enabled) extensions in parallel (max 3 at a time)
 * 6. Refresh repository metadata in the background (fire-and-forget)
 * 7. Check for auto-updates in the background (fire-and-forget)
 *
 * ## Thread safety
 *
 * All operations run on [Dispatchers.IO]. Parallel extension loading is
 * throttled via a [Semaphore] to avoid overwhelming resources.
 * Background tasks (refresh, auto-update) use a separate scope and
 * never block the init sequence.
 *
 * ## Error handling
 *
 * The init method never crashes the app:
 * - Database failures propagate to the caller (but the DB singleton pattern
 *   means initialization happens at most once).
 * - Extension loading failures are logged and the extension is disabled.
 * - Background refresh / auto-update failures are logged and silently ignored.
 */
object ExtensionInitializer {

    private const val TAG = "ExtensionInitializer"

    /** Subdirectory under [Context.filesDir] where .cs3 extension files are stored. */
    private const val EXTENSIONS_DIR = "extensions"

    /** Maximum number of extensions loaded simultaneously on startup. */
    private const val MAX_PARALLEL_LOADS = 3

    // ── Default repository URLs ─────────────────────────────────────────

    /** Official Streamflix extension repository (built-in, enabled by default). */
    private const val OFFICIAL_REPO_URL =
        "https://raw.githubusercontent.com/streamflix-reborn/extensions/main/repo.json"

    /** Cloudstream community extension repository (built-in, disabled by default). */
    private const val COMMUNITY_REPO_URL =
        "https://raw.githubusercontent.com/cloudstream/cloudstream-extension-repo/main/repo.json"

    // ── Bootstrap ───────────────────────────────────────────────────────

    /**
     * Bootstrap the extension system.
     *
     * Safe to call multiple times — the database singleton guards against
     * double initialization, and [Semaphore]-throttled loading is idempotent
     * (already-loaded plugins are skipped by the loader's mutex).
     *
     * @param context Application context.
     */
    suspend fun init(context: Context) {
        val startTime = System.nanoTime()
        Log.i(TAG, "Initializing extension system...")

        // ── 1. Initialize database ──────────────────────────────────────
        val db = ExtensionDatabase.getInstance(context)
        val repoDao = db.repositoryDao()
        val extensionDao = db.extensionDao()

        // ── 2. Ensure extensions directory exists ───────────────────────
        val extDir = File(context.filesDir, EXTENSIONS_DIR)
        if (!extDir.exists()) {
            extDir.mkdirs()
            Log.d(TAG, "Created extensions directory: ${extDir.absolutePath}")
        }

        // ── 3. Create components ────────────────────────────────────────
        val repoManager = RepositoryManager(context, repoDao)
        val extensionLoader = ExtensionLoader(context)
        val extensionManager = ExtensionManager(
            context = context,
            extensionDao = extensionDao,
            repoManager = repoManager,
            extensionLoader = extensionLoader,
            extensionRegistry = ExtensionRegistry,
        )

        // ── 4. Seed default repositories if first launch ────────────────
        if (repoDao.count() == 0) {
            seedDefaultRepositories(repoDao)
        }

        // ── 5. Load previously installed (enabled) extensions ───────────
        // Runs in parallel, max 3 at a time via Semaphore.
        val enabledExtensions = extensionDao.getEnabled()
        if (enabledExtensions.isNotEmpty()) {
            Log.i(TAG, "Loading ${enabledExtensions.size} enabled extension(s)...")
            val semaphore = Semaphore(MAX_PARALLEL_LOADS)

            coroutineScope {
                enabledExtensions.map { entity ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            loadPlugin(entity, extensionLoader, extensionDao)
                        }
                    }
                }
            }
        }

        // ── 6. Refresh repository metadata (background, fire-and-forget) ─
        GlobalScope.launch(Dispatchers.IO) {
            try {
                repoManager.refreshAllRepositories()
                Log.d(TAG, "Background repository refresh complete")
            } catch (e: Exception) {
                Log.w(TAG, "Background repository refresh failed", e)
            }
        }

        // ── 7. Check for auto-updates (background, fire-and-forget) ─────
        GlobalScope.launch(Dispatchers.IO) {
            try {
                extensionManager.checkAutoUpdatesOnLaunch()
            } catch (e: Exception) {
                Log.w(TAG, "Background auto-update check failed", e)
            }
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000L
        Log.i(TAG, "Extension system initialized in ${elapsedMs}ms " +
            "(${enabledExtensions.size} extensions, " +
            "${ExtensionRegistry.getProviderCount()} providers)")
    }

    // ── Plugin loading ──────────────────────────────────────────────────

    /**
     * Load a single extension plugin and register it in [ExtensionRegistry].
     *
     * On failure the extension is disabled in the database so it will not
     * be retried on subsequent launches (the user can re-enable it from
     * settings after addressing the issue).
     *
     * @param entity          The installed extension entity from the database.
     * @param extensionLoader The shared [ExtensionLoader] instance.
     * @param extensionDao    DAO for persisting the disabled state on failure.
     */
    private suspend fun loadPlugin(
        entity: InstalledExtensionEntity,
        extensionLoader: ExtensionLoader,
        extensionDao: ExtensionDao,
    ) {
        val result = extensionLoader.loadExtension(entity.filePath)

        if (result.isSuccess) {
            val pluginLoader = result.getOrThrow()
            registerPlugin(entity, pluginLoader)
            Log.d(TAG, "Loaded extension '${entity.name}' v${entity.version}")
        } else {
            Log.e(TAG, "Failed to load extension '${entity.name}' " +
                "(${entity.packageName}): ${result.exceptionOrNull()?.message}")
            try {
                extensionDao.setEnabled(entity.packageName, false)
                Log.d(TAG, "Disabled failed extension '${entity.packageName}' in DB")
            } catch (dbError: Exception) {
                Log.e(TAG, "Failed to disable extension '${entity.packageName}' in DB", dbError)
            }
        }
    }

    // ── Plugin type detection & registration ───────────────────────────

    /**
     * Determine the plugin type and register it with [ExtensionRegistry].
     *
     * Three outcomes:
     * 1. **Native [ExtensionProvider]** — wraps in [ExtensionProviderAdapter].
     * 2. **Cloudstream plugin** (has `load(String)` method) — wraps in
     *    [CloudstreamAdapter].
     * 3. **Unknown** — logs a warning; the plugin is loaded but not usable.
     *
     * @param entity       The installed extension entity (used for logging).
     * @param pluginLoader The loaded plugin wrapper.
     */
    private fun registerPlugin(
        entity: InstalledExtensionEntity,
        pluginLoader: PluginClassLoader,
    ) {
        val instance = pluginLoader.pluginInstance ?: return

        when {
            // Native ExtensionProvider interface
            instance is ExtensionProvider -> {
                val adapter = ExtensionProviderAdapter(instance)
                ExtensionRegistry.registerAdapter(adapter)
                Log.d(TAG, "Registered native provider: '${entity.name}'")
            }

            // Cloudstream plugin (detected by presence of load(String) method)
            hasCloudstreamApi(instance) -> {
                val adapter = CloudstreamAdapter(
                    pluginInstance = instance,
                    classLoader = pluginLoader.delegate,
                )
                ExtensionRegistry.registerCloudstream(adapter)
                Log.d(TAG, "Registered Cloudstream provider: '${entity.name}'")
            }

            else -> {
                Log.w(TAG, "Unknown plugin type for '${entity.name}': " +
                    "${instance::class.java.name}")
            }
        }
    }

    /**
     * Check whether an object has the Cloudstream [load] method signature.
     *
     * Cloudstream 3 plugins implement `MainAPI` which defines:
     * `suspend fun load(url: String): LoadResponse?`
     * This is the distinguishing method we use for type detection.
     */
    private fun hasCloudstreamApi(instance: Any): Boolean = runCatching {
        instance::class.java.getMethod("load", String::class.java)
        true
    }.getOrDefault(false)

    // ── Default repository seeding ─────────────────────────────────────

    /**
     * Insert the built-in default repositories on first launch.
     *
     * The Cloudstream community repository is added but **disabled by default**
     * — users can enable it from the extension settings if they want
     * Cloudstream-compatible plugins.
     *
     * @param repoDao DAO for persisting the repository entries.
     */
    private suspend fun seedDefaultRepositories(
        repoDao: RepositoryDao,
    ) {
        val repos = listOf(
            ExtensionRepoEntity(
                url = OFFICIAL_REPO_URL,
                name = "Streamflix Official Extensions",
                description = "Official Streamflix extension repository",
                enabled = true,
                isBuiltIn = true,
            ),
            ExtensionRepoEntity(
                url = COMMUNITY_REPO_URL,
                name = "Cloudstream Community Extensions",
                description = "Community-maintained Cloudstream 3 compatible extensions",
                enabled = false,
                isBuiltIn = true,
            ),
        )

        for (repo in repos) {
            try {
                repoDao.upsert(repo)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to seed repository '${repo.name}'", e)
            }
        }

        Log.i(TAG, "Seeded ${repos.size} default repositories " +
            "(${repos.count { it.enabled }} enabled)")
    }
}
