package com.aniblaze.desktop

import com.aniblaze.aggregator.model.TitleComment
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.source.CommentStop
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One source release is one season; source prefix prevents cross-provider collisions. */
internal fun seasonCommentKey(source: String, releaseId: String): String =
    "${source.trim().lowercase()}:${releaseId.trim()}"

/**
 * Picks a foreign-source release without ever guessing between several seasons.
 * The caller has already applied loose title/year matching. Exact title or an
 * explicit matching season marker wins; unresolved ambiguity intentionally means
 * no comments instead of comments from a neighbouring season.
 */
internal fun selectCommentRelease(requested: Anime, candidates: List<Anime>): Anime? {
    val unique = candidates.distinctBy(Anime::id)
    if (unique.isEmpty()) return null
    val exactKey = commentTitleKey(requested.title)
    unique.filter { commentTitleKey(it.title) == exactKey }.singleOrNull()?.let { return it }
    val requestedSeason = explicitSeason(requested.title)
    if (requestedSeason != null) {
        return unique.filter { explicitSeason(it.title) == requestedSeason }.singleOrNull()
    }
    return unique.singleOrNull()
}

private fun commentTitleKey(title: String): String = title.lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private fun explicitSeason(title: String): Int? {
    val normalized = title.lowercase()
    val patterns = listOf(
        Regex("(?:сезон|season)\\s*(\\d+)"),
        Regex("(\\d+)(?:st|nd|rd|th)?\\s*(?:сезон|season)"),
        Regex("(?:часть|part)\\s*(\\d+)"),
        Regex("(\\d+)\\s*(?:часть|part)"),
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

/** Durable snapshot of one source release. Anime sources model one season as one release. */
internal data class StoredCommentSeason(
    val comments: List<TitleComment>,
    val nextPage: Int,
    val rawSeen: Int,
    val totalPages: Int,
    val totalCount: Int,
    val stop: CommentStop?,
    val updatedAt: Long,
)

/**
 * Small disk cache for real comments. One file is one source-qualified season.
 *
 * The player can therefore show the already collected pool immediately after an app
 * restart. A stale complete walk is re-read in the background and merged through the
 * same identity set; cached rows are never blindly appended to network rows.
 */
internal class SeasonCommentStore(
    private val directory: File = defaultDirectory(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        runCatching { directory.mkdirs() }
    }

    fun isStale(updatedAt: Long): Boolean =
        updatedAt <= 0L || now() - updatedAt >= STALE_AFTER_MS

    fun load(key: String): StoredCommentSeason? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("schema") != SCHEMA || root.optString("key") != key) return null
            val comments = root.optJSONArray("comments").toComments()
            StoredCommentSeason(
                comments = comments,
                nextPage = root.optInt("nextPage", 0).coerceAtLeast(0),
                rawSeen = root.optInt("rawSeen", 0).coerceAtLeast(0),
                totalPages = root.optInt("totalPages", 0).coerceAtLeast(0),
                totalCount = root.optInt("totalCount", 0).coerceAtLeast(0),
                stop = root.optString("stop").takeIf { it.isNotBlank() }
                    ?.let { runCatching { CommentStop.valueOf(it) }.getOrNull() },
                updatedAt = root.optLong("updatedAt", 0L),
            )
        }.getOrNull()
    }

    fun save(key: String, season: StoredCommentSeason) {
        runCatching {
            directory.mkdirs()
            val root = JSONObject()
                .put("schema", SCHEMA)
                .put("key", key)
                .put("nextPage", season.nextPage)
                .put("rawSeen", season.rawSeen)
                .put("totalPages", season.totalPages)
                .put("totalCount", season.totalCount)
                .put("stop", season.stop?.name ?: "")
                .put("updatedAt", season.updatedAt)
                .put("comments", JSONArray().also { array ->
                    season.comments.forEach { comment -> array.put(comment.toJson()) }
                })
            val target = fileFor(key)
            val temp = File(directory, "${target.name}.tmp")
            FileOutputStream(temp).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            trimOldFiles(target)
        }
    }

    private fun fileFor(key: String): File = File(directory, "${sha256(key)}.json")

    private fun trimOldFiles(current: File) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" }
            ?.filterNot { it == current }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        files.drop(MAX_SEASONS_ON_DISK - 1).forEach { runCatching { it.delete() } }
    }

    private fun TitleComment.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("source", source)
        .put("authorId", authorId)
        .put("author", author)
        .put("avatar", avatar)
        .put("message", message)
        .put("timestamp", timestamp)
        .put("votes", votes)
        .put("spoiler", isSpoiler)
        .put("replyCount", replyCount)
        .put("episode", episode)

    private fun JSONArray?.toComments(): List<TitleComment> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val message = item.optString("message").trim()
            if (message.isBlank()) return@mapNotNull null
            TitleComment(
                id = item.optLong("id"),
                source = item.optString("source"),
                authorId = item.optString("authorId"),
                author = item.optString("author").ifBlank { "аноним" },
                avatar = item.optString("avatar"),
                message = message,
                timestamp = item.optLong("timestamp"),
                votes = item.optInt("votes"),
                isSpoiler = item.optBoolean("spoiler"),
                replyCount = item.optInt("replyCount"),
                episode = item.optInt("episode"),
            )
        }
    }

    private companion object {
        const val SCHEMA = 1
        const val STALE_AFTER_MS = 6L * 60L * 60L * 1_000L
        const val MAX_SEASONS_ON_DISK = 12

        fun defaultDirectory(): File {
            val root = System.getenv("APPDATA") ?: System.getProperty("user.home")
            return File(root, "AniBlaze/comment-seasons")
        }

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
