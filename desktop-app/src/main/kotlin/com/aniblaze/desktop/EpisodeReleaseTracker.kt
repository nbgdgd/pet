package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val NEW_EPISODE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

/** Season identity, NOT a franchise key: season 1 and season 2 stay separate. */
internal fun releaseTitleKey(title: String): String = Recommender.normalizeTitle(title)
    .replace(Regex("\\b(?:тв|tv)\\s*1$"), "").trim()

@Serializable
internal data class EpisodeObservation(
    val key: String,
    val anime: PersistedAnime,
    val sourceCounts: Map<String, Int>,
    val highWater: Int,
    val latestEpisode: Int = 0,
    val latestAt: Long = 0,
    val lastSeen: Long = 0,
    val estimated: Boolean = false,
)

/** Neither title updatedAt, poster, dub nor rating can create an episode event. */
internal fun observeEpisodeReleases(
    previous: List<EpisodeObservation>, cards: List<Anime>, now: Long,
): List<EpisodeObservation> {
    val out = previous.associateByTo(LinkedHashMap()) { it.key }
    val keysById = HashMap<String, String>()
    val keysByTitle = HashMap<String, MutableSet<String>>()
    for (record in previous) {
        record.sourceCounts.keys.forEach { keysById[it] = record.key }
        keysByTitle.getOrPut(releaseTitleKey(record.anime.title)) { linkedSetOf() }.add(record.key)
    }
    for (card in cards) {
        if (card.episodesAvailable <= 0 || card.title.isBlank()) continue
        val title = releaseTitleKey(card.title)
        val aliases = keysByTitle[title].orEmpty().mapNotNull(out::get).filter {
            (it.anime.year == 0 || card.year == 0 || it.anime.year == card.year) }
        val old = keysById[card.id]?.let(out::get) ?: aliases.singleOrNull()
        val key = old?.key ?: "$title#${card.year}#${if (aliases.size > 1) card.id else ""}"
        val knownAtSource = old?.sourceCounts?.get(card.id)
        val count = card.episodesAvailable
        val highWater = maxOf(old?.highWater ?: 0, count)
        // A newly enabled provider establishes a baseline; lagging providers catching
        // up to the global high-water mark do not announce the same episode again.
        val grew = knownAtSource != null && count > knownAtSource && count > (old?.highWater ?: 0)
        val exactAt = card.episodeReleasedAt.takeIf { it > 0 && it <= now && count >= highWater }
        // Bootstrap first launch AND undated records from older builds. Never move
        // the date forward for the same episode when a weekly calendar rolls over.
        val estimatedAt = card.episodeEstimatedAt.takeIf {
            it > 0 && it <= now && now - it <= NEW_EPISODE_WINDOW_MS && count >= highWater
        }
        val bootstrap = estimatedAt != null && count > (old?.latestEpisode ?: 0)
        val exact = exactAt != null && (count > (old?.latestEpisode ?: 0) || old?.estimated == true)
        val eventAt = when {
            exact -> exactAt!!
            grew -> now
            bootstrap -> estimatedAt!!
            else -> old?.latestAt ?: 0
        }
        val episode = if (grew || exact || bootstrap) count
            else old?.latestEpisode ?: 0
        val stored = card.toPersisted().copy(description = "")
        // Open the provider that actually has the newest episode, not a lagging
        // alias. Equal counts keep the established representative stable.
        val representative = when {
            old == null || old.anime.id == card.id -> stored
            count > (old.sourceCounts[old.anime.id] ?: 0) -> stored
            else -> old.anime
        }
        out[key] = EpisodeObservation(key, representative,
            old?.sourceCounts.orEmpty() + (card.id to maxOf(knownAtSource ?: 0, count)),
            highWater, episode, eventAt, now / 86_400_000L * 86_400_000L,
            estimated = if (exact || grew) false else if (bootstrap) true else old?.estimated ?: false)
        keysById[card.id] = key
        keysByTitle.getOrPut(title) { linkedSetOf() }.add(key)
    }
    return out.values.sortedByDescending { maxOf(it.lastSeen, it.latestAt) }.take(1200)
}

internal class EpisodeReleaseTracker(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private var records = runCatching {
        json.decodeFromString<List<EpisodeObservation>>(file.readText())
    }.getOrDefault(emptyList())
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    @Synchronized fun observe(cards: List<Anime>, now: Long = System.currentTimeMillis()) {
        val next = observeEpisodeReleases(records, cards, now)
        if (next == records) return
        records = next
        runCatching {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(next))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { com.aniblaze.desktop.player.PlayerDiagnostics.failure("episodes.cache.write", it) }
        _revision.value++
    }

    @Synchronized fun recent(now: Long = System.currentTimeMillis()): List<Anime> = records
        .filter { it.latestAt > 0 && it.latestAt <= now && now - it.latestAt <= NEW_EPISODE_WINDOW_MS }
        .sortedWith(compareByDescending<EpisodeObservation> { it.latestAt }.thenBy { it.key })
        .map { it.anime.toAnime().copy(episodesAvailable = it.latestEpisode,
            episodeReleasedAt = if (it.estimated) 0 else it.latestAt,
            episodeEstimatedAt = if (it.estimated) it.latestAt else 0) }
}
