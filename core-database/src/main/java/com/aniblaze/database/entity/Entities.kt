package com.aniblaze.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Catalog entry for an anime title. */
@Entity(tableName = "content")
data class ContentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val poster: String,
    val description: String,
    val rating: Double,
    val status: String,
    val updatedAt: Long,
    /** Airing weekday (1=Mon..7=Sun); 0 = not airing. */
    val broadcast: Int = 0,
    /** Comma-separated genres (for taste-based recommendations). */
    val genres: String = "",
    /** Release year (0 = unknown). */
    val year: Int = 0,
    /** How many users favourited it (popularity). */
    val favoritesCount: Int = 0,
    /** Animation studio (taste signal for recommendations). */
    val studio: String = "",
)

/** A single playable segment (episode) belonging to a [ContentEntity]. */
@Entity(
    tableName = "segment",
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["id"],
            childColumns = ["contentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("contentId")],
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    val contentId: String,
    val number: Int,
    val title: String,
    val releaseDate: String,
)

/** Frame-level resume position per segment. */
@Entity(tableName = "watch_progress", primaryKeys = ["contentId", "segmentId"])
data class WatchProgressEntity(
    val contentId: String,
    val segmentId: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    /** Правдоподобное продвижение медиачасов между соседними замерами. */
    val verifiedPlaybackMs: Long = 0L,
    /** Серия действительно досмотрена, а не просто открыта или промотана к концу. */
    val completed: Boolean = false,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val contentId: String,
    val createdAt: Long,
)

@Entity(tableName = "history", indices = [Index("watchedAt")])
data class HistoryEntity(
    @PrimaryKey val contentId: String,
    val segmentId: String,
    val watchedAt: Long,
)

/**
 * Persistent cache of resolved stream links. Keyed by source + content +
 * segment so a fallback source's result does not clobber the primary one.
 * [expiresAt] enforces the 6-24h TTL described in the spec.
 */
@Entity(tableName = "link_cache", primaryKeys = ["source", "contentId", "segment"])
data class LinkCacheEntity(
    val source: String,
    val contentId: String,
    val segment: Int,
    val location: String,
    val quality: String,
    val expiresAt: Long,
)
