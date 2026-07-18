// file: extensions/repo/RepositoryValidator.kt
package com.streamflixreborn.streamflix.extensions.repo

import android.util.Log
import com.streamflixreborn.streamflix.extensions.models.RepositoryManifest
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Validates repository URLs and performs health checks.
 *
 * A "healthy" repository must:
 * 1. Use http or https scheme
 * 2. Be reachable — the `repo.json` file must respond with HTTP 200
 * 3. Contain valid JSON that can be parsed into [com.streamflixreborn.streamflix.extensions.models.RepositoryManifest]
 */
object RepositoryValidator {

    private const val TAG = "RepositoryValidator"

    /**
     * Perform a full validation of a repository URL.
     *
     * Checks URL format, then attempts to fetch and parse `repo.json`.
     *
     * @param url The candidate repository URL (must point to `repo.json`).
     * @return A [RepositoryValidation] with the results.
     */
    suspend fun validate(url: String): RepositoryValidation {
        // 1. Check URL format
        if (!isValidUrl(url)) {
            return RepositoryValidation(
                isValid = false,
                error = "Invalid URL format: must be http or https",
            )
        }

        // 2. Ping repo.json
        return try {
            pingRepoJson(url)?.let { manifest ->
                RepositoryValidation(
                    isValid = true,
                    name = manifest.name,
                    description = manifest.description.ifEmpty { null },
                    pluginCount = manifest.pluginLists.size,
                )
            } ?: RepositoryValidation(
                isValid = false,
                error = "Repository responded but response could not be parsed",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Validation failed for $url", e)
            RepositoryValidation(
                isValid = false,
                error = e.message ?: "Unknown validation error",
            )
        }
    }

    /**
     * Basic URL format validation.
     *
     * Accepts http and https URLs that have a host component.
     *
     * @param url The URL to validate.
     * @return `true` if the URL has an acceptable scheme and a non-empty host.
     */
    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false

        val httpUrl = trimmed.toHttpUrlOrNull()
        return httpUrl != null &&
            (httpUrl.scheme == "http" || httpUrl.scheme == "https") &&
            httpUrl.host.isNotBlank()
    }

    /**
     * Fetch the `repo.json` from [repoUrl], parse it, and return the manifest.
     *
     * Uses a short timeout (10 s) so a slow or unreachable repo fails fast.
     *
     * @return The parsed [RepositoryManifest], or `null` if the response was empty.
     * @throws Exception on network failure, HTTP error, or JSON parse error.
     */
    private suspend fun pingRepoJson(repoUrl: String): RepositoryManifest? =
        withContext(Dispatchers.IO) {
            val client = NetworkClient.default.newBuilder()
                .readTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(repoUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RepoValidationException(
                    "HTTP ${response.code} — repository not reachable at $repoUrl"
                )
            }

            val body = response.body?.string() ?: return@withContext null

            RepositoryFormat.parseRepoJson(body)
                .getOrThrow()
        }
}

/**
 * Exception thrown when a repository validation check fails.
 * @param message Human-readable description of the failure.
 */
class RepoValidationException(message: String) : Exception(message)
