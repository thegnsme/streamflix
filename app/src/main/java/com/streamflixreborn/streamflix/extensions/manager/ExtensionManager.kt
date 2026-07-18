// file: extensions/manager/ExtensionManager.kt
package com.streamflixreborn.streamflix.extensions.manager

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.extensions.adapter.ExtensionProviderAdapter
import com.streamflixreborn.streamflix.extensions.adapter.ExtensionRegistry
import com.streamflixreborn.streamflix.extensions.cloudstream.CloudstreamAdapter
import com.streamflixreborn.streamflix.extensions.db.ExtensionDao
import com.streamflixreborn.streamflix.extensions.db.InstalledExtensionEntity
import com.streamflixreborn.streamflix.extensions.loader.ExtensionLoader
import com.streamflixreborn.streamflix.extensions.loader.PluginClassLoader
import com.streamflixreborn.streamflix.extensions.models.ExtensionProvider
import com.streamflixreborn.streamflix.extensions.repo.ExtensionMetadata
import com.streamflixreborn.streamflix.extensions.repo.RepositoryManager
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.BufferedSink
import okio.Okio
import java.io.File
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the full lifecycle of installed extensions:
 * install, update, enable/disable, and uninstall.
 *
 * Acts as the coordination layer between:
 * - [RepositoryManager] (download + metadata)
 * - [ExtensionLoader] (class loading)
 * - [ExtensionRegistry] (provider registration)
 * - [ExtensionDao] (persistence)
 *
 * ## Threading
 * All public suspend functions should be called on [Dispatchers.IO].
 * Internal network and file I/O is already dispatched appropriately.
 *
 * ## Error handling
 * Every public method returns [Result]. The extension system never crashes
 * the host app — on any failure, the error is logged and surfaced via [Result].
 *
 * ## Retry strategy
 * Downloads are retried up to [MAX_DOWNLOAD_RETRIES] times with exponential
 * backoff ([BASE_RETRY_DELAY_MS] × 2^(attempt-1)).
 */
class ExtensionManager(
    private val context: Context,
    private val extensionDao: ExtensionDao,
    private val repoManager: RepositoryManager,
    private val extensionLoader: ExtensionLoader,
    private val extensionRegistry: ExtensionRegistry,
) {
    companion object {
        private const val TAG = "ExtensionManager"

        /** Directory name under [Context.filesDir] where extension .cs3 files are stored. */
        private const val EXTENSIONS_DIR = "extensions"

        /** Maximum number of download retry attempts. */
        private const val MAX_DOWNLOAD_RETRIES = 3

        /** Base delay in ms for exponential backoff: delay = BASE × 2^(attempt-1). */
        private const val BASE_RETRY_DELAY_MS = 1_000L

        /** SharedPreferences name for auto-update tracking. */
        private const val PREFS_NAME = "extension_auto_update"

        /** Preference key for the last auto-update check timestamp. */
        private const val PREF_LAST_AUTO_UPDATE_CHECK = "last_auto_update_check_ms"

        /**
         * Minimum interval between auto-update checks (24 hours).
         * If the last check was less than this long ago, the check is skipped.
         */
        private const val AUTO_UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1_000

        /** SHA-256 algorithm name. */
        private const val SHA_256 = "SHA-256"
    }

    /**
     * Maps packageName → registered adapter instance so we can unregister
     * by package name (ExtensionRegistry only supports unregister by reference).
     */
    private val nativeAdapterMap = ConcurrentHashMap<String, ExtensionProviderAdapter>()
    private val cloudstreamAdapterMap = ConcurrentHashMap<String, CloudstreamAdapter>()

    /** Shared conflict resolver instance. */
    private val conflictResolver = ExtensionConflictResolver()

    // ── Install ─────────────────────────────────────────────────────────────

    /**
     * Download a .cs3 extension from a repository URL, verify its hash,
     * load it into an isolated classloader, and register its provider.
     *
     * **Steps:**
     * 1. Check if already installed → compare versions (skip if same or newer)
     * 2. Ensure the extensions storage directory exists
     * 3. Download the .cs3 file from [metadata.url] with retry + hash verification
     * 4. Load via [ExtensionLoader]
     * 5. If load succeeds: determine plugin type, register, persist, return success
     * 6. If load fails: delete downloaded file, return failure
     *
     * @param metadata The extension metadata from a repository listing.
     * @return [Result.success] with the installed extension entity,
     *         or [Result.failure] with a descriptive [ExtensionManagerException].
     */
    suspend fun installExtension(metadata: ExtensionMetadata): Result<InstalledExtensionEntity> =
        withContext(Dispatchers.IO) {
            try {
                // ── 1. Check if already installed ────────────────────────
                val internalName = metadata.internalName
                val existing = extensionDao.get(internalName)
                if (existing != null) {
                    if (existing.version >= metadata.version) {
                        return@withContext Result.success(existing)
                    }
                    // Otherwise proceed with upgrade
                }

                // ── 2. Ensure extensions directory exists ────────────────
                val extDir = getExtensionsDir()
                if (!extDir.exists()) extDir.mkdirs()

                val destFile = File(extDir, "${internalName}.cs3")

                // ── 3. Download with retry and hash verification ──────────
                val downloadResult = downloadWithRetry(
                    url = metadata.url,
                    destFile = destFile,
                    fileHash = metadata.fileHash,
                )
                if (downloadResult.isFailure) {
                    return@withContext Result.failure(
                        ExtensionManagerException(
                            "Failed to download ${metadata.name}: ${downloadResult.exceptionOrNull()?.message}"
                        )
                    )
                }

                // ── 4. Load via ExtensionLoader ──────────────────────────
                val loadResult = extensionLoader.loadExtension(destFile.absolutePath)
                if (loadResult.isFailure) {
                    // 6. Load failed — clean up downloaded file
                    destFile.delete()
                    return@withContext Result.failure(
                        ExtensionManagerException(
                            "Failed to load extension '${metadata.name}' after download",
                            loadResult.exceptionOrNull()
                        )
                    )
                }

                val pluginLoader = loadResult.getOrThrow()

                // ── 5. Determine plugin type and register ────────────────
                val isCloudstream = !isNativeExtensionProvider(pluginLoader)
                registerPlugin(
                    pluginLoader = pluginLoader,
                    packageName = internalName,
                )

                // ── 5c. Build entity and persist ─────────────────────────
                val now = System.currentTimeMillis()
                val entity = InstalledExtensionEntity(
                    packageName = internalName,
                    name = metadata.name,
                    version = metadata.version,
                    description = metadata.description,
                    author = metadata.authors.firstOrNull(),
                    language = metadata.language,
                    iconUrl = metadata.iconUrl,
                    filePath = destFile.absolutePath,
                    repositoryUrl = metadata.repositoryUrl ?: "",
                    isEnabled = true,
                    isCloudstream = isCloudstream,
                    installedAt = existing?.installedAt ?: now,
                    lastUpdatedAt = now,
                )
                extensionDao.upsert(entity)

                // ── Conflict detection (warn-only, last-installed wins) ──
                val allExtensions = extensionDao.getEnabled()
                val conflicts = conflictResolver.findConflicts(allExtensions, entity)
                if (conflicts.isNotEmpty()) {
                    Log.w(TAG, "Provider name conflicts detected for '${metadata.name}': " +
                        conflicts.joinToString("; ") {
                            "'${it.providerName}' shared by ${it.packageNameA} and ${it.packageNameB}"
                        })
                }

                Log.i(TAG, "Installed extension '${metadata.name}' v${metadata.version}")
                Result.success(entity)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to install extension", e)
                Result.failure(ExtensionManagerException("Installation failed", e))
            }
        }

    /**
     * Install an extension from a local .cs3 file (sideload).
     *
     * **Steps:**
     * 1. Copy the file to internal storage
     * 2. Load via [ExtensionLoader]
     * 3. If success: register, persist metadata (name/version from manifest)
     * 4. If failure: clean up, return error
     *
     * Note: no hash verification is performed for sideloaded files.
     *
     * @param filePath Absolute path to the .cs3 file on disk.
     * @return [Result.success] with the installed extension entity,
     *         or [Result.failure] with a descriptive [ExtensionManagerException].
     */
    suspend fun installFromFile(filePath: String): Result<InstalledExtensionEntity> =
        withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(filePath)
                require(sourceFile.exists()) { "File not found: $filePath" }
                require(sourceFile.extension == "cs3") { "File must have .cs3 extension" }

                // ── 1. Copy to internal storage first ────────────────────
                val extDir = getExtensionsDir()
                if (!extDir.exists()) extDir.mkdirs()

                // Generate a temporary name from the source filename for the copy
                val tempName = sourceFile.nameWithoutExtension
                    .replace(Regex("[^a-zA-Z0-9_]"), "_")
                    .lowercase()
                val tempFile = File(extDir, "${tempName}_tmp.cs3")
                sourceFile.copyTo(tempFile, overwrite = true)

                // ── 2. Load from the copied file ─────────────────────────
                val loadResult = extensionLoader.loadExtension(tempFile.absolutePath)
                if (loadResult.isFailure) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        ExtensionManagerException(
                            "Failed to load extension from file",
                            loadResult.exceptionOrNull()
                        )
                    )
                }

                val pluginLoader = loadResult.getOrThrow()
                val manifest = pluginLoader.manifest

                // Derive a stable internal name from the manifest name
                val internalName = manifest.name
                    .replace(Regex("[^a-zA-Z0-9_]"), "_")
                    .lowercase()

                val destFile = File(extDir, "$internalName.cs3")

                // If the temp name differs from the final name, rename
                if (tempFile.absolutePath != destFile.absolutePath) {
                    if (destFile.exists()) destFile.delete()
                    tempFile.renameTo(destFile)
                }

                // ── 3. Register and persist ──────────────────────────────
                val isCloudstream = !isNativeExtensionProvider(pluginLoader)
                registerPlugin(
                    pluginLoader = pluginLoader,
                    packageName = internalName,
                )

                val now = System.currentTimeMillis()
                val entity = InstalledExtensionEntity(
                    packageName = internalName,
                    name = manifest.name,
                    version = manifest.version,
                    description = null,
                    author = null,
                    language = null,
                    iconUrl = null,
                    filePath = destFile.absolutePath,
                    repositoryUrl = "",
                    isEnabled = true,
                    isCloudstream = isCloudstream,
                    installedAt = now,
                    lastUpdatedAt = now,
                )
                extensionDao.upsert(entity)

                Log.i(TAG, "Installed extension from file: '${manifest.name}' v${manifest.version}")
                Result.success(entity)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to install extension from file: $filePath", e)
                Result.failure(ExtensionManagerException("Install from file failed", e))
            }
        }

    // ── Uninstall ───────────────────────────────────────────────────────────

    /**
     * Uninstall an extension: unregister, unload, delete file, remove from DB.
     *
     * **Steps:**
     * 1. Get extension from DB (return success if not found — idempotent)
     * 2. Unregister provider from [ExtensionRegistry]
     * 3. Unload via [ExtensionLoader]
     * 4. Delete the .cs3 file
     * 5. Delete the database record
     *
     * @param packageName The package name of the extension to uninstall.
     * @return [Result.success] on completion, [Result.failure] on error.
     */
    suspend fun uninstallExtension(packageName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val entity = extensionDao.get(packageName)
                if (entity == null) {
                    Log.w(TAG, "Cannot uninstall — extension not found: $packageName")
                    return@withContext Result.success(Unit) // Idempotent
                }

                // ── 2. Unregister from ExtensionRegistry ─────────────────
                unregisterPlugin(packageName)

                // ── 3. Unload via ExtensionLoader ────────────────────────
                extensionLoader.unloadExtension(entity.filePath)

                // ── 4. Delete .cs3 file ──────────────────────────────────
                val file = File(entity.filePath)
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Could not delete file: ${entity.filePath}")
                }

                // ── 5. Delete from DB ────────────────────────────────────
                extensionDao.delete(entity)

                Log.i(TAG, "Uninstalled extension: $packageName")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to uninstall extension: $packageName", e)
                Result.failure(ExtensionManagerException("Uninstall failed", e))
            }
        }

    // ── Enable / Disable ────────────────────────────────────────────────────

    /**
     * Enable or disable an extension.
     *
     * - **enabled=true**: Load the extension, register its provider(s).
     * - **enabled=false**: Unregister provider(s), unload the extension.
     *
     * @param packageName The package name of the extension.
     * @param enabled     `true` to enable, `false` to disable.
     * @return [Result.success] on completion, [Result.failure] on error.
     */
    suspend fun setExtensionEnabled(packageName: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val entity = extensionDao.get(packageName)
                if (entity == null) {
                    return@withContext Result.failure(
                        ExtensionManagerException("Extension not found: $packageName")
                    )
                }

                if (entity.isEnabled == enabled) {
                    return@withContext Result.success(Unit) // No-op
                }

                if (enabled) {
                    // Enable: load and register
                    val loadResult = extensionLoader.loadExtension(entity.filePath)
                    if (loadResult.isFailure) {
                        return@withContext Result.failure(
                            ExtensionManagerException(
                                "Failed to load extension '$packageName'",
                                loadResult.exceptionOrNull()
                            )
                        )
                    }
                    val pluginLoader = loadResult.getOrThrow()
                    registerPlugin(pluginLoader, packageName)
                } else {
                    // Disable: unregister and unload
                    unregisterPlugin(packageName)
                    extensionLoader.unloadExtension(entity.filePath)
                }

                extensionDao.setEnabled(packageName, enabled)

                Log.i(TAG, "Extension '$packageName' ${if (enabled) "enabled" else "disabled"}")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to set extension '$packageName' enabled=$enabled", e)
                Result.failure(ExtensionManagerException("Enable/disable failed", e))
            }
        }

    // ── Updates ─────────────────────────────────────────────────────────────

    /**
     * Check all enabled repositories for newer versions of installed extensions.
     *
     * **Steps:**
     * 1. Get all installed extensions from DB
     * 2. For each enabled repository, refresh metadata
     * 3. Match by internal name, compare version codes
     * 4. Return list of available updates
     *
     * @return List of [ExtensionUpdate] entries — empty if everything is up-to-date.
     */
    suspend fun checkForUpdates(): List<ExtensionUpdate> = withContext(Dispatchers.IO) {
        try {
            val installedExtensions = extensionDao.getEnabled().associateBy { it.packageName }
            if (installedExtensions.isEmpty()) return@withContext emptyList()

            val updates = mutableListOf<ExtensionUpdate>()

            // Get all enabled repos and their metadata
            val repoResults = repoManager.refreshAllRepositories()

            for ((_, metadataResult) in repoResults) {
                val metadataList = metadataResult.getOrNull() ?: continue

                for (metadata in metadataList) {
                    val installed = installedExtensions[metadata.internalName] ?: continue
                    if (metadata.version > installed.version) {
                        updates.add(
                            ExtensionUpdate(
                                packageName = installed.packageName,
                                currentVersion = installed.version,
                                availableVersion = metadata.version,
                                metadata = metadata,
                            )
                        )
                    }
                }
            }

            updates
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            emptyList()
        }
    }

    /**
     * Update a single extension to its latest available version.
     *
     * **Steps:**
     * 1. Find update metadata from repository data
     * 2. Download new .cs3
     * 3. Unload old version
     * 4. Replace file
     * 5. Load new version
     * 6. Update DB record
     *
     * @param packageName The package name of the extension to update.
     * @return [Result.success] with the updated entity, or [Result.failure].
     */
    suspend fun updateExtension(packageName: String): Result<InstalledExtensionEntity> =
        withContext(Dispatchers.IO) {
            try {
                // ── 1. Find update metadata ──────────────────────────────
                val updateMetadata = findUpdateMetadata(packageName)
                if (updateMetadata == null) {
                    return@withContext Result.failure(
                        ExtensionManagerException("No update available for '$packageName'")
                    )
                }

                // ── 2. Download new version ──────────────────────────────
                val entity = extensionDao.get(packageName)
                if (entity == null) {
                    return@withContext Result.failure(
                        ExtensionManagerException("Extension not found: $packageName")
                    )
                }

                val extDir = getExtensionsDir()
                if (!extDir.exists()) extDir.mkdirs()

                val tempFile = File(extDir, "${packageName}_update.cs3")
                val downloadResult = downloadWithRetry(
                    url = updateMetadata.url,
                    destFile = tempFile,
                    fileHash = updateMetadata.fileHash,
                )
                if (downloadResult.isFailure) {
                    return@withContext Result.failure(
                        ExtensionManagerException(
                            "Download failed for update of '$packageName'",
                            downloadResult.exceptionOrNull()
                        )
                    )
                }

                // ── 3. Unload old version ────────────────────────────────
                unregisterPlugin(packageName)
                extensionLoader.unloadExtension(entity.filePath)

                // ── 4. Replace file ──────────────────────────────────────
                val destFile = File(extDir, "${packageName}.cs3")
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)

                // ── 5. Load new version ──────────────────────────────────
                val loadResult = extensionLoader.loadExtension(destFile.absolutePath)
                if (loadResult.isFailure) {
                    // Load failed — clean up and return error
                    destFile.delete()
                    return@withContext Result.failure(
                        ExtensionManagerException(
                            "Failed to load updated extension '$packageName'",
                            loadResult.exceptionOrNull()
                        )
                    )
                }

                val pluginLoader = loadResult.getOrThrow()
                val isCloudstream = !isNativeExtensionProvider(pluginLoader)
                registerPlugin(pluginLoader, packageName)

                // ── 6. Update DB record ──────────────────────────────────
                val now = System.currentTimeMillis()
                extensionDao.updateVersion(
                    pn = packageName,
                    v = updateMetadata.version,
                    fp = destFile.absolutePath,
                    ts = now,
                )

                val updated = entity.copy(
                    version = updateMetadata.version,
                    filePath = destFile.absolutePath,
                    lastUpdatedAt = now,
                    isCloudstream = isCloudstream,
                )

                Log.i(TAG, "Updated extension '$packageName' to v${updateMetadata.version}")
                Result.success(updated)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update extension: $packageName", e)
                Result.failure(ExtensionManagerException("Update failed", e))
            }
        }

    /**
     * Batch-update all extensions that have available updates.
     *
     * @return A map of package name → update result for every attempted update.
     */
    suspend fun updateAllExtensions(): Map<String, Result<InstalledExtensionEntity>> =
        withContext(Dispatchers.IO) {
            val results = ConcurrentHashMap<String, Result<InstalledExtensionEntity>>()
            val updates = checkForUpdates()

            for (update in updates) {
                val result = updateExtension(update.packageName)
                results[update.packageName] = result
                if (result.isSuccess) {
                    Log.i(TAG, "Auto-updated '${update.packageName}' " +
                        "${update.currentVersion} → ${update.availableVersion}")
                } else {
                    Log.w(TAG, "Auto-update failed for '${update.packageName}': " +
                        "${result.exceptionOrNull()?.message}")
                }
            }

            results
        }

    // ── Query ───────────────────────────────────────────────────────────────

    /**
     * Observe all installed extensions as a reactive [Flow].
     * Emits the current list whenever the extensions table changes.
     */
    fun observeExtensions(): Flow<List<InstalledExtensionEntity>> = extensionDao.getAll()

    /**
     * Get a single installed extension by package name.
     *
     * @param packageName The extension's package name.
     * @return The entity, or `null` if not installed.
     */
    suspend fun getExtension(packageName: String): InstalledExtensionEntity? =
        extensionDao.get(packageName)

    /**
     * Get all currently enabled extensions.
     *
     * @return List of enabled extensions.
     */
    suspend fun getEnabledExtensions(): List<InstalledExtensionEntity> =
        extensionDao.getEnabled()

    /**
     * Get all loaded [ExtensionProvider] instances from enabled extensions.
     *
     * Only returns native [ExtensionProvider] implementations, not Cloudstream
     * adapters. For all providers (including Cloudstream), use
     * [ExtensionRegistry.getAllProviders].
     *
     * @return The list of currently loaded native extension providers.
     */
    fun getEnabledProviders(): List<ExtensionProvider> {
        return nativeAdapterMap.values.map { it.extensionProvider }
    }

    // ── Auto-update ────────────────────────────────────────────────────────

    /**
     * Check for and apply updates in the background on app launch.
     *
     * Designed to be called from [com.streamflixreborn.streamflix.extensions.startup.ExtensionInitializer].
     *
     * **Behavior:**
     * 1. Runs on [Dispatchers.IO] (fire-and-forget from the caller)
     * 2. Only checks if the last check was > 24 hours ago
     * 3. Finds available updates
     * 4. Automatically downloads and installs them (no user intervention)
     * 5. Logs results (notifications can be added in a future phase)
     */
    suspend fun checkAutoUpdatesOnLaunch() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastCheck = prefs.getLong(PREF_LAST_AUTO_UPDATE_CHECK, 0L)
                val now = System.currentTimeMillis()

                if (now - lastCheck < AUTO_UPDATE_INTERVAL_MS) {
                    Log.d(TAG, "Auto-update check skipped — last check was ${(now - lastCheck) / 1000}s ago")
                    return@withContext
                }

                prefs.edit().putLong(PREF_LAST_AUTO_UPDATE_CHECK, now).apply()

                Log.i(TAG, "Starting auto-update check...")
                val updates = checkForUpdates()

                if (updates.isEmpty()) {
                    Log.i(TAG, "No updates available")
                    return@withContext
                }

                Log.i(TAG, "Found ${updates.size} update(s), installing...")

                for (update in updates) {
                    val result = updateExtension(update.packageName)
                    if (result.isSuccess) {
                        Log.i(TAG, "Auto-updated '${update.packageName}' " +
                            "${update.currentVersion} → ${update.availableVersion}")
                    } else {
                        Log.w(TAG, "Auto-update failed for '${update.packageName}': " +
                            "${result.exceptionOrNull()?.message}")
                    }
                }

                Log.i(TAG, "Auto-update check complete")

            } catch (e: Exception) {
                Log.e(TAG, "Auto-update check failed", e)
                // Never crash the app — this is a background operation
            }
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Returns the directory where installed extension .cs3 files are stored.
     */
    private fun getExtensionsDir(): File = File(context.filesDir, EXTENSIONS_DIR)

    /**
     * Download a file from [url] to [destFile] with retry and optional
     * SHA-256 hash verification.
     *
     * @param url      The download URL.
     * @param destFile The destination file.
     * @param fileHash Optional SHA-256 hex string for verification.
     * @return [Result.success] with the downloaded file, or [Result.failure].
     */
    private suspend fun downloadWithRetry(
        url: String,
        destFile: File,
        fileHash: String?,
    ): Result<File> {
        var lastError: Throwable = ExtensionManagerException("Download failed after $MAX_DOWNLOAD_RETRIES attempts")

        for (attempt in 1..MAX_DOWNLOAD_RETRIES) {
            try {
                downloadFile(url, destFile)

                // Verify hash if provided
                if (!fileHash.isNullOrBlank()) {
                    val computedHash = computeSha256(destFile)
                    if (!computedHash.equals(fileHash, ignoreCase = true)) {
                        throw ExtensionManagerException(
                            "Hash mismatch for $url: expected=$fileHash, computed=$computedHash"
                        )
                    }
                }

                return Result.success(destFile)

            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Download attempt $attempt/$MAX_DOWNLOAD_RETRIES failed for $url: ${e.message}")

                if (attempt < MAX_DOWNLOAD_RETRIES) {
                    val delayMs = BASE_RETRY_DELAY_MS * (1L shl (attempt - 1))
                    delay(delayMs)
                }
            }
        }

        // All attempts failed — clean up partial download
        if (destFile.exists()) {
            destFile.delete()
        }
        return Result.failure(lastError)
    }

    /**
     * Perform the actual HTTP download from [url] to [destFile].
     */
    private suspend fun downloadFile(url: String, destFile: File) {
        val client = NetworkClient.default
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ExtensionManagerException("HTTP ${response.code} downloading $url")
        }

        val body = response.body ?: throw ExtensionManagerException("Empty response body from $url")

        val sink: BufferedSink = Okio.buffer(Okio.sink(destFile))
        sink.use { s ->
            s.writeAll(body.source())
        }
    }

    /**
     * Compute the SHA-256 hex digest of a file.
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        FileInputStream(file).use { fis ->
            DigestInputStream(fis, digest).use { dis ->
                val buffer = ByteArray(8192)
                while (dis.read(buffer) != -1) { /* consume stream */ }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Determine whether a loaded plugin implements the native [ExtensionProvider]
     * interface (as opposed to a Cloudstream MainAPI plugin).
     */
    private fun isNativeExtensionProvider(pluginLoader: PluginClassLoader): Boolean {
        val instance = pluginLoader.pluginInstance ?: return false
        return instance is ExtensionProvider
    }

    /**
     * Register a loaded plugin with the [ExtensionRegistry].
     *
     * The plugin instance is cast to [ExtensionProvider] if possible;
     * otherwise it is treated as a Cloudstream plugin and wrapped in
     * a [CloudstreamAdapter].
     */
    private fun registerPlugin(pluginLoader: PluginClassLoader, packageName: String) {
        val instance = pluginLoader.pluginInstance ?: return

        if (instance is ExtensionProvider) {
            val adapter = ExtensionProviderAdapter(instance)
            extensionRegistry.registerAdapter(adapter)
            nativeAdapterMap[packageName] = adapter
        } else if (hasCloudstreamApi(instance)) {
            val adapter = CloudstreamAdapter(
                pluginInstance = instance,
                classLoader = pluginLoader.delegate,
            )
            extensionRegistry.registerCloudstream(adapter)
            cloudstreamAdapterMap[packageName] = adapter
        } else {
            Log.w(TAG, "Unknown plugin type for '$packageName': ${instance::class.java.name}")
        }
    }

    /**
     * Unregister a plugin from [ExtensionRegistry] by package name.
     */
    private fun unregisterPlugin(packageName: String) {
        nativeAdapterMap.remove(packageName)?.let { adapter ->
            extensionRegistry.unregisterAdapter(adapter)
        }
        cloudstreamAdapterMap.remove(packageName)?.let { adapter ->
            extensionRegistry.unregisterCloudstream(adapter)
        }
    }

    /**
     * Check if an object has the Cloudstream MainAPI `load` method.
     */
    private fun hasCloudstreamApi(instance: Any): Boolean = runCatching {
        instance::class.java.getMethod("load", String::class.java)
        true
    }.getOrDefault(false)

    /**
     * Search all enabled repositories for metadata about a newer version
     * of the given extension.
     *
     * @param packageName The extension's package name (internal name).
     * @return The [ExtensionMetadata] for the update, or `null` if unavailable.
     */
    private suspend fun findUpdateMetadata(packageName: String): ExtensionMetadata? {
        val repoResults = repoManager.refreshAllRepositories()

        for ((_, metadataResult) in repoResults) {
            val metadataList = metadataResult.getOrNull() ?: continue
            val installed = extensionDao.get(packageName) ?: continue

            for (metadata in metadataList) {
                if (metadata.internalName == packageName && metadata.version > installed.version) {
                    return metadata
                }
            }
        }

        return null
    }
}

/**
 * Exception thrown by [ExtensionManager] operations.
 * Wraps the underlying cause for diagnostics.
 *
 * @param message Human-readable description of the failure.
 * @param cause   The underlying exception, if any.
 */
class ExtensionManagerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
