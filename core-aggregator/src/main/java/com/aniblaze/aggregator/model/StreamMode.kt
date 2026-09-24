package com.aniblaze.aggregator.model

/** How cinema playback should obtain the stream. Anime always uses [AUTO]. */
enum class StreamMode(val routeValue: String) {
    AUTO("auto"),
    PARSER("parser"),
    TORRENT("torrent");

    companion object {
        fun fromRoute(value: String?): StreamMode =
            entries.firstOrNull { it.routeValue.equals(value, ignoreCase = true) } ?: AUTO
    }
}
