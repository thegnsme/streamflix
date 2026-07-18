package com.streamflixreborn.streamflix.extensions.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "extensions")
data class InstalledExtensionEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
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
