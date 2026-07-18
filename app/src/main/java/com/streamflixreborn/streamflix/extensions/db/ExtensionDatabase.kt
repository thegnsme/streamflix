package com.streamflixreborn.streamflix.extensions.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ExtensionRepoEntity::class, InstalledExtensionEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(ExtensionConverters::class)
abstract class ExtensionDatabase : RoomDatabase() {
    abstract fun repositoryDao(): RepositoryDao
    abstract fun extensionDao(): ExtensionDao

    companion object {
        @Volatile
        private var INSTANCE: ExtensionDatabase? = null

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
