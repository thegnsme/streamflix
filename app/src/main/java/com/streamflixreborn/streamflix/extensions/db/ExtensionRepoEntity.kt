package com.streamflixreborn.streamflix.extensions.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "repositories")
data class ExtensionRepoEntity(
    @PrimaryKey
    val url: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "last_refreshed")
    val lastRefreshed: Long? = null,
)
