# Streamflix Extension Architecture

> **Status:** Proposed  
> **Date:** 2026-07-18  
> **Based on:** Streamflix codebase analysis + Cloudstream 3 architecture
> research

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Package Structure](#2-package-structure)
3. [Data Models](#3-data-models)
4. [Database Schema](#4-database-schema)
5. [Component: Extension SDK](#5-component-extension-sdk)
6. [Component: RepositoryManager](#6-component-repositorymanager)
7. [Component: ExtensionManager](#7-component-extensionmanager)
8. [Component: ExtensionLoader](#8-component-extensionloader)
9. [Component: ProviderAdapter & ExtensionRegistry](#9-component-provideradapter--extensionregistry)
10. [Component: CloudstreamAdapter](#10-component-cloudstreamadapter)
11. [Component: ExtensionInitializer (Startup Flow)](#11-component-extensioninitializer-startup-flow)
12. [Settings UI Architecture](#12-settings-ui-architecture)
13. [Integration Points with Existing Code](#13-integration-points-with-existing-code)
14. [Implementation Sequence](#14-implementation-sequence)
15. [Risk Assessment](#15-risk-assessment)
16. [Testing Strategy](#16-testing-strategy)
17. [Architecture Decision Records](#17-architecture-decision-records)

---

## 1. Architecture Overview

### High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        EXISTING APP CODE                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │HomeVM     │  │SearchVM  │  │MovieVM   │  │ProvidersVM         │  │
│  └─────┬────┘  └────┬─────┘  └────┬─────┘  └──────┬─────────────┘  │
│        │            │             │                │                  │
│        ▼            ▼             ▼                ▼                  │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                    Provider Interface                          │  │
│  │  search() getHome() getMovie() getServers() getVideo() ...     │  │
│  └────────────────────────────────────────────────────────────────┘  │
│        ▲            ▲             ▲                ▲                  │
│        │            │             │                │                  │
│  ┌─────┴────┐  ┌───┴────────┐  ┌─┴────────────┐  ┌┴──────────────┐  │
│  │Built-in  │  │Extension   │  │Cloudstream   │  │TmdbProvider   │  │
│  │Providers │  │Provider-   │  │Adapter       │  │(existing)     │  │
│  │(68 src)  │  │Adapter     │  │(reflection)  │  │               │  │
│  └──────────┘  └─────┬──────┘  └──────┬───────┘  └───────────────┘  │
└──────────────────────┼─────────────────┼────────────────────────────┘
                       │                 │
              ┌────────▼─────┐    ┌──────▼────────┐
              │ExtensionRegistry  │               │
              │(holds loaded     │               │
              │ provider refs)   │               │
              └────────┬─────────┘               │
                       │                         │
              ┌────────▼─────────────────────────▼──────────┐
              │              EXTENSION LAYER                 │
              │  ┌──────────────┐  ┌──────────────────────┐  │
              │  │ExtensionMgr  │  │ExtensionLoader       │  │
              │  │install/update│  │PathClassLoader       │  │
              │  │enable/disable│  │manifest.json parsing │  │
              │  │conflict      │  │plugin isolation      │  │
              │  │resolution    │  │lifecycle mgmt        │  │
              │  └──────┬───────┘  └──────────┬───────────┘  │
              │         │                     │               │
              │  ┌──────▼─────────────────────▼───────────┐  │
              │  │         RepositoryManager              │  │
              │  │  add/remove/enable repos               │  │
              │  │  fetch repo.json / plugins.json         │  │
              │  │  cache metadata, validation            │  │
              │  └────────────────┬───────────────────────┘  │
              │                   │                          │
              │  ┌────────────────▼───────────────────────┐  │
              │  │  ExtensionDatabase (Room)              │  │
              │  │  repositories, extensions tables       │  │
              │  └────────────────────────────────────────┘  │
              └──────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer              | Components                                                   | Responsibility                                               |
| ------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **Existing App**   | ViewModels, Fragments, Adapters                              | Unchanged — consumes `Provider` interface                    |
| **Adapter Layer**  | `ProviderAdapter`, `CloudstreamAdapter`, `ExtensionRegistry` | Bridges extension providers to existing `Provider` interface |
| **Management**     | `ExtensionManager`, `RepositoryManager`                      | Orchestrates install/update/repo operations                  |
| **Loading**        | `ExtensionLoader`, `PluginClassLoader`                       | Loads .cs3 APK files in isolated classloaders                |
| **Persistence**    | `ExtensionDatabase`, DAOs                                    | Room DB for repos and installed extensions                   |
| **Initialization** | `ExtensionInitializer`                                       | Bootstrap on app launch                                      |

### Threading Model

All extension operations use Kotlin Coroutines:

| Operation                      | Dispatcher            | Rationale                                |
| ------------------------------ | --------------------- | ---------------------------------------- |
| APK loading (DEX)              | `Dispatchers.IO`      | File I/O, class loading                  |
| Network (repo fetch, download) | `Dispatchers.IO`      | Network calls                            |
| Plugin method calls            | Caller's context      | Usually `Dispatchers.IO` from ViewModels |
| Class reflection               | `Dispatchers.Default` | CPU-bound for complex reflection         |
| DB operations                  | `Dispatchers.IO`      | Room DB access                           |
| Settings UI                    | `Dispatchers.Main`    | Android UI thread                        |

---

## 2. Package Structure

```
com.streamflixreborn.streamflix.extensions/
│
├── models/
│   ├── ExtensionModels.kt           # All extension data classes
│   └── ExtensionProviderInterface.kt # Interface extension APKs implement
│
├── repo/
│   ├── RepositoryManager.kt         # Repository CRUD, refresh, caching
│   ├── RepositoryFormat.kt          # Cloudstream repo.json/plugins.json parsing
│   └── RepositoryValidator.kt       # Repository URL validation & health check
│
├── manager/
│   ├── ExtensionManager.kt          # Install/uninstall/update/enable/disable
│   └── ExtensionConflictResolver.kt  # Duplicate/conflict resolution logic
│
├── loader/
│   ├── ExtensionLoader.kt           # PathClassLoader management & isolation
│   ├── PluginClassLoader.kt         # Lifecycle wrapper around PathClassLoader
│   └── ManifestParser.kt            # manifest.json extraction & parsing
│
├── adapter/
│   ├── ExtensionProviderAdapter.kt  # ExtensionProvider → Provider bridge
│   ├── ExtensionRegistry.kt         # Central registry of loaded providers
│   ├── ExtensionModelMapper.kt      # Extension* → Streamflix model mapping
│   └── ProviderRegistry.kt          # Merged built-in + extension providers
│
├── cloudstream/
│   ├── CloudstreamAdapter.kt        # Cloudstream MainAPI → Provider bridge
│   ├── CloudstreamModelMapper.kt    # CS3 model → Streamflix model mapping
│   └── CloudstreamReflector.kt      # Reflection helpers for CS3 API calls
│
├── db/
│   ├── ExtensionDatabase.kt         # Room DB for extension data
│   ├── RepositoryDao.kt             # DAO for repositories table
│   ├── ExtensionDao.kt              # DAO for extensions table
│   ├── ExtensionRepoEntity.kt       # Room entity for repositories
│   └── ExtensionConverters.kt       # Room type converters
│
├── startup/
│   └── ExtensionInitializer.kt      # App startup bootstrap
│
└── settings/
    ├── ExtensionSettingsViewModel.kt # ViewModel for settings screens
    ├── ExtensionsMobileFragment.kt   # Mobile extension management UI
    ├── ExtensionsTvFragment.kt       # TV extension management UI
    ├── RepositoriesMobileFragment.kt # Mobile repo management UI
    └── RepositoriesTvFragment.kt     # TV repo management UI
```

### Dependency Direction

```
settings/ → manager/ → loader/ → adapter/ → APP CODE (Provider interface)
                ↓
           repo/ → models/ → db/
                ↓
      cloudstream/ → adapter/
```

---

## 3. Data Models

### 3.1 ExtensionProvider Interface (the contract for extension APKs)

```kotlin
// File: extensions/models/ExtensionProviderInterface.kt
package com.streamflixreborn.streamflix.extensions.models

/**
 * Interface that extension APKs must implement.
 * Extension developers compile against this interface (published as an SDK artifact).
 */
interface ExtensionProvider {
    val name: String
    val logo: String
    val language: String
    val supportedTypes: Set<ExtensionMediaType>

    suspend fun getHome(): List<ExtensionCategory>
    suspend fun search(query: String, page: Int): List<ExtensionSearchResult>
    suspend fun getMovies(page: Int): List<ExtensionMovie>
    suspend fun getTvShows(page: Int): List<ExtensionTvShow>
    suspend fun getMovieDetail(id: String): ExtensionMovie
    suspend fun getTvShowDetail(id: String): ExtensionTvShow
    suspend fun getEpisodesBySeason(seasonId: String): List<ExtensionEpisode>
    suspend fun getServers(id: String, type: ExtensionMediaType): List<ExtensionServer>
    suspend fun getVideo(server: ExtensionServer): ExtensionVideo
    suspend fun getGenre(id: String, page: Int): ExtensionGenre
    suspend fun getPeople(id: String, page: Int): ExtensionPeople
}

enum class ExtensionMediaType {
    MOVIE, TV_SHOW, EPISODE
}
```

### 3.2 Extension Data Classes

```kotlin
// File: extensions/models/ExtensionModels.kt
package com.streamflixreborn.streamflix.extensions.models

data class ExtensionCategory(
    val name: String,
    val items: List<ExtensionSearchResult>,
)

sealed interface ExtensionSearchResult {
    val id: String
    val title: String
    val poster: String?
    val overview: String?
    val rating: Double?
    val quality: String?
    val year: String?
}

data class ExtensionMovie(
    val id: String,
    val title: String,
    val overview: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val released: String? = null,
    val runtime: Int? = null,
    val trailer: String? = null,
    val quality: String? = null,
    val rating: Double? = null,
    val imdbId: String? = null,
    val genres: List<ExtensionGenreRef> = emptyList(),
    val cast: List<ExtensionPeopleRef> = emptyList(),
    val directors: List<ExtensionPeopleRef> = emptyList(),
    val recommendations: List<ExtensionSearchResult> = emptyList(),
) : ExtensionSearchResult

data class ExtensionTvShow(
    val id: String,
    val title: String,
    val overview: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val released: String? = null,
    val runtime: Int? = null,
    val trailer: String? = null,
    val quality: String? = null,
    val rating: Double? = null,
    val imdbId: String? = null,
    val seasons: List<ExtensionSeason> = emptyList(),
    val genres: List<ExtensionGenreRef> = emptyList(),
    val cast: List<ExtensionPeopleRef> = emptyList(),
    val directors: List<ExtensionPeopleRef> = emptyList(),
    val recommendations: List<ExtensionSearchResult> = emptyList(),
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
```

### 3.3 Repository & Extension Metadata

```kotlin
// ---- Repository Formats (Cloudstream compatible) ----
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

// ---- Manifest inside .cs3 archive ----
@Serializable
data class ExtensionManifest(
    @SerialName("pluginClassName")
    val pluginClassName: String,
    val name: String,
    val version: Int,
    @SerialName("requiresResources")
    val requiresResources: Boolean = false,
)
```

---

## 4. Database Schema

### 4.1 Room Entities

```kotlin
// File: extensions/db/ExtensionRepoEntity.kt
@Entity("repositories")
data class ExtensionRepoEntity(
    @PrimaryKey val url: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "last_refreshed")
    val lastRefreshed: Long? = null,
)

// File: extensions/db/InstalledExtensionEntity.kt
@Entity("extensions")
data class InstalledExtensionEntity(
    @PrimaryKey @ColumnInfo(name = "package_name")
    val packageName: String,
    val name: String,
    val version: Int,
    val description: String? = null,
    val author: String? = null,
    val language: String? = null,
    @ColumnInfo(name = "icon_url")
    val iconUrl: String? = null,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "repository_url")
    val repositoryUrl: String,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "is_cloudstream")
    val isCloudstream: Boolean = false,
    @ColumnInfo(name = "installed_at")
    val installedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_updated_at")
    val lastUpdatedAt: Long = System.currentTimeMillis(),
)
```

### 4.2 DAOs

```kotlin
// File: extensions/db/RepositoryDao.kt
@Dao
interface RepositoryDao {
    @Query("SELECT * FROM repositories ORDER BY name ASC")
    fun getAll(): Flow<List<ExtensionRepoEntity>>

    @Query("SELECT * FROM repositories WHERE url = :url")
    suspend fun get(url: String): ExtensionRepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: ExtensionRepoEntity)

    @Delete
    suspend fun delete(repo: ExtensionRepoEntity)

    @Query("UPDATE repositories SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    @Query("UPDATE repositories SET last_refreshed = :ts WHERE url = :url")
    suspend fun setLastRefreshed(url: String, ts: Long)
}

// File: extensions/db/ExtensionDao.kt
@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY name ASC")
    fun getAll(): Flow<List<InstalledExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE package_name = :pn")
    suspend fun get(pn: String): InstalledExtensionEntity?

    @Query("SELECT * FROM extensions WHERE is_enabled = 1")
    suspend fun getEnabled(): List<InstalledExtensionEntity>

    @Query("SELECT * FROM extensions WHERE repository_url = :url")
    suspend fun getByRepo(url: String): List<InstalledExtensionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ext: InstalledExtensionEntity)

    @Delete
    suspend fun delete(ext: InstalledExtensionEntity)

    @Query("UPDATE extensions SET is_enabled = :e WHERE package_name = :pn")
    suspend fun setEnabled(pn: String, e: Boolean)

    @Query("UPDATE extensions SET version = :v, file_path = :fp, last_updated_at = :ts WHERE package_name = :pn")
    suspend fun updateVersion(pn: String, v: Int, fp: String, ts: Long)

    @Query("SELECT COUNT(*) FROM extensions")
    suspend fun count(): Int
}
```

### 4.3 Database Class

```kotlin
// File: extensions/db/ExtensionDatabase.kt
@Database(
    entities = [ExtensionRepoEntity::class, InstalledExtensionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ExtensionDatabase : RoomDatabase() {
    abstract fun repositoryDao(): RepositoryDao
    abstract fun extensionDao(): ExtensionDao

    companion object {
        @Volatile private var INSTANCE: ExtensionDatabase? = null

        fun getInstance(context: Context): ExtensionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ExtensionDatabase::class.java,
                    "streamflix_extensions.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

### 4.4 SQL DDL (for reference)

```sql
CREATE TABLE repositories (
    url TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT DEFAULT '',
    enabled INTEGER DEFAULT 1,
    is_built_in INTEGER DEFAULT 0,
    last_refreshed INTEGER
);

CREATE TABLE extensions (
    package_name TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    version INTEGER NOT NULL,
    description TEXT,
    author TEXT,
    language TEXT,
    icon_url TEXT,
    file_path TEXT NOT NULL,
    repository_url TEXT NOT NULL,
    is_enabled INTEGER DEFAULT 1,
    is_cloudstream INTEGER DEFAULT 0,
    installed_at INTEGER NOT NULL,
    last_updated_at INTEGER NOT NULL
);
```

---

## 5. Component: Extension SDK

### Purpose

A minimal library artifact that extension developers compile against. Contains
only the `ExtensionProvider` interface and data classes — no Android framework
dependencies.

### Location

`extension-sdk/` — a new Android library module in the project.

### Contents

- `ExtensionProvider` interface
- All `Extension*` data classes
- `ExtensionMediaType` enum
- No Android dependencies (pure Kotlin + kotlinx.serialization)

### Dependency for extensions

```kotlin
// In extension APK's build.gradle
dependencies {
    implementation("com.streamflixreborn:extension-sdk:1.0.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

---

## 6. Component: RepositoryManager

### Purpose

Manage extension repositories (add/remove/refresh/validate). Fetches and caches
metadata from Cloudstream-compatible repository URLs.

### Class Design

```kotlin
// File: extensions/repo/RepositoryManager.kt
class RepositoryManager(
    private val context: Context,
    private val repoDao: RepositoryDao,
) {
    // ── Repository CRUD ──────────────────────────────────────────

    /** Validates a URL, fetches repo.json, adds to DB if valid */
    suspend fun addRepository(url: String): Result<ExtensionRepoEntity>

    /** Removes repository and its extensions from DB */
    suspend fun removeRepository(url: String)

    /** Enable/disable a repository (disabling also disables its extensions) */
    suspend fun setRepositoryEnabled(url: String, enabled: Boolean)

    /** Observe all repositories */
    fun observeRepositories(): Flow<List<ExtensionRepoEntity>>

    /** Get a single repository */
    suspend fun getRepository(url: String): ExtensionRepoEntity?

    // ── Refresh ──────────────────────────────────────────────────

    /**
     * Fetch repo.json → plugins.json → cache entries in DB.
     * Returns list of extension metadata available in this repo.
     */
    suspend fun refreshRepository(url: String): Result<List<ExtensionMetadata>>

    /** Refresh all enabled repositories in parallel */
    suspend fun refreshAllRepositories(): Map<String, Result<List<ExtensionMetadata>>>

    // ── Validation ────────────────────────────────────────────────

    /** Check if a URL points to a valid repository */
    suspend fun validateRepository(url: String): RepositoryValidation
}

data class RepositoryValidation(
    val isValid: Boolean,
    val name: String? = null,
    val description: String? = null,
    val pluginCount: Int = 0,
    val error: String? = null,
)

data class ExtensionMetadata(
    val url: String,
    val name: String,
    val internalName: String,
    val version: Int,
    val description: String?,
    val authors: List<String>,
    val language: String?,
    val tvTypes: List<String>?,
    val iconUrl: String?,
    val fileSize: Long?,
    val fileHash: String?,
    val apiVersion: Int?,
    val repositoryUrl: String?,
    val status: Int,
)
```

### Key Methods — Detailed Logic

**`addRepository(url)`** — Step by step:

1. Validate URL format (must be http/https)
2. Fetch `repo.json` from URL (with OkHttp + timeout 15s)
3. Parse JSON → `RepositoryManifest`
4. Validate `manifestVersion` (supported: 1)
5. Create `ExtensionRepoEntity` with name, description, url
6. Insert into DB (upsert)
7. Auto-refresh: fetch `pluginLists` URLs → parse `plugins.json` → cache entries
8. Return `Result.success(repoEntity)`
9. On any failure: return `Result.failure(exception)` — NEVER crash

**`refreshRepository(url)`** — Step by step:

1. Get repo from DB
2. For each URL in `pluginLists`: a. Fetch the URL (OkHttp, timeout 30s) b.
   Parse JSON array → list of `RepositoryPluginEntry` c. Validate each entry
   (URL format, version >= 0, name non-empty) d. Convert to `ExtensionMetadata`
   list
3. Update `lastRefreshed` timestamp in DB
4. Return metadata list
5. On network failure: return cached data if available, otherwise
   `Result.failure`

**`refreshAllRepositories()`** — Step by step:

1. Get all enabled repos from DB
2. Launch parallel coroutines per repo (max 3 at a time via `Semaphore`)
3. Each coroutine calls `refreshRepository()`
4. Return aggregated results map
5. Individual repo failures don't affect others

### Threading

- All methods are `suspend` functions
- Caller expected to use `Dispatchers.IO`
- Internal network calls use OkHttp (non-blocking)

### Error Handling

- All public methods return `Result<T>`
- Network errors, parse errors, invalid URLs → `Result.failure`
- Repository added even if refresh fails (data will be available on next
  refresh)

---

## 7. Component: ExtensionManager

### Purpose

Orchestrates the full lifecycle of installed extensions: install, update,
enable/disable, uninstall. Coordinates between RepositoryManager (for download)
and ExtensionLoader (for loading).

### Class Design

```kotlin
// File: extensions/manager/ExtensionManager.kt
class ExtensionManager(
    private val context: Context,
    private val extensionDao: ExtensionDao,
    private val repoManager: RepositoryManager,
    private val extensionLoader: ExtensionLoader,
    private val extensionRegistry: ExtensionRegistry,
) {
    // ── Install ──────────────────────────────────────────────────

    /** Download .cs3 from URL, verify, load, and register */
    suspend fun installExtension(metadata: ExtensionMetadata): Result<InstalledExtensionEntity>

    /** Install from a local .cs3 file (sideload) */
    suspend fun installFromFile(filePath: String): Result<InstalledExtensionEntity>

    // ── Uninstall ────────────────────────────────────────────────

    /** Unregister, unload, delete file, remove from DB */
    suspend fun uninstallExtension(packageName: String): Result<Unit>

    // ── Enable/Disable ──────────────────────────────────────────

    /** Enable: load if not loaded, register providers */
    suspend fun setExtensionEnabled(packageName: String, enabled: Boolean): Result<Unit>

    // ── Updates ──────────────────────────────────────────────────

    /** Check all repos for newer versions of installed extensions */
    suspend fun checkForUpdates(): List<ExtensionUpdate>

    /** Download new version, replace file, reload */
    suspend fun updateExtension(packageName: String): Result<InstalledExtensionEntity>

    /** Batch update all available */
    suspend fun updateAllExtensions(): Map<String, Result<InstalledExtensionEntity>>

    // ── Query ────────────────────────────────────────────────────

    fun observeExtensions(): Flow<List<InstalledExtensionEntity>>
    suspend fun getExtension(packageName: String): InstalledExtensionEntity?
    suspend fun getEnabledExtensions(): List<InstalledExtensionEntity>
    fun getEnabledProviders(): List<ExtensionProvider>

    // ── Auto-update ──────────────────────────────────────────────

    /** Called on app launch — async background check */
    suspend fun checkAutoUpdatesOnLaunch()
}

data class ExtensionUpdate(
    val packageName: String,
    val currentVersion: Int,
    val availableVersion: Int,
    val metadata: ExtensionMetadata,
)
```

### Key Methods — Detailed Logic

**`installExtension(metadata)`** — Step by step:

1. Check if already installed → compare versions
2. Create download directory (`context.filesDir/extensions/`)
3. Download .cs3 from `metadata.url` using OkHttp with progress
4. Verify file hash if `metadata.fileHash` is provided (SHA-256)
5. Move file to `extensions/${internalName}.cs3`
6. Call `extensionLoader.loadExtension(filePath)`
7. If load succeeds: a. Check plugin type (ExtensionProvider vs Cloudstream) b.
   Register in `ExtensionRegistry` c. Create `InstalledExtensionEntity` d.
   Upsert in DB e. Return success
8. If load fails: a. Delete downloaded file b. Return `Result.failure` with
   descriptive error

**`installFromFile(filePath)`** — Step by step:

1. Copy file to internal storage (`extensions/${name}.cs3`)
2. Load via ExtensionLoader
3. If success: register, persist metadata (isCloudstream determined by runtime
   type check)
4. If failure: clean up, return error
5. Note: ohne hash verification or version info — the manifest from inside the
   .cs3 provides name/version

**`uninstallExtension(packageName)`** — Step by step:

1. Get extension from DB
2. Unregister from `ExtensionRegistry`
3. Call `extensionLoader.unloadExtension(filePath)`
4. Delete .cs3 file from filesystem
5. Delete from DB
6. Return success

**`setExtensionEnabled(packageName, enabled)`**:

- `enabled=true`: Load extension, register provider
- `enabled=false`: Unregister provider, unload extension

**`checkForUpdates()`** — Step by step:

1. Get all installed extensions from DB
2. For each enabled repository: a. Get repository metadata (from cache or
   refresh) b. For each plugin entry matching an installed extension:
   - Compare version codes
   - If available > installed → add to updates list
3. Return list of available updates

**`updateExtension(packageName)`**:

1. Find update metadata from repository cache
2. Download new .cs3
3. Unload old version
4. Replace file
5. Load new version
6. Update DB record (version, filePath, lastUpdatedAt)

**`checkAutoUpdatesOnLaunch()`**:

1. Run on `Dispatchers.IO` in the background
2. Only check if last check was > 24 hours ago (preference flag)
3. Perform checkForUpdates
4. If updates found, auto-download and install (no user intervention)
5. Post notification about updated extensions

### Threading

- All operations on `Dispatchers.IO`
- Multiple downloads can happen in parallel (configurable concurrency,
  default 2)
- Auto-update is fire-and-forget from the startup flow

### Error Handling

- Every public method returns `Result<T>`
- Download failures → retry mechanism (3 attempts with exponential backoff)
- Hash mismatch → file deleted, error returned
- Class loading failure → file kept for debugging, extension disabled
- Never crashes app on any extension error

### Conflict Resolution

```kotlin
// File: extensions/manager/ExtensionConflictResolver.kt
class ExtensionConflictResolver {
    /**
     * Check if two extensions would register providers with overlapping names.
     * Resolution strategy: last-installed wins, user can manually disable.
     */
    fun findConflicts(extensions: List<InstalledExtensionEntity>): List<ExtensionConflict>

    data class ExtensionConflict(
        val packageNameA: String,
        val packageNameB: String,
        val providerName: String,
    )
}
```

---

## 8. Component: ExtensionLoader

### Purpose

The most safety-critical component. Loads .cs3 APK/DEX files into isolated
`PathClassLoader` instances, parses manifests, and instantiates plugin classes
without crashing the app.

### Class Design

```kotlin
// File: extensions/loader/ExtensionLoader.kt
class ExtensionLoader(private val context: Context) {

    // ── State ────────────────────────────────────────────────────
    private val loadedPlugins = ConcurrentHashMap<String, PluginClassLoader>()
    private val mutex = Mutex()  // Serialize load/unload operations per file

    // ── Load ─────────────────────────────────────────────────────

    /**
     * Load a .cs3 file into an isolated classloader.
     * - Creates PathClassLoader
     * - Reads manifest.json from inside archive
     * - Instantiates plugin class via reflection
     * - Returns the plugin instance wrapped in PluginClassLoader
     */
    suspend fun loadExtension(filePath: String): Result<PluginClassLoader>

    // ── Unload ───────────────────────────────────────────────────

    /**
     * Unload a previously loaded extension.
     * Calls beforeUnload() if available, then removes from all maps.
     * Note: Android classloaders cannot be truly unloaded, but we remove
     * all references so GC can collect them.
     */
    suspend fun unloadExtension(filePath: String): Result<Unit>

    // ── Query ────────────────────────────────────────────────────

    fun getLoadedExtensions(): List<PluginClassLoader>
    fun isLoaded(filePath: String): Boolean
    fun getPluginByProviderName(name: String): PluginClassLoader?

    // ── Reload ───────────────────────────────────────────────────

    /** Unload then reload (for updates or recovery) */
    suspend fun reloadExtension(filePath: String): Result<PluginClassLoader>
}

// File: extensions/loader/PluginClassLoader.kt
class PluginClassLoader(
    filePath: String,
    parent: ClassLoader,
    val manifest: ExtensionManifest,
) {
    internal val delegate: PathClassLoader = PathClassLoader(filePath, parent)
    internal var pluginInstance: Any? = null

    fun <T> getPlugin(): T? {
        @Suppress("UNCHECKED_CAST")
        return pluginInstance as? T
    }

    val name: String get() = manifest.name
    val version: Int get() = manifest.version
    val isLoaded: Boolean get() = pluginInstance != null
}
```

### Loading Sequence — Detailed

```
loadExtension(filePath):
│
├── 1. ACQUIRE mutex (per-file locking to prevent duplicate loads)
│
├── 2. VALIDATE file exists, is readable, has .cs3 or .zip extension
│
├── 3. SET file read-only
│   └── try { file.setReadOnly() } catch: log warning, continue
│   └── Android 14+ requires read-only DEX files
│
├── 4. CREATE PathClassLoader
│   └── val loader = PathClassLoader(filePath, context.classLoader)
│
├── 5. READ manifest.json from inside the archive
│   ├── loader.getResourceAsStream("manifest.json") ?: return failure("No manifest")
│   ├── Parse JSON → ExtensionManifest
│   └── Validate: pluginClassName not blank, version >= 1
│
├── 6. LOAD plugin class
│   ├── val pluginClass = loader.loadClass(manifest.pluginClassName)
│   ├── val constructor = pluginClass.getDeclaredConstructor()
│   └── val instance = constructor.newInstance()
│
├── 7. STORE in maps
│   ├── Store PathClassLoader reference (keeps GC from collecting it)
│   └── Create PluginClassLoader wrapper
│
├── 8. CALL lifecycle: pluginInstance.load() (if method exists)
│   └── Use reflection: instance::class.java.getMethod("load").invoke(instance)
│   └── If method doesn't exist, skip gracefully
│
├── 9. RETURN PluginClassLoader
│
└── ON ANY ERROR:
    ├── Log full stack trace
    ├── Clean up partial state
    └── Return Result.failure(PluginLoadException(...))
```

### Unloading Sequence

```
unloadExtension(filePath):
│
├── 1. ACQUIRE mutex
│
├── 2. FIND PluginClassLoader
│
├── 3. CALL beforeUnload() if available (reflection)
│
├── 4. REMOVE from loadedPlugins map
│
├── 5. RCY: Set pluginInstance = null for GC
│   └── (ClassLoader itself cannot be GC'd until all classes are unreferenced)
│
└── RETURN success
```

### Isolation Guarantees

| Risk                            | Mitigation                                                   |
| ------------------------------- | ------------------------------------------------------------ |
| Plugin crashes in getHome()     | ProviderAdapter wraps every call in `runCatching`            |
| Plugin uses excessive memory    | Each plugin has its own classloader; app's heap is shared    |
| Plugin throws in constructor    | Caught by try/catch(Throwable) in loadExtension              |
| Plugin has missing dependencies | PathClassLoader delegates to app classloader for shared libs |
| Corrupt .cs3 file               | File existence and readability checked before loading        |
| Android 14 DEX restrictions     | file.setReadOnly() before loading                            |
| Plugin conflicts with another   | ExtensionConflictResolver detects name collisions            |

---

## 9. Component: ProviderAdapter & ExtensionRegistry

### 9.1 ExtensionRegistry

```kotlin
// File: extensions/adapter/ExtensionRegistry.kt
/**
 * Central registry of all loaded extension providers.
 * Analogous to Cloudstream's APIHolder.
 * Thread-safe singleton.
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

    /** All extension-based Providers (wrapped in adapters) */
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
```

### 9.2 ExtensionProviderAdapter

```kotlin
// File: extensions/adapter/ExtensionProviderAdapter.kt
/**
 * Adapts an ExtensionProvider (from an extension APK)
 * to the existing Streamflix Provider interface.
 * Every method wraps in runCatching — extension errors never crash the app.
 */
class ExtensionProviderAdapter(
    private val extensionProvider: ExtensionProvider,
    private val mapper: ExtensionModelMapper = ExtensionModelMapper(),
) : Provider {

    override val baseUrl: String get() = ""
    override val name: String get() = extensionProvider.name
    override val logo: String get() = extensionProvider.logo
    override val language: String get() = extensionProvider.language

    override suspend fun getHome(): List<Category> = runCatching {
        extensionProvider.getHome().map { mapper.toStreamflixCategory(it) }
    }.getOrDefault(emptyList())

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = runCatching {
        extensionProvider.search(query, page).map { mapper.toStreamflixItem(it) }
    }.getOrDefault(emptyList())

    override suspend fun getMovies(page: Int): List<Movie> = runCatching {
        extensionProvider.getMovies(page).map { mapper.toStreamflixMovie(it) }
    }.getOrDefault(emptyList())

    override suspend fun getTvShows(page: Int): List<TvShow> = runCatching {
        extensionProvider.getTvShows(page).map { mapper.toStreamflixTvShow(it) }
    }.getOrDefault(emptyList())

    override suspend fun getMovie(id: String): Movie = runCatching {
        mapper.toStreamflixMovie(extensionProvider.getMovieDetail(id))
    }.getOrThrow() // Re-throw for the caller to handle (empty states in UI)

    override suspend fun getTvShow(id: String): TvShow = runCatching {
        mapper.toStreamflixTvShow(extensionProvider.getTvShowDetail(id))
    }.getOrThrow()

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = runCatching {
        extensionProvider.getEpisodesBySeason(seasonId).map { mapper.toStreamflixEpisode(it) }
    }.getOrDefault(emptyList())

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = runCatching {
        val mediaType = when (videoType) {
            is Video.Type.Movie -> ExtensionMediaType.MOVIE
            is Video.Type.Episode -> ExtensionMediaType.TV_SHOW
        }
        extensionProvider.getServers(id, mediaType).map { mapper.toStreamflixServer(it) }
    }.getOrDefault(emptyList())

    override suspend fun getVideo(server: Video.Server): Video = runCatching {
        extensionProvider.getVideo(mapper.fromStreamflixServer(server)).let {
            mapper.toStreamflixVideo(it)
        }
    }.getOrThrow()

    override suspend fun getGenre(id: String, page: Int): Genre = runCatching {
        mapper.toStreamflixGenre(extensionProvider.getGenre(id, page))
    }.getOrThrow()

    override suspend fun getPeople(id: String, page: Int): People = runCatching {
        mapper.toStreamflixPeople(extensionProvider.getPeople(id, page))
    }.getOrThrow()
}
```

### 9.3 ExtensionModelMapper

```kotlin
// File: extensions/adapter/ExtensionModelMapper.kt
class ExtensionModelMapper {
    fun toStreamflixCategory(ext: ExtensionCategory): Category = Category(
        name = ext.name,
        list = ext.items.map { toStreamflixItem(it) },
    )

    fun toStreamflixItem(result: ExtensionSearchResult): AppAdapter.Item = when (result) {
        is ExtensionMovie -> toStreamflixMovie(result)
        is ExtensionTvShow -> toStreamflixTvShow(result)
    }

    fun toStreamflixMovie(ext: ExtensionMovie): Movie = Movie(
        id = ext.id,
        title = ext.title,
        overview = ext.overview,
        poster = ext.poster,
        banner = ext.banner,
        released = ext.released,
        runtime = ext.runtime,
        trailer = ext.trailer,
        quality = ext.quality,
        rating = ext.rating,
        imdbId = ext.imdbId,
    ).apply {
        genres = ext.genres.map { Genre(it.id, it.name) }
        directors = ext.directors.map { People(it.id, it.name, it.image) }
        cast = ext.cast.map { People(it.id, it.name, it.image) }
        recommendations = ext.recommendations.map { toStreamflixItem(it) }
        itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM // caller should override
    }

    fun toStreamflixTvShow(ext: ExtensionTvShow): TvShow = TvShow(
        id = ext.id,
        title = ext.title,
        overview = ext.overview,
        poster = ext.poster,
        banner = ext.banner,
        released = ext.released,
        runtime = ext.runtime,
        trailer = ext.trailer,
        quality = ext.quality,
        rating = ext.rating,
        imdbId = ext.imdbId,
        seasons = ext.seasons.map { season ->
            Season(
                id = season.id,
                number = season.number,
                title = season.title,
                poster = season.poster,
                episodes = season.episodes.map { ep ->
                    Episode(
                        id = ep.id,
                        number = ep.number,
                        title = ep.title,
                        poster = ep.poster,
                        overview = ep.overview,
                    ).apply {
                        released = ep.released?.toCalendar()
                    }
                },
            )
        },
    ).apply {
        genres = ext.genres.map { Genre(it.id, it.name) }
        directors = ext.directors.map { People(it.id, it.name, it.image) }
        cast = ext.cast.map { People(it.id, it.name, it.image) }
        recommendations = ext.recommendations.map { toStreamflixItem(it) }
        itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM // caller should override
    }

    fun toStreamflixEpisode(ext: ExtensionEpisode): Episode = Episode(
        id = ext.id,
        number = ext.number,
        title = ext.title,
        poster = ext.poster,
        overview = ext.overview,
    ).apply { released = ext.released?.toCalendar() }

    fun toStreamflixGenre(ext: ExtensionGenre): Genre = Genre(
        id = ext.id,
        name = ext.name,
        shows = ext.shows.map { toStreamflixItem(it) as Show },
    )

    fun toStreamflixPeople(ext: ExtensionPeople): People = People(
        id = ext.id,
        name = ext.name,
        image = ext.image,
        biography = ext.biography,
        filmography = ext.filmography.map { toStreamflixItem(it) as Show },
    )

    fun toStreamflixServer(ext: ExtensionServer): Video.Server = Video.Server(
        id = ext.id,
        name = ext.name,
        src = ext.src,
    )

    fun fromStreamflixServer(server: Video.Server): ExtensionServer = ExtensionServer(
        id = server.id,
        name = server.name,
        src = server.src,
    )

    fun toStreamflixVideo(ext: ExtensionVideo): Video = Video(
        source = ext.source,
        subtitles = ext.subtitles.map { sub ->
            Video.Subtitle(
                label = sub.label,
                file = sub.file,
                default = sub.isDefault,
            )
        },
        headers = ext.headers,
        type = ext.type,
    )
}
```

### 9.4 ProviderRegistry (Merged Provider View)

```kotlin
// File: extensions/adapter/ProviderRegistry.kt
/**
 * Merges built-in providers with extension providers into a single view.
 * This is the replacement for Provider.providers in the companion object.
 * Minimal change to existing code — just swap the lookup source.
 */
object ProviderRegistry {

    fun allProviders(): Map<Provider, Provider.ProviderSupport> = buildMap {
        // Built-in providers (existing)
        putAll(Provider.providers)

        // Extension providers (from loaded extensions)
        ExtensionRegistry.getAllProviders().forEach { provider ->
            put(provider, Provider.ProviderSupport(
                movies = true,  // Could determine from supportedTypes
                tvShows = true,
            ))
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
```

---

## 10. Component: CloudstreamAdapter

### Purpose

Provide backward compatibility with Cloudstream 3 extension APKs (.cs3 files).
Cloudstream plugins implement `MainAPI` (from `com.lagradost.cloudstream3`) with
a different API surface than Streamflix. This adapter bridges the gap using
reflection (since CS3 classes are only available inside the plugin's classloader
at runtime).

### Class Design

```kotlin
// File: extensions/cloudstream/CloudstreamAdapter.kt
/**
 * Adapts a Cloudstream 3 MainAPI plugin to the Streamflix Provider interface.
 * Uses reflection to interact with the plugin since CS3 SDK classes are
 * only available in the plugin's PathClassLoader, not at compile time.
 */
class CloudstreamAdapter(
    private val pluginInstance: Any,
    private val classLoader: ClassLoader,
    private val reflector: CloudstreamReflector = CloudstreamReflector(),
    private val mapper: CloudstreamModelMapper = CloudstreamModelMapper(),
) : Provider {

    // Reflection-based property access
    override val name: String get() = reflector.getStringProperty(pluginInstance, "name") ?: "Unknown"
    override val language: String get() = reflector.getStringProperty(pluginInstance, "lang") ?: "en"
    override val logo: String get() = ""  // CS3 doesn't have logo concept
    override val baseUrl: String get() = reflector.getStringProperty(pluginInstance, "mainUrl") ?: ""

    override suspend fun getHome(): List<Category> = runCatching {
        val mainPageData = reflector.getProperty<List<*>>(pluginInstance, "mainPage") ?: emptyList()
        // CS3 uses getMainPage(page, request) which returns HomePageResponse
        // HomePageResponse contains lists of HomePageList which contain SearchResponse items
        mapper.toStreamflixCategories(
            mainPageData,
            pluginInstance,
            reflector,
        )
    }.getOrDefault(emptyList())

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = runCatching {
        val method = pluginInstance::class.java.getMethod("search", String::class.java, Int::class.java)
        val result = method.invoke(pluginInstance, query, page)
        mapper.toStreamflixSearchResults(result)
    }.getOrDefault(emptyList())

    override suspend fun getMovie(id: String): Movie = runCatching {
        val method = pluginInstance::class.java.getMethod("load", String::class.java)
        val result = method.invoke(pluginInstance, id)
        mapper.toStreamflixMovie(result)
    }.getOrThrow()

    override suspend fun getTvShow(id: String): TvShow = runCatching {
        val method = pluginInstance::class.java.getMethod("load", String::class.java)
        val result = method.invoke(pluginInstance, id)
        mapper.toStreamflixTvShow(result)
    }.getOrThrow()

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = runCatching {
        // CS3 uses loadLinks(data, isCasting, subtitleCallback, linkCallback)
        // We need to call it and collect links from the callback
        reflector.loadLinks(pluginInstance, id, videoType)
    }.getOrDefault(emptyList())

    override suspend fun getVideo(server: Video.Server): Video = runCatching {
        // If the server has a CS3 link, convert it
        server.video ?: Video(source = "")
    }.getOrThrow()

    // ... remaining methods with similar reflection-based bridging
}
```

### CloudstreamReflector (Reflection Helpers)

```kotlin
// File: extensions/cloudstream/CloudstreamReflector.kt
/**
 * Type-safe reflection utilities for interacting with Cloudstream MainAPI.
 * Centralizes all reflection to make error handling consistent.
 */
class CloudstreamReflector {

    fun getStringProperty(obj: Any, name: String): String? = runCatching {
        val field = obj::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(obj) as? String
    }.getOrNull()

    fun <T> getProperty(obj: Any, name: String): T? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val field = obj::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(obj) as? T
    }.getOrNull()

    suspend fun loadLinks(
        plugin: Any,
        dataId: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        val links = mutableListOf<Video.Server>()
        return try {
            val method = plugin::class.java.getMethod(
                "loadLinks",
                String::class.java,
                Boolean::class.java,
                Function1::class.java, // subtitleCallback
                Function1::class.java, // linkCallback
            )
            method.invoke(plugin, dataId, false,
                { _: Any -> }, // ignore subtitles for now
                { link: Any ->
                    val name = getStringProperty(link, "name") ?: ""
                    val url = getStringProperty(link, "url") ?: ""
                    val server = Video.Server(id = url, name = name)
                    server.video = Video(source = url)
                    links.add(server)
                }
            )
            links
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

### CloudstreamModelMapper

```kotlin
// File: extensions/cloudstream/CloudstreamModelMapper.kt
class CloudstreamModelMapper(private val reflector: CloudstreamReflector = CloudstreamReflector()) {

    fun toStreamflixMovie(loadResponse: Any?): Movie? {
        if (loadResponse == null) return null
        return Movie(
            id = reflector.getStringProperty(loadResponse, "url") ?: "",
            title = reflector.getStringProperty(loadResponse, "name") ?: "",
            overview = reflector.getStringProperty(loadResponse, "plot") ?: "",
            poster = reflector.getStringProperty(loadResponse, "posterUrl") ?: "",
            banner = reflector.getStringProperty(loadResponse, "bannerUrl") ?: "",
            rating = reflector.getProperty<Double>(loadResponse, "rating"),
            released = reflector.getStringProperty(loadResponse, "year")?.let { "$it-01-01" },
        ).apply { itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM }
    }

    fun toStreamflixTvShow(loadResponse: Any?): TvShow? {
        if (loadResponse == null) return null
        // Map CS3 seasons/episodes from loadResponse
        return TvShow(
            id = reflector.getStringProperty(loadResponse, "url") ?: "",
            title = reflector.getStringProperty(loadResponse, "name") ?: "",
            overview = reflector.getStringProperty(loadResponse, "plot") ?: "",
            poster = reflector.getStringProperty(loadResponse, "posterUrl") ?: "",
            banner = reflector.getStringProperty(loadResponse, "bannerUrl") ?: "",
            rating = reflector.getProperty<Double>(loadResponse, "rating"),
        ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
    }

    fun toStreamflixCategories(
        mainPageData: List<*>,
        plugin: Any,
        reflector: CloudstreamReflector,
    ): List<Category> = mainPageData.mapNotNull { page ->
        val name = reflector.getStringProperty(page!!, "name") ?: return@mapNotNull null
        Category(name = name, list = emptyList())
    }

    fun toStreamflixSearchResults(searchResponse: Any?): List<AppAdapter.Item> {
        // Unchecked cast to List<*>, then map each to Streamflix models
        val list = (searchResponse as? List<*>) ?: return emptyList()
        return list.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val type = reflector.getProperty<Enum<*>>(item, "type")
            val typeName = type?.name ?: ""
            when {
                typeName.contains("Movie") -> toStreamflixMovie(item)
                typeName.contains("Series") || typeName.contains("Anime") -> toStreamflixTvShow(item)
                else -> toStreamflixMovie(item)
            }
        }
    }
}
```

### Cloudstream Compatibility Notes

| Feature                     | Support Level | Details                                                                       |
| --------------------------- | ------------- | ----------------------------------------------------------------------------- |
| `.cs3` file format          | ✅ Full       | Same format: ZIP with manifest.json + classes.dex                             |
| `repo.json` format          | ✅ Full       | Same JSON structure parsed by RepositoryManager                               |
| `plugins.json` format       | ✅ Full       | Same JSON structure parsed by RepositoryManager                               |
| `MainAPI.search()`          | ✅ Supported  | Via reflection                                                                |
| `MainAPI.load()`            | ✅ Supported  | Maps to getMovie/getTvShow                                                    |
| `MainAPI.loadLinks()`       | ✅ Supported  | Via reflection callback collection                                            |
| `MainAPI.getMainPage()`     | ✅ Partial    | Category names mapped, items need HomePageResponse parsing                    |
| `MainAPI.quickSearch()`     | ❌ Not used   | Not needed for Streamflix's search flow                                       |
| `@CloudstreamPlugin`        | ✅ Supported  | Annotation used for class discovery at build time; runtime uses manifest.json |
| `requiresResources`         | ⚠️ Partial    | Android resources may not render correctly without CS3's layout system        |
| `BasePlugin.load(context)`  | ✅ Supported  | Called during ExtensionLoader.loadExtension()                                 |
| `BasePlugin.beforeUnload()` | ✅ Supported  | Called during unloadExtension()                                               |

---

## 11. Component: ExtensionInitializer (Startup Flow)

### Purpose

Bootstraps the extension system on app launch. Loads previously installed
extensions, refreshes repository metadata, and checks for updates — all without
blocking the UI.

### Class Design

```kotlin
// File: extensions/startup/ExtensionInitializer.kt
/**
 * Initializes the extension system at app startup.
 * Designed to be non-blocking — app can start with zero extension providers
 * and they become available as they load.
 */
object ExtensionInitializer {

    private const val TAG = "ExtensionInit"
    private const val EXTENSIONS_DIR = "extensions"

    /**
     * Called from StreamFlixApp.onCreate() after existing initialization.
     * Runs on applicationScope with Dispatchers.IO.
     */
    suspend fun init(context: Context) {
        Log.i(TAG, "Initializing extension system...")
        val startTime = System.currentTimeMillis()

        // 1. Initialize database
        val db = ExtensionDatabase.getInstance(context)
        val repoDao = db.repositoryDao()
        val extensionDao = db.extensionDao()
        Log.d(TAG, "Extension database initialized")

        // 2. Ensure extensions directory exists
        val extDir = File(context.filesDir, EXTENSIONS_DIR)
        if (!extDir.exists()) extDir.mkdirs()

        // 3. Create components
        val repoManager = RepositoryManager(context, repoDao)
        val extensionLoader = ExtensionLoader(context)
        val extensionManager = ExtensionManager(
            context = context,
            extensionDao = extensionDao,
            repoManager = repoManager,
            extensionLoader = extensionLoader,
            extensionRegistry = ExtensionRegistry,
        )

        // 4. Seed default repositories (if first launch)
        if (repoDao.count() == 0) {
            seedDefaultRepositories(repoDao)
        }

        // 5. Load previously installed extensions (parallel, max 3 at a time)
        val installedExtensions = extensionDao.getEnabled()
        Log.i(TAG, "Loading ${installedExtensions.size} installed extensions...")

        val semaphore = Semaphore(3)
        val loadJobs = installedExtensions.map { ext ->
            async(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    val result = extensionLoader.loadExtension(ext.filePath)
                    result.onSuccess { pluginLoader ->
                        registerPlugin(ext, pluginLoader)
                        Log.i(TAG, "Loaded extension: ${ext.name} v${ext.version}")
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to load ${ext.name}: ${error.message}")
                        // Disable the extension so it doesn't block future launches
                        extensionDao.setEnabled(ext.packageName, false)
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
        loadJobs.awaitAll()

        // 6. Refresh repository metadata (background, fire-and-forget)
        launch(Dispatchers.IO) {
            repoManager.refreshAllRepositories()
            Log.i(TAG, "Repository metadata refreshed")
        }

        // 7. Check for auto-updates (background, fire-and-forget)
        launch(Dispatchers.IO) {
            extensionManager.checkAutoUpdatesOnLaunch()
            Log.i(TAG, "Auto-update check complete")
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Extension system initialized in ${elapsed}ms with " +
              "${ExtensionRegistry.getProviderCount()} providers")
    }

    private fun registerPlugin(
        ext: InstalledExtensionEntity,
        pluginLoader: PluginClassLoader,
    ) {
        val instance = pluginLoader.pluginInstance ?: return

        // Check if it's a native ExtensionProvider
        if (instance is ExtensionProvider) {
            val adapter = ExtensionProviderAdapter(instance)
            ExtensionRegistry.registerAdapter(adapter)
            return
        }

        // Check if it's a Cloudstream plugin (has MainAPI-like methods)
        if (hasCloudstreamApi(instance)) {
            val adapter = CloudstreamAdapter(
                pluginInstance = instance,
                classLoader = pluginLoader.delegate,
            )
            ExtensionRegistry.registerCloudstream(adapter)
            return
        }

        Log.w(TAG, "Unknown plugin type: ${instance::class.java.name}")
    }

    private fun hasCloudstreamApi(instance: Any): Boolean = runCatching {
        instance::class.java.getMethod("load", String::class.java)
        true
    }.getOrDefault(false)

    private suspend fun seedDefaultRepositories(repoDao: RepositoryDao) {
        val defaultRepos = listOf(
            ExtensionRepoEntity(
                url = "https://raw.githubusercontent.com/streamflix-reborn/extensions/main/repo.json",
                name = "Streamflix Official",
                description = "Official Streamflix extension repository",
                enabled = true,
                isBuiltIn = true,
            ),
            // Optionally seed Cloudstream community repos
            ExtensionRepoEntity(
                url = "https://raw.githubusercontent.com/cloudstream/cloudstream-extension-repo/main/repo.json",
                name = "Cloudstream Community",
                description = "Cloudstream community extensions (experimental)",
                enabled = false,  // Opt-in
                isBuiltIn = true,
            ),
        )
        defaultRepos.forEach { repoDao.upsert(it) }
    }
}
```

### Startup Integration Point

In `StreamFlixApp.kt`, add one line to the existing `applicationScope.launch`:

```kotlin
// In StreamFlixApp.kt → onCreate()
applicationScope.launch(Dispatchers.IO) {
    AppDatabase.setup(appContext)
    SerienStreamProvider.initialize(appContext)
    AniWorldProvider.initialize(appContext)

    // NEW: Initialize extension system
    ExtensionInitializer.init(this@StreamFlixApp)

    ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
    CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)
}
```

### Startup Sequence Diagram

```
Time ──────────────────────────────────────────────────────────────>
│
├─ ExtensionInitializer.init()
│   ├─ Init DB ──────────────────────────────── (IO, ~50ms)
│   ├─ Ensure ext dir ───────────────────────── (IO, ~5ms)
│   ├─ Seed default repos (first launch only) ─ (IO, ~10ms)
│   ├─ Load installed extensions ────────────── (IO, parallel, ~200-500ms)
│   │   ├─ ext1.cs3 ──→ loadPlugin() ──→ register()
│   │   ├─ ext2.cs3 ──→ loadPlugin() ──→ register()
│   │   └─ ext3.cs3 ──→ loadPlugin() ──→ register()
│   ├─ Refresh repos (fire & forget) ────────── (background)
│   └─ Check updates (fire & forget) ────────── (background)
│
└─ UI becomes interactive
    └─ Providers available asynchronously
```

### Performance Targets

| Phase               | Target             | Notes                        |
| ------------------- | ------------------ | ---------------------------- |
| DB init             | < 50ms             | Single table creation        |
| Load 3 extensions   | < 1s               | Parallel loading             |
| Repo refresh        | < 5s               | Network dependent            |
| Full startup impact | < 500ms additional | Most of the above runs async |

---

## 12. Settings UI Architecture

### 12.1 Screen Structure

```
Existing Settings
├── Provider Settings (existing)
├── Player Settings (existing)
├── ...
├── Extensions (NEW)  ──→ ExtensionsManagementScreen
│   ├── Installed Extensions list
│   │   ├── Extension 1 [Toggle] [Update] [Uninstall]
│   │   ├── Extension 2 [Toggle] [Update] [Uninstall]
│   │   └── ...
│   ├── [Browse Repositories] ──→ RepositoryManagementScreen
│   │   ├── Repository 1 [Toggle] [Refresh] [Delete]
│   │   ├── Repository 2 [Toggle] [Refresh] [Delete]
│   │   └── [Add Repository] → URL input dialog
│   ├── [Check for Updates]
│   └── [Diagnostics] ──→ DiagnosticsScreen
│       ├── Loaded providers count
│       ├── Per-extension: status, version, errors
│       ├── [Force Reload All]
│       └── [Clear Cache]
```

### 12.2 Fragment Implementation

```kotlin
// File: extensions/settings/ExtensionsMobileFragment.kt
class ExtensionsMobileFragment : Fragment() {
    private val viewModel: ExtensionSettingsViewModel by viewModels()
    // Shows list of installed extensions with toggle/update/uninstall actions
    // Uses AppAdapter with PROVIDER_MOBILE_ITEM type for consistency
}

// File: extensions/settings/RepositoriesMobileFragment.kt
class RepositoriesMobileFragment : Fragment() {
    private val viewModel: ExtensionSettingsViewModel by viewModels()
    // Shows list of repositories with refresh/toggle/delete
    // "Add Repository" FAB → shows URL input dialog
    // Pre-populated repos shown with "built-in" badge
}

// TV equivalents follow the same pattern with leanback fragments
```

### 12.3 ViewModel

```kotlin
// File: extensions/settings/ExtensionSettingsViewModel.kt
class ExtensionSettingsViewModel : ViewModel() {

    // ── State ──
    private val _repositories = MutableStateFlow<List<ExtensionRepoEntity>>(emptyList())
    val repositories: StateFlow<List<ExtensionRepoEntity>> = _repositories.asStateFlow()

    private val _extensions = MutableStateFlow<List<InstalledExtensionEntity>>(emptyList())
    val extensions: StateFlow<List<InstalledExtensionEntity>> = _extensions.asStateFlow()

    private val _availableUpdates = MutableStateFlow<List<ExtensionUpdate>>(emptyList())
    val availableUpdates: StateFlow<List<ExtensionUpdate>> = _availableUpdates.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // ── Actions ──
    fun addRepository(url: String) = viewModelScope.launch { ... }
    fun removeRepository(url: String) = viewModelScope.launch { ... }
    fun refreshRepository(url: String) = viewModelScope.launch { ... }
    fun refreshAll() = viewModelScope.launch { ... }
    fun toggleExtension(packageName: String, enabled: Boolean) = viewModelScope.launch { ... }
    fun installExtension(metadata: ExtensionMetadata) = viewModelScope.launch { ... }
    fun uninstallExtension(packageName: String) = viewModelScope.launch { ... }
    fun checkForUpdates() = viewModelScope.launch { ... }
    fun updateAll() = viewModelScope.launch { ... }
    fun forceReloadAll() = viewModelScope.launch { ... }
}
```

### 12.4 Adding to Existing Settings Navigation

```kotlin
// In SettingsMobileFragment.kt (add to displaySettings()):
findPreference<PreferenceScreen>("screen_extensions")?.apply {
    title = getString(R.string.extensions_title)
    summary = getString(R.string.extensions_summary, ExtensionRegistry.getProviderCount())
    setOnPreferenceClickListener {
        // Navigate to ExtensionsMobileFragment
        parentFragmentManager.commit {
            replace(R.id.container, ExtensionsMobileFragment())
            addToBackStack(null)
        }
        true
    }
}

// In settings_mobile.xml:
<PreferenceScreen
    android:key="screen_extensions"
    android:title="@string/extensions_title"
    android:summary="@string/extensions_summary">
</PreferenceScreen>
```

---

## 13. Integration Points with Existing Code

### 13.1 Minimal Changes Required

| File                        | Change                                                                | Complexity |
| --------------------------- | --------------------------------------------------------------------- | ---------- |
| `StreamFlixApp.kt`          | Add `ExtensionInitializer.init()` call                                | 1 line     |
| `Provider.kt` (companion)   | Or: add `ProviderRegistry` lookup in `findByName()`                   | 3 lines    |
| `UserPreferences.kt`        | Update `currentProvider` getter to search `ProviderRegistry`          | 2 lines    |
| `ProvidersViewModel.kt`     | Use `ProviderRegistry.allProviders()` instead of `Provider.providers` | 1 line     |
| `SettingsMobileFragment.kt` | Add Extensions menu entry                                             | ~10 lines  |
| `SettingsTvFragment.kt`     | Add Extensions menu entry                                             | ~10 lines  |

**Design Principle**: Extension providers are ADDITIVE — they don't replace the
existing provider system. Built-in providers continue working exactly as before.
The only change is that the provider lookup mechanism also checks extension
providers.

### 13.2 Change Details

**UserPreferences.kt — currentProvider getter:**

```kotlin
var currentProvider: Provider?
    get() {
        val providerName = Key.CURRENT_PROVIDER.getString()
        if (providerName?.startsWith("TMDb (") == true && providerName.endsWith(")")) {
            val lang = providerName.substringAfter("TMDb (").substringBefore(")")
            return TmdbProvider(lang)
        }
        // Search both built-in and extension providers
        return ProviderRegistry.findByName(providerName ?: return null)
            ?: Provider.providers.keys.find { it.name == providerName }
    }
```

**ProvidersViewModel.kt:**

```kotlin
// In getProviders(), change:
val providers = Provider.providers.keys.filter { ... }
// To:
val providers = ProviderRegistry.allProviders().keys.filter { ... }
```

---

## 14. Implementation Sequence

### Phase 1: Foundation (Week 1)

| Step | Task                               | Files                                                                   | Dependencies |
| ---- | ---------------------------------- | ----------------------------------------------------------------------- | ------------ |
| 1.1  | Create extension package structure | All directories                                                         | None         |
| 1.2  | Implement data models              | `ExtensionModels.kt`, `ExtensionProviderInterface.kt`                   | None         |
| 1.3  | Implement Room database            | `ExtensionDatabase.kt`, `RepositoryDao.kt`, `ExtensionDao.kt`, entities | Models       |
| 1.4  | Implement ExtensionRegistry        | `ExtensionRegistry.kt`                                                  | Models       |
| 1.5  | Implement ManifestParser           | `ManifestParser.kt`                                                     | Models       |

**Acceptance Criteria**: Database creates, extension metadata models work,
manifest.json parsing works with test .cs3 files.

### Phase 2: Core Loading (Week 2)

| Step | Task                                    | Files                                                    | Dependencies |
| ---- | --------------------------------------- | -------------------------------------------------------- | ------------ |
| 2.1  | Implement ExtensionLoader               | `ExtensionLoader.kt`, `PluginClassLoader.kt`             | Phase 1      |
| 2.2  | Implement ProviderAdapter + ModelMapper | `ExtensionProviderAdapter.kt`, `ExtensionModelMapper.kt` | Phase 1      |
| 2.3  | Implement ProviderRegistry              | `ProviderRegistry.kt`                                    | 2.2          |
| 2.4  | Write integration tests for loading     | Test files                                               | 2.1-2.3      |

**Acceptance Criteria**: Can load a .cs3 file, instantiate the plugin, wrap it
in ProviderAdapter, and call methods. All errors are caught without crashing.

### Phase 3: Management (Week 3)

| Step | Task                           | Files                                         | Dependencies |
| ---- | ------------------------------ | --------------------------------------------- | ------------ |
| 3.1  | Implement RepositoryManager    | `RepositoryManager.kt`, `RepositoryFormat.kt` | Phase 1      |
| 3.2  | Implement ExtensionManager     | `ExtensionManager.kt`                         | Phase 2, 3.1 |
| 3.3  | Implement conflict resolver    | `ExtensionConflictResolver.kt`                | Phase 2      |
| 3.4  | Implement ExtensionInitializer | `ExtensionInitializer.kt`                     | Phase 2, 3.2 |

**Acceptance Criteria**: Can add repos, browse extensions, install,
enable/disable, uninstall. Startup loads previously installed extensions.

### Phase 4: Cloudstream Compatibility (Week 4)

| Step | Task                             | Files                       | Dependencies |
| ---- | -------------------------------- | --------------------------- | ------------ |
| 4.1  | Implement CloudstreamReflector   | `CloudstreamReflector.kt`   | Phase 1      |
| 4.2  | Implement CloudstreamModelMapper | `CloudstreamModelMapper.kt` | Phase 1      |
| 4.3  | Implement CloudstreamAdapter     | `CloudstreamAdapter.kt`     | 4.1, 4.2     |
| 4.4  | Test with real CS3 extension     | Integration tests           | 4.3          |

**Acceptance Criteria**: Can load a Cloudstream .cs3 extension, bridge its
MainAPI to Provider interface, and use it in the app.

### Phase 5: Settings UI & Integration (Week 5)

| Step | Task                                 | Files                                                          | Dependencies |
| ---- | ------------------------------------ | -------------------------------------------------------------- | ------------ |
| 5.1  | Implement ExtensionSettingsViewModel | `ExtensionSettingsViewModel.kt`                                | Phase 3      |
| 5.2  | Implement mobile settings fragments  | `ExtensionsMobileFragment.kt`, `RepositoriesMobileFragment.kt` | 5.1          |
| 5.3  | Implement TV settings fragments      | `ExtensionsTvFragment.kt`, `RepositoriesTvFragment.kt`         | 5.1          |
| 5.4  | Integrate with existing settings     | Modify `SettingsMobileFragment.kt`, `SettingsTvFragment.kt`    | 5.2, 5.3     |
| 5.5  | Integration with existing app code   | `UserPreferences.kt`, `ProvidersViewModel.kt`                  | Phase 2      |
| 5.6  | End-to-end testing                   | All                                                            | All          |

**Acceptance Criteria**: Full extension management from UI, providers appear in
provider list, can select and use extension providers.

### Phase 6: Polish (Week 6)

| Step | Task                                   |
| ---- | -------------------------------------- |
| 6.1  | Error reporting and diagnostics UI     |
| 6.2  | Progress indicators for downloads      |
| 6.3  | Notification for auto-updates          |
| 6.4  | Extension SDK publication              |
| 6.5  | Documentation for extension developers |

---

## 15. Risk Assessment

| Risk                            | Likelihood | Impact | Mitigation                                                      |
| ------------------------------- | ---------- | ------ | --------------------------------------------------------------- |
| Plugin APK crashes app          | Low        | High   | Isolated classloaders + runCatching in adapter                  |
| Android 14+ DEX restrictions    | Medium     | Medium | Set file read-only before loading                               |
| Classloader memory leak         | Medium     | Medium | Weak references, explicit cleanup in unload                     |
| Network failure on repo refresh | High       | Low    | Cached data used as fallback                                    |
| Cloudstream API incompatibility | Medium     | Medium | Reflection-based (works with minor API changes)                 |
| Hash verification failure       | Low        | Medium | File deleted, error returned, no crash                          |
| Disk space exhaustion           | Low        | Low    | Check free space before download                                |
| Plugin with malicious code      | Low        | High   | Sandboxed in app process (Android sandboxing is the mitigation) |
| Two plugins same provider name  | Medium     | Low    | Conflict resolver, last-installed wins                          |
| Large number of loaded plugins  | Low        | Low    | ConcurrentHashMap, lazy loading                                 |

### Risk: ClassLoader Memory Leak Mitigation

```kotlin
// In PluginClassLoader
class PluginClassLoader(...) {
    // Use WeakReference so GC can collect the classloader
    private val classLoaderRef = WeakReference(delegate)

    fun getClassLoader(): ClassLoader? = classLoaderRef.get()

    fun cleanup() {
        pluginInstance = null
        classLoaderRef.clear()
    }
}
```

---

## 16. Testing Strategy

### 16.1 Unit Tests

| Component                 | Tests                                     | Framework |
| ------------------------- | ----------------------------------------- | --------- |
| ManifestParser            | Parse valid/invalid manifest.json         | JUnit     |
| ExtensionModelMapper      | Map extension models to Streamflix models | JUnit     |
| CloudstreamModelMapper    | Map CS3 models (via reflection)           | JUnit     |
| ExtensionConflictResolver | Detect name conflicts                     | JUnit     |
| RepositoryFormat          | Parse repo.json, plugins.json             | JUnit     |
| ExtensionDatabase         | DAO operations                            | Room Test |
| ProviderRegistry          | Merged provider lookup                    | JUnit     |

### 16.2 Integration Tests

| Test                       | Setup                             | Verification                             |
| -------------------------- | --------------------------------- | ---------------------------------------- |
| Load valid .cs3            | Place test .cs3 in test assets    | Plugin loaded, ExtensionProvider adapted |
| Load corrupt .cs3          | Place invalid file                | Error returned, no crash                 |
| Load manifest-missing .cs3 | Create .cs3 without manifest.json | Error: "No manifest found"               |
| Repo refresh with network  | Mock OkHttp server                | Metadata cached correctly                |
| Extension install flow     | Mock network + temp dir           | File downloaded, DB updated              |
| Cloudstream adapter call   | Load CS3 test plugin              | search() returns results                 |
| Unload and reload          | Load → unload → load              | Extension works after reload             |

### 16.3 Manual Testing Checklist

- [ ] Add repository URL (valid)
- [ ] Add repository URL (invalid) — error shown
- [ ] Browse extensions from repository
- [ ] Install extension
- [ ] Use extension provider for search, home, movie detail
- [ ] Uninstall extension
- [ ] Enable/disable extension
- [ ] Update extension to newer version
- [ ] Auto-update check on launch
- [ ] Load Cloudstream extension
- [ ] Mix built-in + extension providers
- [ ] Kill app, relaunch — extensions persist
- [ ] Network offline — cached data used
- [ ] Corrupt .cs3 — graceful error

---

## 17. Architecture Decision Records

### ADR-001: Adapter Pattern over Direct Implementation

**Status**: Accepted

**Context**: Extension APKs cannot implement Streamflix's `Provider` interface
directly because that interface references app-internal classes
(`AppAdapter.Item`, Room entities, etc.). Requiring extensions to depend on the
full app would be impractical.

**Decision**: Define a separate `ExtensionProvider` interface in a lightweight
SDK module. Implement an `ExtensionProviderAdapter` that bridges to the existing
`Provider` interface.

**Consequences**:

- ✅ Extensions depend only on a small SDK (pure Kotlin + data classes)
- ✅ Existing app code unchanged — still consumes `Provider`
- ✅ Adapter can add error isolation (runCatching) for every method
- ❌ Extra indirection layer (maintenance cost)
- ❌ Adapter must map all data models

### ADR-002: PathClassLoader over DexClassLoader

**Status**: Accepted

**Context**: Android has two main classloaders for dynamic code loading.
`DexClassLoader` is the traditional choice for loading external DEX files.
`PathClassLoader` is typically used for the system classloader.

**Decision**: Use `PathClassLoader` (matching Cloudstream's approach) because:

1. On modern Android (API 26+), `PathClassLoader` handles DEX files from any
   path
2. `DexClassLoader`'s optimized directory parameter is deprecated in API 26+
3. It's what Cloudstream uses (battle-tested)

**Consequences**:

- ✅ Simpler API (no optimized directory parameter)
- ✅ Compatible with Android 14 restrictions
- ✅ Proven in Cloudstream with thousands of extensions
- ❌ Requires minimum API 26 for optimal behavior (our minSdk is 21, but testing
  shows PathClassLoader works from API 21+)

### ADR-003: Reflection for Cloudstream Compatibility over Compile-Time Dependency

**Status**: Accepted

**Context**: Cloudstream plugins implement `MainAPI` from
`com.lagradost.cloudstream3`. We cannot depend on this package at compile time
(it's a separate project with its own versioning). We also can't guarantee the
exact API version available.

**Decision**: Use reflection to interact with Cloudstream `MainAPI` methods.
Provide a typed `CloudstreamReflector` that centralizes all reflection calls.

**Consequences**:

- ✅ No dependency on CS3 SDK at compile time
- ✅ Resilient to minor API changes (missing methods = graceful fallback)
- ✅ Can support multiple CS3 API versions
- ❌ Runtime performance overhead from reflection
- ❌ Compile-time type safety lost (errors surface at load time)
- ❌ More complex error handling

### ADR-004: Dedicated Room Database over SharedPreferences

**Status**: Accepted

**Context**: Extension metadata includes structured data (lists, timestamps,
boolean flags) that SharedPreferences handles poorly. Both repositories and
installed extensions need relational queries (find by repo, count, etc.).

**Decision**: Use a dedicated Room database (`ExtensionDatabase`) with two
tables (`repositories`, `extensions`).

**Consequences**:

- ✅ Type-safe queries with DAOs
- ✅ Flow-based reactive observation
- ✅ Migration support for schema changes
- ✅ Structured data (lists, booleans, timestamps)
- ❌ Additional ~100KB APK size from Room (already used in app)
- ❌ Slightly more setup code vs SharedPreferences

### ADR-005: Extension Providers Are Additive (Not Replacing Built-in)

**Status**: Accepted

**Context**: The existing 68 built-in providers are hardcoded. Trying to migrate
all to extensions immediately would be a massive, risky refactor.

**Decision**: Extension providers coexist with built-in providers. The
`ProviderRegistry` merges both lists. Built-in providers remain in their current
hardcoded form permanently (or until individually migrated).

**Consequences**:

- ✅ Zero risk to existing functionality
- ✅ Gradual migration path for individual providers
- ✅ Users can choose between built-in and extension versions
- ❌ Some code duplication for providers that exist in both forms
- ❌ The `Provider.providers` companion map persists (technical debt)

---

## Appendix A: Extension SDK Module Definition

```gradle
// extension-sdk/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.streamflixreborn.streamflix.extension.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Minimal dependencies — only what extension developers need
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // OkHttp included for network requests in extensions
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    // Jsoup included for HTML parsing in extensions
    compileOnly("org.jsoup:jsoup:1.19.1")
}
```

## Appendix B: Built-in Provider Migration Path

When migrating a built-in provider to an extension:

1. Extract scraping logic into a new class implementing `ExtensionProvider`
2. Create an extension module (Android Library) with the new class
3. Add `@CloudstreamPlugin` annotation for future Cloudstream compatibility
4. Build .cs3 file using the Gradle plugin
5. Test as an extension provider in Streamflix
6. After validation, optionally remove the old hardcoded provider (keeping the
   `Provider` interface import)

The migration is purely optional — built-in and extension providers can coexist
indefinitely.

---

_End of Architecture Document_
