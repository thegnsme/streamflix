package com.streamflixreborn.streamflix.extensions.db

import androidx.room.TypeConverter

/**
 * Room type converters for extension database entities.
 *
 * Currently provides converters for list types stored as JSON strings.
 * Add converters here as new field types are introduced.
 */
class ExtensionConverters {

    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(",").map { it.trim() }

    @TypeConverter
    fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    fun toBoolean(value: Int): Boolean = value == 1
}
