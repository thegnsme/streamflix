// file: extensions/manager/ExtensionUpdate.kt
package com.streamflixreborn.streamflix.extensions.manager

import com.streamflixreborn.streamflix.extensions.repo.ExtensionMetadata

/**
 * Represents an available update for an installed extension.
 *
 * @property packageName     The package name of the installed extension that has an update.
 * @property currentVersion  The version code currently installed.
 * @property availableVersion The version code available in the repository.
 * @property metadata        The full repository metadata for the updated version,
 *                           containing the download URL, hash, and other details.
 */
data class ExtensionUpdate(
    val packageName: String,
    val currentVersion: Int,
    val availableVersion: Int,
    val metadata: ExtensionMetadata,
)
