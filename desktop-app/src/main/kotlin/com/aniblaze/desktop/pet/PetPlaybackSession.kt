package com.aniblaze.desktop.pet

/** In-memory UI continuity; persisted release/completion IDs live in AppSettings. */
class PetUiMemory {
    val phrases = PetPhrasePicker()
    var greeted = false
    var lastSpeechAt = Long.MIN_VALUE / 2
    var lastFocus: String? = null
    var lastMotionAt = 0L
    var lastInteractionAt = System.currentTimeMillis()
    /** Сколько объявленных событий уже «отпраздновано» анимацией (см. PetHost). */
    var lastReleaseCheer = -1
    data class CachedTitle(val schedule: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule,
        val recommendations: List<com.aniblaze.aggregator.model.Anime>, val at: Long)
    val titles = LinkedHashMap<String, CachedTitle>()
}

/** Lives with the title's player, not with its temporarily visible overlay. */
class PetPlaybackSession {
    /** Once per title session, including overlay remount/fullscreen changes. */
    var drizzTitleShown = false
    var seekSerial = 0L
    val director = PetDirector()
    private val watched = HashMap<Int, Long>()
    private val completed = HashSet<Int>()
    private val announced = HashSet<Pair<Int, PetEvent>>()
    var watchedMs: Long = 0L
        private set
    val completedCount: Int get() = completed.size
    private var pending: Completion? = null

    data class Completion(val episode: Int, val event: PetEvent, val at: Long)

    fun recordWatch(episode: Int, measuredMs: Long) {
        val delta = measuredMs.coerceIn(0L, 60_000L)
        watchedMs += delta
        watched[episode] = (watched[episode] ?: 0L) + delta
    }

    fun episodeWatchedMs(episode: Int): Long = watched[episode] ?: 0L

    fun recordCompletion(episode: Int, event: PetEvent, announce: Boolean, now: Long = System.currentTimeMillis()) {
        completed.add(episode)
        if (announce && announced.add(episode to event)) pending = Completion(episode, event, now)
    }

    fun takeCompletion(now: Long): Completion? {
        val value = pending
        pending = null
        return value?.takeIf { now - it.at in 0..30_000L }
    }
}
