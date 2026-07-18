package com.streamflixreborn.streamflix.extensions.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RepositoryDao {
    @Query("SELECT * FROM repositories ORDER BY name ASC")
    fun getAll(): Flow<List<ExtensionRepoEntity>>

    @Query("SELECT * FROM repositories WHERE url = :url")
    suspend fun get(url: String): ExtensionRepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: ExtensionRepoEntity)

    @Delete
    suspend fun delete(repo: ExtensionRepoEntity)

    @Query("UPDATE repositories SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    @Query("UPDATE repositories SET last_refreshed = :ts WHERE url = :url")
    suspend fun setLastRefreshed(url: String, ts: Long)

    @Query("SELECT COUNT(*) FROM repositories")
    suspend fun count(): Int
}
