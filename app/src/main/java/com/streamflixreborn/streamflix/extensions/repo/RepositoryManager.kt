// file: extensions/repo/RepositoryManager.kt
package com.streamflixreborn.streamflix.extensions.repo

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.extensions.db.ExtensionRepoEntity
import com.streamflixreborn.streamflix.extensions.db.RepositoryDao
import com.streamflixreborn.streamflix.extensions.models.RepositoryManifest
import com.streamflixreborn.streamflix.extensions.models.RepositoryPluginEntry
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Manages extension repositories — CRUD, refresh, and validation.
 *
 * Repositories are Cloudstream-compatible sources that provide:
 * - A `repo.json` manifest describing the repository
 * - One or more `plugins.json` files listing available extensions
 *
 * ## Threading
 * All public methods are [suspend] functions. Callers should use
 * [kotlinx.coroutines.Dispatchers.IO] for network and DB operations.
 *
 * ## Error handling
 * - Network errors: return cached data where available, otherwise [Result.failure]
 * - Parse errors: return [Result.failure] with a descriptive message
 * - No method crashes the app on any error
 */
class RepositoryManager(
    private val context: Context,
    private val repoDao: RepositoryDao,
) {
    companion object {
        private const val TAG = "RepositoryManager"

        /**
         * Maximum number of parallel refresh operations when refreshing all repos.
         * Prevents overwhelming the network or the device.
         */
        private const val MAX_PARALLEL_REFRESHES = 3

        /**
         * Supported manifest version. Cloudstream-compatible repos use version 1.
         */
        private val SUPPORTED_MANIFEST_VERSIONS = setOf(1, 2)

        /**
         * Connect/read timeout for the initial repo.json fetch during addRepository.
         */
        private const val REPO_FETCH_TIMEOUT_SECONDS = 15L

        /**
         * Read timeout for plugin-list fetches during refresh.
         * Plugin lists can be large (many entries), hence a longer timeout.
         */
        private const val PLUGIN_LIST_TIMEOUT_SECONDS = 30L
    }

    /**
     * In-memory cache of the last successful refresh result per repository URL.
     *
     * Key: repository URL
     * Value: list of [ExtensionMetadata] from the last successful refresh
     *
     * Used to serve stale data when the network is unavailable
     * (graceful degradation as specified in the architecture).
     */
    private val metadataCache = ConcurrentHashMap<String, List<ExtensionMetadata>>()

    // ── Repository CRUD ───────────────────────────────────────────

    /**
     * Add a repository by URL.
     *
     * **Steps:**
     * 1. Validate URL format (must be http/https)
     * 2. Fetch `repo.json` from [url] with a 15-second timeout
     * 3. Parse JSON into [RepositoryManifest]
     * 4. Validate [RepositoryManifest.manifestVersion] (only version 1 supported)
     * 5. Create an [ExtensionRepoEntity] and upsert it in the database
     * 6. Auto-refresh: fetch each plugin-list URL, parse entries, update cache
     * 7. Return [Result.success] with the entity, or [Result.failure]
     *
     * The repository is added to the DB even if the auto-refresh fails;
     * data will be available on the next successful refresh.
     */
    suspend fun addRepository(url: String): Result<ExtensionRepoEntity> = runCatching {
        // 1. Validate URL format
        if (!RepositoryValidator.isValidUrl(url)) {
            throw RepoManagerException("Invalid repository URL: $url")
        }

        // 2. Fetch repo.json
        val manifest = fetchManifest(url)

        // 3. Validate manifest version
        if (manifest.manifestVersion !in SUPPORTED_MANIFEST_VERSIONS) {
            throw RepoManagerException(
                "Unsupported manifest version ${manifest.manifestVersion}. " +
                    "Supported versions: $SUPPORTED_MANIFEST_VERSIONS"
            )
        }

        // 4. Create entity & upsert in DB
        val entity = ExtensionRepoEntity(
            url = url.trimEnd('/'),
            name = manifest.name,
            description = manifest.description,
            enabled = true,
            isBuiltIn = false,
            lastRefreshed = null,
        )
        repoDao.upsert(entity)

        // 5. Auto-refresh (best-effort — failure does not block adding the repo)
        try {
            val metadata = refreshPluginLists(url, manifest.pluginLists)
            metadataCache[url] = metadata
            repoDao.setLastRefreshed(url, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Auto-refresh failed for new repository $url", e)
            // Repository added even if refresh fails — data available on next refresh
        }

        entity
    }

    /**
     * Remove a repository and all its installed extensions from the database.
     *
     * @param url The URL of the repository to remove.
     */
    suspend fun removeRepository(url: String) {
        val repo = repoDao.get(url)
        if (repo != null) {
            repoDao.delete(repo)
        }
        metadataCache.remove(url)
    }

    /**
     * Enable or disable a repository.
     *
     * When a repository is disabled, its extensions are NOT automatically
     * disabled — that is the responsibility of [setRepositoryEnabled] callers
     * (typically [com.streamflixreborn.streamflix.extensions.manager.ExtensionManager]).
     *
     * @param url The URL of the repository.
     * @param enabled `true` to enable, `false` to disable.
     */
    suspend fun setRepositoryEnabled(url: String, enabled: Boolean) {
        repoDao.setEnabled(url, enabled)
        if (!enabled) {
            metadataCache.remove(url)
        }
    }

    /**
     * Observe all repositories as a reactive [Flow].
     *
     * The flow emits the current list whenever the repositories table changes.
     * Useful for UI that needs to stay in sync with the database.
     */
    fun observeRepositories(): Flow<List<ExtensionRepoEntity>> = repoDao.getAll()

    /**
     * Get a single repository by URL.
     *
     * @param url The repository URL.
     * @return The entity, or `null` if not found.
     */
    suspend fun getRepository(url: String): ExtensionRepoEntity? = repoDao.get(url)

    // ── Refresh ───────────────────────────────────────────────────

    /**
     * Refresh a single repository — fetch all plugin lists, parse entries,
     * and update the in-memory cache.
     *
     * **Steps:**
     * 1. Get the repository from the database
     * 2. For each URL in [RepositoryManifest.pluginLists]:
     *    - Fetch the URL (OkHttp, 30-second timeout)
     *    - Parse JSON array into [RepositoryPluginEntry] list
     *    - Validate each entry (URL format, version >= 0, name non-empty)
     *    - Convert valid entries to [ExtensionMetadata]
     * 3. Update [ExtensionRepoEntity.lastRefreshed] in the database
     * 4. Return the metadata list
     *
     * On failure: returns cached data if available, otherwise [Result.failure].
     *
     * @param url The URL of the repository to refresh.
     * @return [Result.success] with the list of extension metadata,
     *         or [Result.failure] if the refresh fails (with fallback to cache).
     */
    suspend fun refreshRepository(url: String): Result<List<ExtensionMetadata>> {
        // 1. Get repo from DB
        val repo = repoDao.get(url)
        if (repo == null) {
            return Result.failure(RepoManagerException("Repository not found: $url"))
        }

        // 2. Get the manifest (either from cache or by fetching)
        return try {
            val manifest = fetchManifest(url)
            val metadata = refreshPluginLists(url, manifest.pluginLists)

            // 3. Update cache and timestamp
            metadataCache[url] = metadata
            repoDao.setLastRefreshed(url, System.currentTimeMillis())

            Result.success(metadata)
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed for $url", e)

            // Fall back to cached metadata if available
            metadataCache[url]?.let { cached ->
                return Result.success(cached)
            }

            Result.failure(e)
        }
    }

    /**
     * Refresh all enabled repositories in parallel.
     *
     * Uses a [Semaphore] to limit concurrent refreshes to [MAX_PARALLEL_REFRESHES].
     * Failures of individual repositories do not affect others.
     *
     * @return A map of repository URL → refresh result for every enabled repo.
     */
    suspend fun refreshAllRepositories(): Map<String, Result<List<ExtensionMetadata>>> {
        val enabledRepos = repoDao.getAll() // collect once
            .let { flow ->
                // Use first() to get a single snapshot from the reactive flow
                try {
                    flow.first()
                } catch (_: NoSuchElementException) {
                    emptyList()
                }
            }
            .filter { it.enabled }

        if (enabledRepos.isEmpty()) return emptyMap()

        val semaphore = Semaphore(MAX_PARALLEL_REFRESHES)
        val results = ConcurrentHashMap<String, Result<List<ExtensionMetadata>>>()

        // Launch parallel refreshes, each acquiring the semaphore
        coroutineScope {
            val jobs = enabledRepos.map { repo ->
                launch {
                    semaphore.withPermit {
                        val result = refreshRepository(repo.url)
                        results[repo.url] = result
                    }
                }
            }
            jobs.forEach { it.join() }
        }

        return results
    }

    // ── Validation ─────────────────────────────────────────────────

    /**
     * Validate a repository URL — checks format and reachability.
     *
     * Delegates to [RepositoryValidator.validate].
     *
     * @param url The repository URL to validate.
     * @return A [RepositoryValidation] with the results.
     */
    suspend fun validateRepository(url: String): RepositoryValidation {
        return RepositoryValidator.validate(url)
    }

    // ── Internal helpers ──────────────────────────────────────────

    /**
     * Fetch and parse `repo.json` from [url].
     *
     * @param url The full URL of the `repo.json` file.
     * @return The parsed [RepositoryManifest].
     * @throws Exception on network failure or parse error.
     */
    private suspend fun fetchManifest(url: String): RepositoryManifest =
        withContext(Dispatchers.IO) {
            val client = NetworkClient.default.newBuilder()
                .readTimeout(REPO_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(REPO_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RepoManagerException(
                    "HTTP ${response.code} fetching repo.json from $url"
                )
            }
            val body = response.body?.string()
                ?: throw RepoManagerException("Empty response body from $url")

            RepositoryFormat.parseRepoJson(body).getOrThrow()
        }

    /**
     * Fetch all plugin-list URLs, parse them, validate entries, and
     * return the merged list of [ExtensionMetadata].
     *
     * @param repoUrl The base repository URL (for resolving relative plugin URLs).
     * @param pluginUrls The list of plugin-list URLs from the manifest.
     * @return Merged list of validated [ExtensionMetadata].
     */
    private suspend fun refreshPluginLists(
        repoUrl: String,
        pluginUrls: List<String>,
    ): List<ExtensionMetadata> = withContext(Dispatchers.IO) {
        if (pluginUrls.isEmpty()) return@withContext emptyList()

        val client = NetworkClient.default.newBuilder()
            .readTimeout(PLUGIN_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(PLUGIN_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val allMetadata = mutableListOf<ExtensionMetadata>()

        for (pluginUrl in pluginUrls) {
            try {
                val resolvedUrl = RepositoryFormat.resolvePluginUrl(repoUrl, pluginUrl)

                val request = Request.Builder()
                    .url(resolvedUrl)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} fetching plugin list $resolvedUrl")
                    continue
                }

                val body = response.body?.string()
                    ?: continue

                val entries = RepositoryFormat.parsePluginListJson(body).getOrNull()
                    ?: continue

                // Validate and convert each entry
                for (entry in entries) {
                    if (isValidPluginEntry(entry)) {
                        allMetadata.add(entry.toMetadata())
                    } else {
                        Log.w(TAG, "Skipping invalid plugin entry: ${entry.internalName}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch plugin list $pluginUrl", e)
                // Continue with other plugin lists
            }
        }

        allMetadata
    }

    /**
     * Validate a single [RepositoryPluginEntry].
     *
     * An entry is valid when:
     * - [RepositoryPluginEntry.url] is a non-blank string
     * - [RepositoryPluginEntry.version] >= 0
     * - [RepositoryPluginEntry.name] is non-blank
     * - [RepositoryPluginEntry.internalName] is non-blank
     */
    private fun isValidPluginEntry(entry: RepositoryPluginEntry): Boolean {
        return entry.url.isNotBlank() &&
            entry.version >= 0 &&
            entry.name.isNotBlank() &&
            entry.internalName.isNotBlank()
    }

    /**
     * Convert a validated [RepositoryPluginEntry] to [ExtensionMetadata].
     */
    private fun RepositoryPluginEntry.toMetadata(): ExtensionMetadata = ExtensionMetadata(
        url = url,
        name = name,
        internalName = internalName,
        version = version,
        description = description,
        authors = authors,
        language = language,
        tvTypes = tvTypes,
        iconUrl = iconUrl,
        fileSize = fileSize,
        fileHash = fileHash,
        apiVersion = apiVersion,
        repositoryUrl = repositoryUrl,
        status = status,
    )
}

/**
 * Exception thrown by [RepositoryManager] operations.
 * @param message Human-readable description of the failure.
 */
class RepoManagerException(message: String) : Exception(message)
