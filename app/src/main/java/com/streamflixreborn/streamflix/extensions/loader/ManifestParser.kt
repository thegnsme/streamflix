package com.streamflixreborn.streamflix.extensions.loader

import com.streamflixreborn.streamflix.extensions.models.ExtensionManifest
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Parses manifest.json from inside a .cs3 extension archive (ZIP format).
 *
 * The manifest contains metadata needed to load and register an extension,
 * including the fully qualified plugin class name and version.
 */
class ManifestParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads manifest.json from a .cs3 archive at [apkPath] and deserializes
     * it to [ExtensionManifest].
     *
     * @param apkPath Absolute path to the .cs3 file.
     * @return [Result.success] with the parsed manifest, or [Result.failure]
     *         if the file cannot be read or the JSON is invalid.
     */
    suspend fun parse(apkPath: String): Result<ExtensionManifest> = runCatching {
        val zipFile = ZipFile(apkPath)
        val entry: ZipEntry = zipFile.getEntry("manifest.json")
            ?: throw FileNotFoundException("manifest.json not found in $apkPath")
        val jsonString = zipFile.getInputStream(entry).bufferedReader().use { it.readText() }
        zipFile.close()
        json.decodeFromString<ExtensionManifest>(jsonString)
    }

    /**
     * Parses manifest.json from raw byte content. Useful for testing.
     *
     * @param bytes Raw UTF-8 encoded JSON bytes.
     * @return [Result.success] with the parsed manifest, or [Result.failure]
     *         if the JSON is invalid.
     */
    suspend fun parseFromBytes(bytes: ByteArray): Result<ExtensionManifest> = runCatching {
        val jsonString = bytes.decodeToString()
        json.decodeFromString<ExtensionManifest>(jsonString)
    }
}
