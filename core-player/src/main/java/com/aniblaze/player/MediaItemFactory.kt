package com.aniblaze.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.aniblaze.aggregator.model.Caption
import com.aniblaze.aggregator.model.CaptionFormat
import com.aniblaze.aggregator.model.ContentResult

/** Builds Media3 [MediaItem]s from resolved [ContentResult]s. */
object MediaItemFactory {

    fun from(result: ContentResult, title: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(result.location)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())

        mimeTypeFor(result)?.let { builder.setMimeType(it) }

        result.captions?.takeIf { it.isNotEmpty() }?.let { captions ->
            builder.setSubtitleConfigurations(captions.map { it.toSubtitleConfig() })
        }
        return builder.build()
    }

    private fun mimeTypeFor(result: ContentResult): String? = when {
        result.isHls -> MimeTypes.APPLICATION_M3U8
        result.isDash -> MimeTypes.APPLICATION_MPD
        result.location.contains(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }

    private fun Caption.toSubtitleConfig(): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
            .setMimeType(format.toMime())
            .setLanguage(language)
            .setSelectionFlags(0)
            .build()

    private fun CaptionFormat.toMime(): String = when (this) {
        CaptionFormat.WEBVTT -> MimeTypes.TEXT_VTT
        CaptionFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
        // ASS/SSA is rendered by the libass bridge; Media3 still needs a MIME.
        CaptionFormat.ASS -> MimeTypes.TEXT_SSA
        CaptionFormat.UNKNOWN -> MimeTypes.TEXT_UNKNOWN
    }
}
