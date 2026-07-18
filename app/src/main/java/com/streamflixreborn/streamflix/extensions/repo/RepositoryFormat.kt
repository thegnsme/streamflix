// file: extensions/repo/RepositoryFormat.kt
package com.streamflixreborn.streamflix.extensions.repo

import com.streamflixreborn.streamflix.extensions.models.RepositoryManifest
import com.streamflixreborn.streamflix.extensions.models.RepositoryPluginEntry
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Parses Cloudstream-compatible repository JSON formats.
 *
 * Repositories expose two JSON files:
 * - **repo.json** — a [RepositoryManifest] describing the repo and listing plugin-list URLs
 * - **plugins.json** — an array of [RepositoryPluginEntry] entries for each available plugin
 *
 * All methods are [suspend] functions meant to be called on [Dispatchers.IO].
 */
object RepositoryFormat {

    private const val TAG = "RepositoryFormat"

    /** Shared JSON parser — lenient to tolerate minor format variations. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ── Parse from string ─────────────────────────────────────────

    /**
     * Parse a raw JSON string into a [RepositoryManifest].
     *
     * @param jsonString The raw JSON content of a `repo.json` file.
     * @return [Result.success] with the manifest, or [Result.failure] with a parse error.
     */
    suspend fun parseRepoJson(jsonString: String): Result<RepositoryManifest> =
        runCatching {
            json.decodeFromString<RepositoryManifest>(jsonString)
        }

    /**
     * Parse a raw JSON string into a list of [RepositoryPluginEntry].
     *
     * @param jsonString The raw JSON content of a `plugins.json` file.
     * @return [Result.success] with the parsed entries, or [Result.failure] with a parse error.
     */
    suspend fun parsePluginListJson(jsonString: String): Result<List<RepositoryPluginEntry>> =
        runCatching {
            json.decodeFromString<List<RepositoryPluginEntry>>(jsonString)
        }

    // ── Fetch + parse from URL ────────────────────────────────────

    /**
     * Fetch a `repo.json` from [url] and parse it into a [RepositoryManifest].
     *
     * Uses the shared [NetworkClient.default] with a 15-second timeout.
     *
     * @param url The full URL of the `repo.json` file.
     * @return [Result.success] with the manifest, or [Result.failure] with a network/parse error.
     */
    suspend fun fetchAndParseRepo(url: String): Result<RepositoryManifest> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = NetworkClient.default.newBuilder()
                    .readTimeout(15, TimeUnit.SECONDS)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw RepoFetchException(
                        "HTTP ${response.code} fetching repo.json from $url"
                    )
                }
                val body = response.body?.string()
                    ?: throw RepoFetchException("Empty response body from $url")

                json.decodeFromString<RepositoryManifest>(body)
            }
        }

    /**
     * Fetch a `plugins.json` from [url] and parse it into a list of [RepositoryPluginEntry].
     *
     * Uses the shared [NetworkClient.default] with a 30-second timeout.
     *
     * @param url The full URL of the `plugins.json` file.
     * @return [Result.success] with the parsed entries, or [Result.failure] with a network/parse error.
     */
    suspend fun fetchAndParsePluginList(url: String): Result<List<RepositoryPluginEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = NetworkClient.default.newBuilder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw RepoFetchException(
                        "HTTP ${response.code} fetching plugins.json from $url"
                    )
                }
                val body = response.body?.string()
                    ?: throw RepoFetchException("Empty response body from $url")

                json.decodeFromString<List<RepositoryPluginEntry>>(body)
            }
        }

    // ── URL resolution ────────────────────────────────────────────

    /**
     * Resolve a plugin-list URL against the repo.json base URL.
     *
     * If [pluginUrl] is already absolute it is returned as-is.
     * If it is relative, it is resolved against the directory of [repoUrl].
     *
     * Examples:
     * ```
     * repoUrl = "https://example.com/repo/repo.json"
     * pluginUrl = "plugins.json" → "https://example.com/repo/plugins.json"
     * pluginUrl = "/plugins/list.json" → "https://example.com/plugins/list.json"
     * pluginUrl = "https://other.com/list.json" → "https://other.com/list.json"
     * ```
     */
    fun resolvePluginUrl(repoUrl: String, pluginUrl: String): String {
        val repoHttpUrl = repoUrl.toHttpUrlOrNull() ?: return pluginUrl
        val pluginHttp = pluginUrl.toHttpUrlOrNull()
        if (pluginHttp != null) return pluginUrl // already absolute

        // Resolve relative URL against the repo.json directory
        val base = repoHttpUrl.newBuilder()
            .encodedPath(repoHttpUrl.encodedPath.substringBeforeLast("/") + "/")
            .build()
        return base.resolve(pluginUrl)?.toString() ?: pluginUrl
    }
}

/**
 * Exception thrown when a repository fetch fails (network or HTTP error).
 * @param message Human-readable description of what went wrong.
 */
class RepoFetchException(message: String) : Exception(message)
