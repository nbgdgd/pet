package com.animeparser

import okhttp3.OkHttpClient

class AnimeParser(
    private val client: OkHttpClient = AnimeGoParser.defaultClient(),
) {
    private val animego = AnimeGoParser(client)

    suspend fun search(query: String): List<Anime> {
        return animego.search(query)
    }

    suspend fun getAnime(url: String): Anime? {
        return animego.getAnime(url)
    }

    suspend fun getVoices(url: String): List<VoiceOption> {
        return animego.getVoices(url)
    }

    suspend fun getStream(
        animeId: String,
        animeUrl: String,
        voice: VoiceOption,
        episode: Int = 1,
    ): VideoStream? {
        return when (voice.playerType) {
            "aniboom" -> animego.getAniboomStream(voice.id, animeId, episode)
            "kodik" -> animego.getKodikEmbed(voice)
            else -> animego.getKodikEmbed(voice)
        }
    }
}
