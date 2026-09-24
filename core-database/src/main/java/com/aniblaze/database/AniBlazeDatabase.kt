package com.aniblaze.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aniblaze.database.dao.ContentDao
import com.aniblaze.database.dao.FavoriteDao
import com.aniblaze.database.dao.HistoryDao
import com.aniblaze.database.dao.LinkCacheDao
import com.aniblaze.database.dao.SegmentDao
import com.aniblaze.database.dao.WatchProgressDao
import com.aniblaze.database.entity.ContentEntity
import com.aniblaze.database.entity.FavoriteEntity
import com.aniblaze.database.entity.HistoryEntity
import com.aniblaze.database.entity.LinkCacheEntity
import com.aniblaze.database.entity.SegmentEntity
import com.aniblaze.database.entity.WatchProgressEntity

@Database(
    entities = [
        ContentEntity::class,
        SegmentEntity::class,
        WatchProgressEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        LinkCacheEntity::class,
    ],
    version = 5,
    // Схема выгружается в core-database/schemas. Без выгруженного слепка следующую
    // миграцию писать не по чему: Room не помнит, как таблицы выглядели ДО правки, и
    // единственным выходом остаётся снос базы вместе с избранным и прогрессом.
    exportSchema = true,
)
abstract class AniBlazeDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao
    abstract fun segmentDao(): SegmentDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun linkCacheDao(): LinkCacheDao

    companion object {
        const val NAME = "aniblaze.db"
    }
}
