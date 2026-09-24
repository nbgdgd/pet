package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Translation
import com.aniblaze.aggregator.model.ContentResult
import kotlinx.coroutines.CancellationException

internal suspend fun applyVoicePriority(original: ContentResult?, enabled: Boolean, preference: PlayerPref?,
    resolve: suspend (Int) -> ContentResult?): ContentResult? {
    if (!enabled || original == null || (preference?.voiceManual == true && preference.voice >= 0)) return original
    for (voice in prioritizedVoices(original.translations.orEmpty())) {
        if (voice.id == original.translationId) return original
        val candidate = try { resolve(voice.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            com.aniblaze.desktop.player.PlayerDiagnostics.failure("voice.priority.failed", failure)
            null
        }
        if (candidate?.translationId == voice.id && candidate.source == original.source) return candidate
    }
    return original
}

/** Popularity is deliberately absent from the priority order. */
internal fun prioritizedVoices(voices: List<Translation>): List<Translation> = voices
    .filterNot { it.isSub }
    .mapNotNull { voice ->
        val name = voice.name.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val rank = when {
            name.contains("дубляж") || name == "dub" || name.contains("dubbing") -> 0
            name.contains("studioband") || name.contains("студийнаябанда") -> 1
            name.contains("anilibria") || name.contains("анилибрия") -> 2
            else -> return@mapNotNull null
        }
        rank to voice
    }.sortedBy { it.first }.map { it.second }
