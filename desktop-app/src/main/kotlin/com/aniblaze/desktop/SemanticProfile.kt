package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class SemanticProfile(
    val themes: List<String> = emptyList(),
    val setting: List<String> = emptyList(),
    val tone: List<String> = emptyList(),
    val characterDynamics: List<String> = emptyList(),
    val narrativeStyle: List<String> = emptyList(),
    val storyFocus: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
) {
    fun facets() = listOf(themes, setting, tone, characterDynamics, narrativeStyle, storyFocus, keywords)
    fun sanitized(): SemanticProfile {
        fun clean(values: List<String>) = values.take(8).map { it.lowercase().replace('ё', 'е').trim().take(60) }
            .filter { it.isNotBlank() && it.none(Char::isISOControl) }.distinct()
        return SemanticProfile(clean(themes), clean(setting), clean(tone), clean(characterDynamics),
            clean(narrativeStyle), clean(storyFocus), clean(keywords))
    }
    val isEmpty get() = facets().all { it.isEmpty() }
}

object AnimeSemantics {
    // Bump when extraction instructions/vocabulary/schema change, not for score tuning.
    const val VERSION = 1
    val fields = listOf("themes", "setting", "tone", "characterDynamics", "narrativeStyle", "storyFocus", "keywords")
    private val weights = listOf(2.0, 1.2, 2.0, 1.5, 1.5, 2.0, 0.6)
    private val spaces = Regex("\\s+")
    fun metadata(anime: Anime): List<String> = listOf(anime.title.take(240), anime.status.take(240), anime.description.take(3000),
        anime.genres.take(500), anime.studio.take(160), anime.contentType.take(80), anime.country.take(80), anime.year.toString())
        .map { it.replace(spaces, " ").trim() }
    fun cacheKey(anime: Anime): String {
        // Length prefix prevents delimiter collisions. Popularity/airing progress are
        // deliberately absent: they cannot change story semantics.
        val canonical = metadata(anime).joinToString("") { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${anime.id}|$VERSION|$hash"
    }

    /** Sparse weighted facet Jaccard. Missing metadata is not evidence of similarity. */
    fun similarity(a: SemanticProfile, b: SemanticProfile): Double {
        var denominator = 0.0
        var numerator = 0.0
        a.facets().zip(b.facets()).forEachIndexed { index, (left, right) ->
            val x = left.toSet(); val y = right.toSet()
            if (x.isEmpty() && y.isEmpty()) return@forEachIndexed
            denominator += weights[index]
            numerator += weights[index] * x.intersect(y).size / x.union(y).size.coerceAtLeast(1)
        }
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    fun shared(a: SemanticProfile, b: SemanticProfile): List<String> =
        listOf(0, 2, 5, 3, 4, 1, 6).flatMap { i -> a.facets()[i].intersect(b.facets()[i].toSet()) }.distinct().take(3)

    /** Cheap context extraction before/without AI. No network, no genre-only inference
     * about tone: words must actually occur in the synopsis. */
    fun local(anime: Anime): SemanticProfile {
        val text = anime.description.lowercase().replace('ё', 'е')
        fun matches(vararg entries: Pair<String, List<String>>) = entries.filter { (_, roots) ->
            roots.any { text.contains(it) }
        }.map { it.first }
        return SemanticProfile(
            themes = Recommender.themesOf(anime).sorted().take(8),
            tone = matches("меланхолия" to listOf("меланхол", "утрат", "одиночеств"),
                "мрачность" to listOf("мрачн", "жесток", "кошмар"),
                "комедия" to listOf("комед", "смешн", "забавн"),
                "психологизм" to listOf("психолог", "внутренн", "травм")),
            setting = matches("фантастический мир" to listOf("фантастическ", "мир магии"),
                "космос" to listOf("космос", "космич"), "школа" to listOf("школ", "академи")),
            characterDynamics = matches("дружба" to listOf("дружб", "друзья"),
                "наставничество" to listOf("наставник", "ученик"), "романтика" to listOf("любов", "влюбл")),
            narrativeStyle = matches("путешествие" to listOf("путешеств", "странств"),
                "расследование" to listOf("расслед", "детектив")),
            storyFocus = matches("исследование мира" to listOf("исслед", "неизведан"),
                "взросление" to listOf("взрослен", "повзросле"), "выживание" to listOf("выжива", "выжить")),
        ).sanitized()
    }
}
