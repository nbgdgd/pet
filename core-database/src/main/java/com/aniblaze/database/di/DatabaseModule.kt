package com.aniblaze.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aniblaze.database.AniBlazeDatabase
import com.aniblaze.database.dao.ContentDao
import com.aniblaze.database.dao.FavoriteDao
import com.aniblaze.database.dao.HistoryDao
import com.aniblaze.database.dao.LinkCacheDao
import com.aniblaze.database.dao.SegmentDao
import com.aniblaze.database.dao.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** v3 → v4: add the studio column without wiping favourites/history. */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE content ADD COLUMN studio TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v4 → v5: отделяем открытые серии от действительно просмотренных. Для старых
     * данных точных медиачасов нет, поэтому сохраняем только прежние отметки ≥90%
     * как legacy-завершение; все новые записи требуют проверенного воспроизведения.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN verifiedPlaybackMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            // До v5 приложение не хранило медиачасы. Для уже существующих строк
            // сохраняем прежнюю нижнюю оценку времени; новые записи идут через
            // проверку непрерывного движения позиции.
            db.execSQL(
                "UPDATE watch_progress SET verifiedPlaybackMs = " +
                    "CASE WHEN duration > 0 AND position > duration THEN duration ELSE position END " +
                    "WHERE position > 0",
            )
            db.execSQL(
                "UPDATE watch_progress SET completed = 1 " +
                    "WHERE duration > 0 AND position * 100 >= duration * 90",
            )
        }
    }

    /**
     * Здесь стояло `fallbackToDestructiveMigration()` — «нет миграции, снеси базу».
     * Правило било по ЛЮБОМУ будущему изменению схемы: очередное обновление тихо
     * уносило избранное, историю и позиции просмотра, и заметить это можно было
     * только постфактум, по пустому экрану.
     *
     * Снос остаётся ровно в двух местах, где он безобиден:
     *
     *  • версии 1 и 2 — древние сборки, миграций для них никогда не существовало, и
     *    написать их сейчас не по чему (слепков схемы тогда не выгружали). Без этой
     *    оговорки такая установка просто падала бы при запуске;
     *  • откат назад — база новее приложения бывает только если человек поставил
     *    сборку старее установленной, и читать её всё равно нечем.
     *
     * Всё, начиная с версии 3, обязано иметь миграцию. Её отсутствие — падение на
     * запуске, а не потерянные данные: молча стереть чужой просмотр хуже, чем упасть.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AniBlazeDatabase =
        Room.databaseBuilder(context, AniBlazeDatabase::class.java, AniBlazeDatabase.NAME)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigrationFrom(1, 2)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun provideContentDao(db: AniBlazeDatabase): ContentDao = db.contentDao()
    @Provides fun provideSegmentDao(db: AniBlazeDatabase): SegmentDao = db.segmentDao()
    @Provides fun provideWatchProgressDao(db: AniBlazeDatabase): WatchProgressDao = db.watchProgressDao()
    @Provides fun provideFavoriteDao(db: AniBlazeDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideHistoryDao(db: AniBlazeDatabase): HistoryDao = db.historyDao()
    @Provides fun provideLinkCacheDao(db: AniBlazeDatabase): LinkCacheDao = db.linkCacheDao()
}
