// file: extensions/manager/ExtensionConflictResolver.kt
package com.streamflixreborn.streamflix.extensions.manager

import com.streamflixreborn.streamflix.extensions.db.InstalledExtensionEntity

/**
 * Detects conflicts between installed extensions where two extensions
 * advertise the same provider name.
 *
 * ## Resolution strategy
 * - **Last-installed wins**: when a conflict is detected during install,
 *   the installation proceeds and the new extension's provider shadows
 *   the existing one at runtime.
 * - **User can manually disable**: the Settings UI allows disabling any
 *   extension, which lets the user resolve conflicts by choosing which
 *   provider to keep active.
 *
 * ## What constitutes a conflict
 * Two extensions conflict when they have the same [InstalledExtensionEntity.name].
 * Since `name` is the provider name exposed to the user, duplicates cause
 * ambiguity in provider selection UIs.
 */
class ExtensionConflictResolver {

    /**
     * Describes a conflict between two installed extensions that both
     * register a provider with the same [providerName].
     *
     * @property packageNameA The package name of the first conflicting extension.
     * @property packageNameB The package name of the second conflicting extension.
     * @property providerName The provider name that both extensions advertise.
     */
    data class ExtensionConflict(
        val packageNameA: String,
        val packageNameB: String,
        val providerName: String,
    )

    /**
     * Find all conflicts within a list of installed extensions.
     *
     * Extensions are grouped by [InstalledExtensionEntity.name]. Every pair
     * of extensions within a group of size > 1 produces one [ExtensionConflict].
     *
     * @param extensions The list of currently installed extensions.
     * @return A list of conflicts — empty if no duplicates exist.
     */
    fun findConflicts(extensions: List<InstalledExtensionEntity>): List<ExtensionConflict> {
        if (extensions.size < 2) return emptyList()

        val conflicts = mutableListOf<ExtensionConflict>()

        // Group by provider name
        extensions.groupBy { it.name }
            .filter { (_, group) -> group.size > 1 }
            .forEach { (name, group) ->
                // Generate all unordered pairs within the group
                for (i in group.indices) {
                    for (j in i + 1 until group.size) {
                        conflicts.add(
                            ExtensionConflict(
                                packageNameA = group[i].packageName,
                                packageNameB = group[j].packageName,
                                providerName = name,
                            )
                        )
                    }
                }
            }

        return conflicts
    }

    /**
     * Check whether a newly installing extension would conflict with any
     * already-installed extensions.
     *
     * This is a convenience overload of [findConflicts] that accepts an
     * existing list and a single new extension.
     *
     * @param existing     The list of already-installed extensions.
     * @param newExtension The extension being installed (not yet in [existing]).
     * @return A list of conflicts — empty if no existing extension uses the
     *         same provider name.
     */
    fun findConflicts(
        existing: List<InstalledExtensionEntity>,
        newExtension: InstalledExtensionEntity,
    ): List<ExtensionConflict> {
        return existing
            .filter { it.name == newExtension.name }
            .map { existingExt ->
                ExtensionConflict(
                    packageNameA = existingExt.packageName,
                    packageNameB = newExtension.packageName,
                    providerName = newExtension.name,
                )
            }
    }
}
