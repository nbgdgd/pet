package com.aniblaze.aggregator.source

import com.aniblaze.aggregator.ContentAggregator
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.ContentResult
import com.aniblaze.aggregator.model.CharacterCredit
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.PersonDetails
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StaffWork
import com.aniblaze.aggregator.model.StudioCredit
import com.aniblaze.aggregator.model.TitleCredits
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class StudioTitlePage(val titles: List<Anime>, val hasMore: Boolean)

class ShikimoriSource @Inject constructor(
    private val http: HttpClient,
    private val okHttpClient: OkHttpClient,
) : ContentAggregator {

    override val name: String = "Shikimori"
    override fun ownsContentId(contentId: String): Boolean = contentId.isNotBlank() && contentId.all(Char::isDigit)

    companion object {
        private const val GRAPHQL_URL = "https://shikimori.one/api/graphql"
        private const val REST_API = "https://shikimori.one/api"
        private const val IMAGE_BASE = "https://shikimori.one"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Сколько персонажей показываем на странице тайтла. */
        const val CHARACTERS_LIMIT = 16
        private const val JIKAN_API = "https://api.jikan.moe/v4"
    }

    private data class Cached<T>(val value: T?, val at: Long = System.currentTimeMillis())
    private val creditsCache = ConcurrentHashMap<String, Cached<TitleCredits>>()
    private val studioPageCache = ConcurrentHashMap<String, Cached<StudioTitlePage>>()
    private val personCache = ConcurrentHashMap<Int, Cached<PersonDetails>>()
    private val studioIdCache = ConcurrentHashMap<String, Int>()

    /**
     * Studios and the main director of a title. Shikimori is already a metadata
     * source in AniBlaze, so this does not add a second database or touch playback.
     * Results (including misses) live for the app session: reopening a title does
     * not repeat the title + roles requests.
     */
    suspend fun titleCredits(anime: Anime): TitleCredits? = withContext(Dispatchers.IO) {
        creditsCache[anime.id]?.let { return@withContext it.value }
        val value = try {
            val id = resolveAnimeId(anime)
            if (id == null) null else restObject("/animes/$id")?.let { details ->
                val studios = details.optJSONArray("studios").objects().mapNotNull { studio ->
                    val studioId = studio.optInt("id")
                    val name = studio.optString("name").trim()
                    name.takeIf { it.isNotBlank() }?.let { StudioCredit(studioId, it) }
                }.distinctBy { it.id.takeIf { studioId -> studioId > 0 } ?: it.name.lowercase() }
                studios.forEach { if (it.id > 0) studioIdCache[it.name.lowercase()] = it.id }

                val roleItems = restArray("/animes/$id/roles").objects()
                val directors = roleItems.mapNotNull { item ->
                    val roles = item.optJSONArray("roles").strings()
                    if (roles.none { it.equals("Director", true) }) return@mapNotNull null
                    item.optJSONObject("person")?.toPerson()
                }.distinctBy { it.id }
                // Персонажи лежат в том же ответе: «Main» / «Supporting». Отдельного
                // запроса не нужно — главные первыми, не больше CHARACTERS_LIMIT.
                val shikimoriCharacters = roleItems.mapNotNull { item ->
                    val character = item.optJSONObject("character") ?: return@mapNotNull null
                    val roles = item.optJSONArray("roles").strings()
                    character.toCharacter(main = roles.any { it.equals("Main", true) })
                }.distinctBy { it.id }.sortedByDescending { it.main }
                // У свежих тайтлов Shikimori часто без портретов (missing_preview) —
                // тогда портреты и сэйю добираются у MAL через Jikan (id совпадают).
                val characters = withJikanCharacters(id, shikimoriCharacters).take(CHARACTERS_LIMIT)
                TitleCredits(studios = studios, directors = directors, characters = characters)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "[Shikimori] title credits failed for %s", anime.title)
            throw error
        }
        creditsCache[anime.id] = Cached(value)
        value
    }

    /** One ranked studio page. [hasMore] describes the raw metadata page, before
     * AniBlaze filters out titles unavailable from the user's playback sources. */
    suspend fun studioTitles(studio: StudioCredit, page: Int, limit: Int = 24): StudioTitlePage = withContext(Dispatchers.IO) {
        val studioId = studio.id.takeIf { it > 0 } ?: resolveStudioId(studio.name)
            ?: return@withContext StudioTitlePage(emptyList(), hasMore = false)
        val safeLimit = limit.coerceIn(1, 50)
        val key = "$studioId:${page.coerceAtLeast(1)}:$safeLimit"
        studioPageCache[key]?.value?.let { return@withContext it }
        val raw = requiredRestArray("/animes?studio=$studioId&page=${page.coerceAtLeast(1)}&limit=$safeLimit&order=ranked")
        val value = StudioTitlePage(
            titles = raw.objects().mapNotNull { it.toAnimeRest(studio.name) }.distinctBy { it.id },
            hasMore = raw.length() >= safeLimit,
        )
        studioPageCache[key] = Cached(value)
        value
    }

    /** Director page. Shikimori returns every staff job; only the exact director
     * role is exposed now, while [StaffWork.role] keeps the model ready for more. */
    suspend fun personDetails(person: PersonCredit): PersonDetails? = withContext(Dispatchers.IO) {
        personCache[person.id]?.let { return@withContext it.value }
        val value = runCatching {
            val json = requiredRestObject("/people/${person.id}")
            val resolved = json.toPerson() ?: person
            // The REST person payload truncates `works` to the 50 highest-rated
            // entries and can omit every directing credit. The public works page
            // contains the complete id -> roles annotation; fetch the small anime
            // cards for the exact Director ids in one REST batch afterwards.
            val annotated = http.getHtml("$IMAGE_BASE/people/${person.id}/works")
                ?.let { html -> Jsoup.parse(html).selectFirst("[data-texts]")?.attr("data-texts") }
                ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            val directorIds = annotated.objects().mapNotNull { item ->
                val roles = item.optString("text").split(',').map(String::trim)
                item.optInt("linked_id").takeIf {
                    it > 0 && item.optString("linked_type") == "anime" &&
                        roles.any { role -> role == "Режиссёр" || role.equals("Director", true) }
                }
            }.distinct()
            val completeWorks = directorIds.chunked(50).flatMap { ids ->
                requiredRestArray("/animes?ids=${ids.joinToString(",")}&limit=50").objects()
                    .mapNotNull { anime -> anime.toAnimeRest()?.let { StaffWork(it, "Режиссёр") } }
            }
            val fallbackWorks = json.optJSONArray("works").objects().mapNotNull { item ->
                val role = item.optString("role").trim()
                if (role != "Режиссёр" && !role.equals("Director", true)) return@mapNotNull null
                val title = item.optJSONObject("anime")?.toAnimeRest() ?: return@mapNotNull null
                StaffWork(title, role.ifBlank { "Режиссёр" })
            }
            val works = (completeWorks.ifEmpty { fallbackWorks }).groupBy { it.anime.id }.map { (_, sameTitle) ->
                sameTitle.first().copy(role = sameTitle.map { it.role }.distinct().joinToString(" · "))
            }.sortedWith(compareByDescending<StaffWork> { it.anime.rating }.thenByDescending { it.anime.year })
            PersonDetails(resolved, works)
        }.getOrElse { error ->
            Timber.w(error, "[Shikimori] person failed id=%d", person.id)
            throw error
        }
        personCache[person.id] = Cached(value)
        value
    }

    private suspend fun resolveAnimeId(anime: Anime): Int? {
        anime.id.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val query = encode(anime.status.ifBlank { anime.title })
        val candidates = restArray("/animes?search=$query&limit=10").objects()
        fun norm(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val wanted = setOf(norm(anime.title), norm(anime.status)).filter { it.isNotBlank() }.toSet()
        return candidates.mapNotNull { item ->
            val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val titles = setOf(norm(item.optString("russian")), norm(item.optString("name")))
            val exact = titles.any { it in wanted }
            val year = item.optJSONObject("aired_on")?.optInt("year")
                ?: item.optString("aired_on").take(4).toIntOrNull() ?: 0
            val yearFits = anime.year == 0 || year == 0 || anime.year == year
            Triple(id, (if (exact) 10 else 0) + (if (yearFits) 2 else -4), exact || titles.any { t -> wanted.any { w -> t.contains(w) || w.contains(t) } })
        }.filter { it.third }.maxByOrNull { it.second }?.first
    }

    private suspend fun resolveStudioId(name: String): Int? {
        val key = name.trim().lowercase()
        studioIdCache[key]?.let { return it }
        val id = requiredRestArray("/studios").objects().firstOrNull {
            it.optString("name").equals(name.trim(), true) || it.optString("filtered_name").equals(name.trim(), true)
        }?.optInt("id")?.takeIf { it > 0 }
        if (id != null) studioIdCache[key] = id
        return id
    }

    private suspend fun restObject(path: String): JSONObject? =
        http.getHtml("$REST_API$path")?.let(::JSONObject)

    private suspend fun restArray(path: String): JSONArray =
        http.getHtml("$REST_API$path")?.let(::JSONArray) ?: JSONArray()

    private suspend fun requiredRestObject(path: String): JSONObject =
        http.getHtml("$REST_API$path")?.let(::JSONObject)
            ?: throw IOException("Shikimori did not return $path")

    private suspend fun requiredRestArray(path: String): JSONArray =
        http.getHtml("$REST_API$path")?.let(::JSONArray)
            ?: throw IOException("Shikimori did not return $path")

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JSONObject.toCharacter(main: Boolean): CharacterCredit? {
        val id = optInt("id").takeIf { it > 0 } ?: return null
        val name = optString("russian").ifBlank { optString("name") }.trim().ifBlank { return null }
        val image = optJSONObject("image")?.optString("preview").orEmpty().let(::absoluteImage)
        return CharacterCredit(id, name, image.takeUnless { it.contains("missing_") }.orEmpty(), main, latinName = optString("name").trim())
    }

    /**
     * Добор портретов и сэйю у Jikan (`/anime/{mal}/characters`). Зовётся, когда
     * портретов нет хотя бы у половины списка (или списка нет вовсе): один запрос,
     * закэшированный на сутки (HostPolicy). Сбой Jikan — остаёмся с тем, что есть.
     * Сопоставление — по латинскому имени без учёта порядка слов: у MAL «Gurion, Kunon»,
     * у Shikimori «Kunon Gurion».
     */
    private suspend fun withJikanCharacters(malId: Int, base: List<CharacterCredit>): List<CharacterCredit> {
        if (base.isNotEmpty() && base.count { it.image.isBlank() } * 2 < base.size) return base
        val body = runCatching { http.getHtml("$JIKAN_API/anime/$malId/characters") }.getOrNull() ?: return base
        val jikan = runCatching { parseJikanCharacters(body) }.getOrNull().orEmpty()
        if (jikan.isEmpty()) return base
        if (base.isEmpty()) return jikan
        val byName = jikan.associateBy { nameKey(it.latinName) }
        return base.map { c ->
            val match = byName[nameKey(c.latinName)] ?: return@map c
            c.copy(image = c.image.ifBlank { match.image }, seiyuu = c.seiyuu.ifBlank { match.seiyuu })
        }
    }

    /** Разбор ответа Jikan: главные первыми, имя «Фамилия, Имя» → «Имя Фамилия». */
    internal fun parseJikanCharacters(body: String): List<CharacterCredit> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return data.objects().mapNotNull { item ->
            val character = item.optJSONObject("character") ?: return@mapNotNull null
            val raw = character.optString("name").trim().ifBlank { return@mapNotNull null }
            val name = raw.split(",").map(String::trim).filter { it.isNotBlank() }.reversed().joinToString(" ")
            val image = character.optJSONObject("images")?.optJSONObject("jpg")?.optString("image_url").orEmpty()
            val seiyuu = item.optJSONArray("voice_actors").objects()
                .firstOrNull { it.optString("language").equals("Japanese", true) }
                ?.optJSONObject("person")?.optString("name").orEmpty()
                .split(",").map(String::trim).filter { it.isNotBlank() }.reversed().joinToString(" ")
            CharacterCredit(
                id = character.optInt("mal_id"), name = name,
                image = image.takeUnless { it.contains("questionmark") }.orEmpty(),
                main = item.optString("role").equals("Main", true), latinName = name, seiyuu = seiyuu,
            )
        }.distinctBy { it.id }.sortedByDescending { it.main }
    }

    private fun nameKey(name: String): String =
        name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }.sorted().joinToString(" ")

    private fun JSONObject.toPerson(): PersonCredit? {
        val id = optInt("id").takeIf { it > 0 } ?: return null
        val name = optString("russian").ifBlank { optString("name") }.trim().ifBlank { return null }
        val photo = optJSONObject("image")?.optString("original").orEmpty().let(::absoluteImage)
        return PersonCredit(id, name, photo.takeUnless { it.contains("missing_original") }.orEmpty())
    }

    private fun JSONObject.toAnimeRest(studio: String = ""): Anime? {
        val id = optInt("id").takeIf { it > 0 } ?: return null
        val title = optString("russian").ifBlank { optString("name") }.trim().ifBlank { return null }
        val aired = opt("aired_on")
        val year = when (aired) {
            is JSONObject -> aired.optInt("year")
            else -> aired?.toString()?.take(4)?.toIntOrNull() ?: 0
        }
        val statusRaw = optString("status")
        return Anime(
            id = id.toString(), title = title,
            poster = optJSONObject("image")?.optString("original").orEmpty().let(::absoluteImage),
            rating = optString("score", "0").toDoubleOrNull() ?: optDouble("score", 0.0),
            ratingMax = 10.0, year = year, studio = studio,
            episodesTotal = optInt("episodes"), episodesAvailable = optInt("episodes_aired"),
            status = when (statusRaw) { "released" -> "Завершён"; "ongoing" -> "Выходит"; "anons" -> "Анонс"; else -> statusRaw },
            airingStatus = when (statusRaw) { "released" -> 1; "ongoing" -> 2; "anons" -> 3; else -> 0 },
        )
    }

    private fun absoluteImage(value: String): String = when {
        value.isBlank() -> ""
        value.startsWith("/") -> "$IMAGE_BASE$value"
        else -> value
    }

    override suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        Timber.d("[Shikimori] search query='%s'", query)
        try {
            val body = graphql("""
                query {
                  animes(search: "${query.replace("\"", "\\\"")}", limit: 30) {
                    id
                    name
                    russian
                    score
                    status
                    episodes
                    kind
                    airedOn { year }
                    poster { id originalUrl }
                  }
                }
            """.trimIndent()) ?: return@withContext emptyList()

            val animes = body.optJSONObject("data")?.optJSONArray("animes")

            if (animes == null || animes.length() == 0) {
                Timber.w("[Shikimori] search empty response")
                return@withContext emptyList()
            }

            val results = (0 until animes.length()).mapNotNull { i ->
                animes.optJSONObject(i)?.toAnime()
            }
            Timber.d("[Shikimori] search returned %d results", results.size)
            results
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] search error")
            emptyList()
        }
    }

    override suspend fun trending(): List<Anime> = withContext(Dispatchers.IO) {
        Timber.d("[Shikimori] trending")
        try {
            val body = graphql("""
                query {
                  animes(limit: 30, order: popularity) {
                    id
                    name
                    russian
                    score
                    status
                    episodes
                    kind
                    airedOn { year }
                    poster { id originalUrl }
                  }
                }
            """.trimIndent()) ?: return@withContext emptyList()

            val animes = body.optJSONObject("data")?.optJSONArray("animes")
            if (animes == null || animes.length() == 0) {
                Timber.w("[Shikimori] trending empty, falling back to REST API")
                return@withContext trendingRest()
            }

            val results = (0 until animes.length()).mapNotNull { i ->
                animes.optJSONObject(i)?.toAnime()
            }
            Timber.d("[Shikimori] trending returned %d results", results.size)
            results
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] trending error, fallback to REST")
            trendingRest()
        }
    }

    private suspend fun trendingRest(): List<Anime> = withContext(Dispatchers.IO) {
        try {
            val url = "$REST_API/animes?page=1&limit=30&order=popularity&status=ongoing"
            val body = http.getHtml(url) ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.toAnimeRest()
            }
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] trending REST fallback error")
            emptyList()
        }
    }

    override suspend fun catalog(category: String): List<Anime> = withContext(Dispatchers.IO) {
        if (category != "anons") return@withContext emptyList()
        Timber.d("[Shikimori] catalog category='%s'", category)
        try {
            val body = graphql("""
                query {
                  animes(limit: 30, status: "anons", order: popularity) {
                    id
                    name
                    russian
                    score
                    status
                    episodes
                    kind
                    airedOn { year }
                    poster { id originalUrl }
                  }
                }
            """.trimIndent()) ?: return@withContext emptyList()

            val animes = body.optJSONObject("data")?.optJSONArray("animes")
            if (animes == null || animes.length() == 0) {
                Timber.w("[Shikimori] catalog %s empty", category)
                return@withContext emptyList()
            }

            (0 until animes.length()).mapNotNull { i ->
                animes.optJSONObject(i)?.toAnime()
            }
        } catch (e: Exception) {
            Timber.w(e, "[Shikimori] catalog %s error", category)
            emptyList()
        }
    }

    override suspend fun getContentSegments(contentId: String): List<Segment> {
        return emptyList()
    }

    override suspend fun extractContent(contentId: String, segment: Int): ContentResult? {
        return null
    }

    override suspend fun validateSource(contentId: String): Boolean =
        http.isReachable("https://shikimori.me")

    // ---- GraphQL ----

    private suspend fun graphql(query: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().apply { put("query", query) }
            val requestBody = json.toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(GRAPHQL_URL)
                .header("User-Agent", "Mozilla/5.0 (Android 14) AniBlaze/1.0")
                .header("Accept", "application/json")
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("[Shikimori] GraphQL HTTP %d: %s", response.code, response.body?.string())
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                JSONObject(body)
            }
        }.onFailure { Timber.w(it, "[Shikimori] GraphQL error") }.getOrNull()
    }

    // ---- Mappers ----

    private fun JSONObject.toAnime(): Anime? {
        val id = optString("id", "").ifBlank { return null }
        val name = optString("name", "")
        val russian = optString("russian", "")
        val title = russian.ifBlank { name }.ifBlank { return null }
        val score = optDouble("score", 0.0)
        val status = optString("status", "").let { s ->
            when (s) {
                "anons" -> "Анонс"
                "ongoing" -> "Онгоинг"
                "released" -> "Вышел"
                else -> s
            }
        }
        val poster = optJSONObject("poster")?.optString("originalUrl", "")
            ?.let { if (it.startsWith("/")) "$IMAGE_BASE$it" else it } ?: ""
        val kind = optString("kind", "")

        return Anime(
            id = id,
            title = title,
            poster = poster,
            description = "Тип: ${kindRu(kind)}",
            rating = score,
            ratingMax = 10.0,
            status = status,
        )
    }

    private fun JSONObject.toAnimeRest(): Anime? {
        val id = optInt("id", 0).takeIf { it > 0 }?.toString() ?: return null
        val name = optString("name", "")
        val russian = optString("russian", "")
        val title = russian.ifBlank { name }.ifBlank { return null }
        val score = optString("score", "0.0").toDoubleOrNull() ?: 0.0
        val status = optString("status", "")
        val poster = optJSONObject("image")?.optString("original", "")
            ?.let { if (it.startsWith("/")) "$IMAGE_BASE$it" else it } ?: ""
        val kind = optString("kind", "")

        return Anime(
            id = id,
            title = title,
            poster = poster,
            description = "Тип: ${kindRu(kind)}",
            rating = score,
            ratingMax = 10.0,
            status = status,
        )
    }

    private fun kindRu(kind: String): String = when (kind) {
        "tv" -> "ТВ Сериал"
        "movie" -> "Фильм"
        "ova" -> "OVA"
        "ona" -> "ONA"
        "special" -> "Спецвыпуск"
        "tv_special" -> "TV Спецвыпуск"
        "music" -> "Клип"
        "pv" -> "Проморолик"
        "cm" -> "Реклама"
        else -> kind
    }
}

// force rebuild
