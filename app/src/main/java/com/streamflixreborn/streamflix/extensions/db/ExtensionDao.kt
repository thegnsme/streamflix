package com.streamflixreborn.streamflix.extensions.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY name ASC")
    fun getAll(): Flow<List<InstalledExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE package_name = :pn")
    suspend fun get(pn: String): InstalledExtensionEntity?

    @Query("SELECT * FROM extensions WHERE is_enabled = 1")
    suspend fun getEnabled(): List<InstalledExtensionEntity>

    @Query("SELECT * FROM extensions WHERE repository_url = :url")
    suspend fun getByRepo(url: String): List<InstalledExtensionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ext: InstalledExtensionEntity)

    @Delete
    suspend fun delete(ext: InstalledExtensionEntity)

    @Query("UPDATE extensions SET is_enabled = :e WHERE package_name = :pn")
    suspend fun setEnabled(pn: String, e: Boolean)

    @Query("UPDATE extensions SET version = :v, file_path = :fp, last_updated_at = :ts WHERE package_name = :pn")
    suspend fun updateVersion(pn: String, v: Int, fp: String, ts: Long)

    @Query("SELECT COUNT(*) FROM extensions")
    suspend fun count(): Int
}
