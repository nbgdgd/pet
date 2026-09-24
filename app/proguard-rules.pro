# Keep Jsoup (used reflectively by the aggregators).
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Media3 / ExoPlayer.
-dontwarn com.google.android.exoplayer2.**
-keep class androidx.media3.** { *; }
