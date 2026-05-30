package com.brave.veloxcore.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface CaptureDao {

    @Insert
    fun insert(capture: CaptureEntity): Long

@Query("SELECT EXISTS(SELECT 1 FROM captures WHERE contentHash = :hash)")
    fun existsByHash(hash: String): Boolean

    @Query("SELECT * FROM captures ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): List<CaptureEntity>

    @Query("SELECT * FROM captures WHERE textContent LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    fun search(query: String, limit: Int = 20): List<CaptureEntity>

    @Query("SELECT COUNT(*) FROM captures")
    fun getCount(): Int

    @Query("DELETE FROM captures WHERE timestamp < :before")
    fun deleteOlderThan(before: Long): Int

    @RawQuery
    fun searchFts(query: SupportSQLiteQuery): List<CaptureEntity>
}