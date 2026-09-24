package com.animeparser

import kotlinx.serialization.Serializable

@Serializable
data class Anime(
    val id: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val image: String = "",
    val description: String = "",
    val score: String = "",
    val episodes: Int = 0,
    val type: String = "",
    val status: String = "",
    val genres: List<String> = emptyList(),
    val year: String = "",
    val url: String = "",
)

@Serializable
data class Episode(
    val number: Int,
    val title: String = "",
)

@Serializable
data class VoiceOption(
    val id: String = "",
    val name: String = "",
    val playerType: String = "",  // kodik / aniboom / cvh
    val embedUrl: String = "",
)

@Serializable
data class VideoStream(
    val url: String = "",
    val quality: Int = 720,
    val qualities: List<Int> = emptyList(),
    val playerType: String = "",  // direct / hls / webview
)
