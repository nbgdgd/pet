package com.aniblaze.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aniblaze.database.entity.ContentEntity
import com.aniblaze.database.entity.FavoriteEntity
import com.aniblaze.database.entity.HistoryEntity
import com.aniblaze.database.entity.LinkCacheEntity
import com.aniblaze.database.entity.SegmentEntity
import com.aniblaze.database.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(content: ContentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(content: List<ContentEntity>)

    @Query("SELECT * FROM content WHERE id = :id")
    suspend fun getById(id: String): ContentEntity?

    @Query("SELECT * FROM content WHERE title LIKE '%' || :query || '%' ORDER BY rating DESC")
    suspend fun search(query: String): List<ContentEntity>

    @Query("SELECT * FROM content ORDER BY rating DESC LIMIT :limit")
    fun trending(limit: Int): Flow<List<ContentEntity>>
}

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(segments: List<SegmentEntity>)

    @Query("DELETE FROM segment WHERE contentId = :contentId")
    suspend fun deleteForContent(contentId: String)

    @Transaction
    suspend fun replaceForContent(contentId: String, segments: List<SegmentEntity>) {
        deleteForContent(contentId)
        upsertAll(segments)
    }

    @Query("SELECT * FROM segment WHERE contentId = :contentId ORDER BY number ASC")
    suspend fun forContent(contentId: String): List<SegmentEntity>

    @Query("SELECT * FROM segment WHERE id = :id")
    suspend fun getById(id: String): SegmentEntity?
}

@Dao
interface WatchProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE contentId = :contentId AND segmentId = :segmentId")
    suspend fun get(contentId: String, segmentId: String): WatchProgressEntity?

    @Query("SELECT segmentId FROM watch_progress WHERE contentId = :contentId AND completed = 1")
    suspend fun watchedSegments(contentId: String): List<String>

    @Query("SELECT * FROM watch_progress WHERE contentId = :contentId")
    suspend fun forContent(contentId: String): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE contentId = :contentId AND completed = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(contentId: String): WatchProgressEntity?

    @Query(
        """
        SELECT * FROM watch_progress
        WHERE completed = 0 AND position > 8000 AND position < duration
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    fun continueWatching(limit: Int): Flow<List<WatchProgressEntity>>

    /**
     * ВСЕ отметки просмотра, включая досмотренные до конца.
     *
     * [continueWatching] для статистики не годится: его `position < duration`
     * оставляет ровно недосмотренное, то есть экран считал бы «просмотрено серий»
     * по тем сериям, которые как раз НЕ досмотрели. Записи о законченных сериях
     * никуда не деваются — у них просто position догоняет duration.
     */
    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun allProgress(limit: Int): Flow<List<WatchProgressEntity>>
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE contentId = :contentId")
    suspend fun remove(contentId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE contentId = :contentId)")
    fun isFavorite(contentId: String): Flow<Boolean>

    @Transaction
    @Query(
        """
        SELECT c.* FROM content c
        INNER JOIN favorites f ON f.contentId = c.id
        ORDER BY f.createdAt DESC
        """,
    )
    fun favorites(): Flow<List<ContentEntity>>

    @Query("SELECT c.* FROM content c INNER JOIN favorites f ON f.contentId = c.id")
    suspend fun favoritesOnce(): List<ContentEntity>
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entry: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT contentId FROM history")
    suspend fun watchedIds(): List<String>

    @Transaction
    @Query(
        """
        SELECT c.* FROM content c
        INNER JOIN history h ON h.contentId = c.id
        ORDER BY h.watchedAt DESC
        LIMIT :limit
        """,
    )
    fun history(limit: Int): Flow<List<ContentEntity>>
}

@Dao
interface LinkCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: LinkCacheEntity)

    @Query(
        """
        SELECT * FROM link_cache
        WHERE source = :source AND contentId = :contentId AND segment = :segment
          AND expiresAt > :now
        """,
    )
    suspend fun get(source: String, contentId: String, segment: Int, now: Long): LinkCacheEntity?

    @Query("DELETE FROM link_cache WHERE expiresAt <= :now")
    suspend fun evictExpired(now: Long)
}
