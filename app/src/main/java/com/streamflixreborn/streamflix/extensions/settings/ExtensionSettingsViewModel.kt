// file: extensions/settings/ExtensionSettingsViewModel.kt
package com.streamflixreborn.streamflix.extensions.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.extensions.adapter.ExtensionRegistry
import com.streamflixreborn.streamflix.extensions.db.ExtensionDatabase
import com.streamflixreborn.streamflix.extensions.db.ExtensionRepoEntity
import com.streamflixreborn.streamflix.extensions.db.InstalledExtensionEntity
import com.streamflixreborn.streamflix.extensions.loader.ExtensionLoader
import com.streamflixreborn.streamflix.extensions.manager.ExtensionManager
import com.streamflixreborn.streamflix.extensions.manager.ExtensionUpdate
import com.streamflixreborn.streamflix.extensions.repo.ExtensionMetadata
import com.streamflixreborn.streamflix.extensions.repo.RepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for extension settings screens (mobile and TV).
 *
 * Manages repository CRUD, extension lifecycle (install/uninstall/update/toggle),
 * and exposes reactive state for the UI via [StateFlow] and [SharedFlow].
 *
 * Components ([RepositoryManager], [ExtensionManager]) are created from the
 * Room database and application context, mirroring [ExtensionInitializer]'s
 * bootstrap pattern.
 */
class ExtensionSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "ExtSettingsVM"

    // ── Components (lazily initialised) ──────────────────────────────────────

    private val db by lazy { ExtensionDatabase.getInstance(getApplication()) }
    private val repoDao by lazy { db.repositoryDao() }
    private val extensionDao by lazy { db.extensionDao() }
    private val repoManager by lazy { RepositoryManager(getApplication(), repoDao) }
    private val extensionLoader by lazy { ExtensionLoader(getApplication()) }
    private val extensionManager by lazy {
        ExtensionManager(
            context = getApplication(),
            extensionDao = extensionDao,
            repoManager = repoManager,
            extensionLoader = extensionLoader,
            extensionRegistry = ExtensionRegistry,
        )
    }

    // ── Reactive state ───────────────────────────────────────────────────────

    private val _repositories = MutableStateFlow<List<ExtensionRepoEntity>>(emptyList())
    val repositories: StateFlow<List<ExtensionRepoEntity>> = _repositories.asStateFlow()

    private val _extensions = MutableStateFlow<List<InstalledExtensionEntity>>(emptyList())
    val extensions: StateFlow<List<InstalledExtensionEntity>> = _extensions.asStateFlow()

    private val _availableUpdates = MutableStateFlow<List<ExtensionUpdate>>(emptyList())
    val availableUpdates: StateFlow<List<ExtensionUpdate>> = _availableUpdates.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _browseResult = MutableStateFlow<List<ExtensionMetadata>>(emptyList())
    val browseResult: StateFlow<List<ExtensionMetadata>> = _browseResult.asStateFlow()

    // ── Initialisation ───────────────────────────────────────────────────────

    init {
        // Observe repository list from DB
        viewModelScope.launch {
            repoManager.observeRepositories().collect { list ->
                _repositories.value = list
            }
        }
        // Observe extension list from DB
        viewModelScope.launch {
            extensionManager.observeExtensions().collect { list ->
                _extensions.value = list
            }
        }
    }

    // ── Repository actions ───────────────────────────────────────────────────

    fun addRepository(url: String) = viewModelScope.launch(Dispatchers.IO) {
        val result = repoManager.addRepository(url.trim())
        if (result.isSuccess) {
            _snackbarMessage.tryEmit("Repository added: ${result.getOrNull()?.name}")
        } else {
            _snackbarMessage.tryEmit(
                "Failed to add repository: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun removeRepository(url: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            repoManager.removeRepository(url)
            _snackbarMessage.tryEmit("Repository removed")
        } catch (e: Exception) {
            Log.e(tag, "Failed to remove repository", e)
            _snackbarMessage.tryEmit("Failed to remove repository: ${e.message}")
        }
    }

    fun setRepositoryEnabled(url: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        try {
            repoManager.setRepositoryEnabled(url, enabled)
        } catch (e: Exception) {
            Log.e(tag, "Failed to toggle repository", e)
        }
    }

    fun refreshRepository(url: String) = viewModelScope.launch(Dispatchers.IO) {
        _isRefreshing.value = true
        val result = repoManager.refreshRepository(url)
        if (result.isSuccess) {
            _snackbarMessage.tryEmit("Repository refreshed")
        } else {
            _snackbarMessage.tryEmit(
                "Refresh failed: ${result.exceptionOrNull()?.message}"
            )
        }
        _isRefreshing.value = false
    }

    fun refreshAll() = viewModelScope.launch(Dispatchers.IO) {
        _isRefreshing.value = true
        try {
            repoManager.refreshAllRepositories()
            _snackbarMessage.tryEmit("All repositories refreshed")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Refresh failed: ${e.message}")
        }
        _isRefreshing.value = false
    }

    fun browseRepository(url: String) = viewModelScope.launch(Dispatchers.IO) {
        _isRefreshing.value = true
        val result = repoManager.refreshRepository(url)
        if (result.isSuccess) {
            _browseResult.value = result.getOrNull() ?: emptyList()
        } else {
            _snackbarMessage.tryEmit(
                "Failed to browse: ${result.exceptionOrNull()?.message}"
            )
            _browseResult.value = emptyList()
        }
        _isRefreshing.value = false
    }

    // ── Extension actions ────────────────────────────────────────────────────

    fun toggleExtension(packageName: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val result = extensionManager.setExtensionEnabled(packageName, enabled)
        if (result.isFailure) {
            _snackbarMessage.tryEmit(
                "Failed to ${if (enabled) "enable" else "disable"} extension: " +
                    result.exceptionOrNull()?.message
            )
        }
    }

    fun installExtension(metadata: ExtensionMetadata) = viewModelScope.launch(Dispatchers.IO) {
        val result = extensionManager.installExtension(metadata)
        if (result.isSuccess) {
            _snackbarMessage.tryEmit("Installed: ${metadata.name}")
        } else {
            _snackbarMessage.tryEmit(
                "Install failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun uninstallExtension(packageName: String) = viewModelScope.launch(Dispatchers.IO) {
        val result = extensionManager.uninstallExtension(packageName)
        if (result.isSuccess) {
            _snackbarMessage.tryEmit("Extension uninstalled")
        } else {
            _snackbarMessage.tryEmit(
                "Uninstall failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun checkForUpdates() = viewModelScope.launch(Dispatchers.IO) {
        _isRefreshing.value = true
        try {
            val updates = extensionManager.checkForUpdates()
            _availableUpdates.value = updates
            if (updates.isEmpty()) {
                _snackbarMessage.tryEmit("All extensions are up to date")
            } else {
                _snackbarMessage.tryEmit("${updates.size} update(s) available")
            }
        } catch (e: Exception) {
            Log.e(tag, "Update check failed", e)
            _snackbarMessage.tryEmit("Update check failed: ${e.message}")
        }
        _isRefreshing.value = false
    }

    fun updateExtension(packageName: String) = viewModelScope.launch(Dispatchers.IO) {
        val result = extensionManager.updateExtension(packageName)
        if (result.isSuccess) {
            _snackbarMessage.tryEmit("Extension updated")
            // Refresh the updates list
            _availableUpdates.value = _availableUpdates.value.filter { it.packageName != packageName }
        } else {
            _snackbarMessage.tryEmit(
                "Update failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun updateAll() = viewModelScope.launch(Dispatchers.IO) {
        _isRefreshing.value = true
        try {
            val results = extensionManager.updateAllExtensions()
            val successCount = results.count { it.value.isSuccess }
            _snackbarMessage.tryEmit("Updated $successCount/${results.size} extension(s)")
            _availableUpdates.value = emptyList()
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Update all failed: ${e.message}")
        }
        _isRefreshing.value = false
    }

    fun forceReloadAll() = viewModelScope.launch(Dispatchers.IO) {
        _isRefreshing.value = true
        try {
            // Reload all enabled extensions
            val enabled = extensionDao.getEnabled()
            for (ext in enabled) {
                try {
                    extensionLoader.reloadExtension(ext.filePath)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to reload ${ext.name}", e)
                }
            }
            _snackbarMessage.tryEmit("All extensions reloaded")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Reload failed: ${e.message}")
        }
        _isRefreshing.value = false
    }

    fun clearCache() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val extDir = java.io.File(getApplication<Application>().filesDir, "extensions")
            var deleted = 0
            if (extDir.exists()) {
                // Remove temp files (*.tmp, *.download) but NOT installed .cs3 extensions
                extDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".tmp") || file.name.endsWith(".download") || file.name.startsWith("._")) {
                        if (file.delete()) deleted++
                    }
                }
            }
            _snackbarMessage.tryEmit("Cleared $deleted temp file(s)")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to clear cache: ${e.message}")
        }
    }

    fun exportLogs() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val logs = buildString {
                appendLine("=== Extension System Logs ===")
                appendLine("Repositories: ${_repositories.value.size}")
                appendLine("Extensions: ${_extensions.value.size}")
                appendLine("Updates: ${_availableUpdates.value.size}")
                appendLine("Providers loaded: ${ExtensionRegistry.getProviderCount()}")
            }
            val file = java.io.File(
                getApplication<Application>().cacheDir,
                "extension_logs_${System.currentTimeMillis()}.txt"
            )
            file.writeText(logs)
            _snackbarMessage.tryEmit("Logs exported to ${file.absolutePath}")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to export logs: ${e.message}")
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Clear the browse result when leaving the browse dialog.
     */
    fun clearBrowseResult() {
        _browseResult.value = emptyList()
    }
}
