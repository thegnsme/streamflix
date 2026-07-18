// file: extensions/repo/ExtensionMetadata.kt
package com.streamflixreborn.streamflix.extensions.repo

/**
 * Metadata about an extension as listed in a repository's plugins.json.
 * Converted from [RepositoryPluginEntry] after validation.
 */
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

/**
 * Result of validating a repository URL.
 * [isValid] is the overall pass/fail — when false, [error] contains the reason.
 */
data class RepositoryValidation(
    val isValid: Boolean,
    val name: String? = null,
    val description: String? = null,
    val pluginCount: Int = 0,
    val error: String? = null,
)
