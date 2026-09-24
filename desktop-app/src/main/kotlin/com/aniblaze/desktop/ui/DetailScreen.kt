package com.aniblaze.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.CommentBatch
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.model.StudioCredit
import com.aniblaze.aggregator.model.TitleCredits
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.player.PlayerDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff

@Composable
fun DetailScreen(
    anime: Anime,
    isCinema: Boolean,
    repository: DesktopRepository,
    settings: AppSettings,
    onPlay: (Segment) -> Unit,
    onOpenRelated: (Anime) -> Unit = {},
    onOpenStudio: (StudioCredit) -> Unit = {},
    onOpenPerson: (PersonCredit) -> Unit = {},
) {
    val state by settings.state.collectAsState()
    val isFav = remember(state) { settings.isFavorite(anime.id) }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<Anime>>(emptyList()) }
    // Air dates + release status + next-episode countdown, fetched apart from the
    // episode list so a slow lookup never holds the list back.
    var schedule by remember(anime.id) {
        mutableStateOf(com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule.EMPTY)
    }
    val airDates = schedule.dates
    // «Похожие» — лента постеров под списком серий (рекомендации источника плюс наш
    // подбор по каталогу, см. DesktopRepository.similarTitles).
    var recommendations by remember(anime.id) { mutableStateOf<List<Anime>>(emptyList()) }
    // Раскрыт ли остаток подборки кнопкой «Показать ещё».
    var similarExpanded by remember(anime.id) { mutableStateOf(false) }
    // Полное описание: карточка из каталога несёт обрезок (~200 символов), полный
    // текст догружается с релиза и подменяет его, когда реально длиннее.
    var fullDescription by remember(anime.id) { mutableStateOf<String?>(null) }
    // Страна производства. Карточки каталога её не несут — приходит вместе с
    // полным описанием релиза.
    var detailedCountry by remember(anime.id) { mutableStateOf("") }
    var detailedEpisodesTotal by remember(anime.id) { mutableStateOf(0) }
    var credits by remember(anime.id) { mutableStateOf<TitleCredits?>(null) }
    var creditsFailed by remember(anime.id) { mutableStateOf(false) }
    var creditsRetryNonce by remember(anime.id) { mutableStateOf(0) }
    // Комментарии сообщества (Anixart), постранично.
    var comments by remember(anime.id) { mutableStateOf<List<com.aniblaze.aggregator.model.TitleComment>>(emptyList()) }
    var commentsBusy by remember(anime.id) { mutableStateOf(false) }
    var commentsEnded by remember(anime.id) { mutableStateOf(false) }
    /**
     * Сколько страниц разрешено обойти. Растёт по нажатию «Ещё комментарии».
     *
     * Это бюджет, а не конец обсуждения: репозиторий идёт, пока источник приносит
     * новое (см. [com.aniblaze.desktop.DesktopRepository.commentFeed]), и сам скажет,
     * что обход завершён. Кэш общий, поэтому расширение бюджета догружает продолжение,
     * а не перечитывает начало.
     */
    var commentBudget by remember(anime.id) { mutableStateOf(DETAIL_COMMENT_PAGES) }
    val commentScope = androidx.compose.runtime.rememberCoroutineScope()
    // Раскрытые ветки ответов: id комментария → его ответы.
    var openReplies by remember(anime.id) { mutableStateOf<Map<Long, List<com.aniblaze.aggregator.model.TitleComment>>>(emptyMap()) }
    fun toggleReplies(comment: com.aniblaze.aggregator.model.TitleComment) {
        if (openReplies.containsKey(comment.id)) {
            openReplies = openReplies - comment.id
        } else {
            commentScope.launch {
                val thread = runCatching { repository.commentReplies(comment.id) }.getOrDefault(emptyList())
                if (thread.isNotEmpty()) openReplies = openReplies + (comment.id to thread)
            }
        }
    }
    // Загрузка обсуждения — ЭФФЕКТОМ, ПРИВЯЗАННЫМ К ТАЙТЛУ, а не задачей в вольной
    // области видимости.
    //
    // Здесь стоял `commentScope.launch` из rememberCoroutineScope, и это протекало:
    // область живёт всю жизнь экрана, а состояние сбрасывается по `remember(anime.id)`.
    // Ушёл на другой тайтл, пока страница догружалась, — запоздалый ответ записывал
    // чужие комментарии в состояние НОВОГО тайтла. LaunchedEffect с ключом отменяет
    // прошлый обход сам, и записать в чужое состояние ему уже нечем.
    LaunchedEffect(anime.id, commentBudget) {
        commentsBusy = true
        try {
            repository.commentFeed(anime, maxPages = commentBudget).collect { batch ->
                // «Сначала лучшие» ставится У СЕБЯ и по ПОЛНОМУ списку. У источника этот
                // порядок неоднозначен и ломал сам обход — разбор у AnixartSource.comments.
                comments = batch.comments.sortedByDescending { it.votes }
                commentsEnded = batch.complete
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PlayerDiagnostics.failure("detail.comments", error)
        } finally {
            commentsBusy = false
        }
    }
    var loading by remember { mutableStateOf(true) }
    // Источник не ответил (в отличие от «у тайтла честно нет серий»).
    var segmentsFailed by remember(anime.id) { mutableStateOf(false) }
    var reloadNonce by remember(anime.id) { mutableStateOf(0) }
    // Cinema series: episodes carry a "С<n> · …" prefix; group them by season.
    var selectedSeason by remember(anime.id) { mutableStateOf(1) }

    LaunchedEffect(anime.id, isCinema) {
        if (isCinema) try {
            repository.prepareCinemaPlayback(anime.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PlayerDiagnostics.failure("cinema.prepare", error)
        }
    }

    // Both anime and cinema resolve through the repository by id — cinema is routed
    // to the Lordfilm source, which returns the film as a single segment and, on
    // play, a signed .m3u8 that plays in the in-app VLC surface.
    LaunchedEffect(anime.id, reloadNonce) {
        loading = true
        val result = repository.segmentsDetailed(anime.id, title = anime.title, year = anime.year)
        segments = result.segments
        segmentsFailed = result.failed
        loading = false
        // Opening the title acknowledges its "new episode" badge, and the fresh count
        // becomes the baseline the notifier compares against next time.
        //
        // Порядок важен: recordEpisodeCount САМ поднимает пометку, когда серий стало
        // больше. Сначала чистить, потом записывать — значит вернуть пометку на место
        // сразу после того, как её сняли, и она уже не снимется никогда.
        val availableCount = segments.filter { it.playable }.maxOfOrNull { it.number } ?: 0
        if (availableCount > 0) settings.recordEpisodeCount(anime.id, availableCount)
        repository.observeAvailableEpisodes(anime, segments)
        settings.clearNewEpisode(anime.id)
    }
    // Other seasons / franchise entries (anime only) for the season selector.
    //
    // «Похожие» ждут этот же список и грузятся следом, а не параллельно: сезоны — это
    // ровно то, чего в похожих быть не должно, и знать их надо ДО отбора. Отдельным
    // эффектом это стоило бы либо второго обхода каталога, либо блока, в котором
    // секунду висят собственные сезоны тайтла.
    LaunchedEffect(anime.id) {
        val franchise = if (isCinema) emptyList() else repository.seasons(anime)
        seasons = franchise
        recommendations = repository.similarTitles(anime, franchise)
    }
    LaunchedEffect(anime.id) {
        schedule = repository.titleSchedule(anime)
    }
    LaunchedEffect(anime.id) {
        val full = repository.fullDetails(anime)
        // Only replace when the release really carries MORE text than the card.
        full?.description?.takeIf { it.length > anime.description.length }?.let { fullDescription = it }
        full?.country?.takeIf { it.isNotBlank() }?.let { detailedCountry = it }
        full?.episodesTotal?.takeIf { it > 0 }?.let { detailedEpisodesTotal = it }
    }
    LaunchedEffect(anime.id, creditsRetryNonce) {
        creditsFailed = false
        try {
            credits = repository.titleCredits(anime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            creditsFailed = true
            PlayerDiagnostics.failure("detail.credits", error)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
        val sideScroll = rememberScrollState()
        Column(
            Modifier.width(280.dp).fillMaxSize().padding(16.dp)
                .browserAutoScroll(sideScroll)
                .verticalScroll(sideScroll),
        ) {
            // Одноразовый вход: постер и заголовок «поднимаются» при открытии.
            val appear = remember(anime.id) { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(anime.id) {
                appear.animateTo(1f, androidx.compose.animation.core.tween(420))
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                    .padding(top = (18 * (1f - appear.value)).dp)
                    .graphicsLayer { alpha = appear.value }
                    .clip(Shapes.card).background(Surface2),
            ) {
                AsyncImage(
                    model = posterUrl(anime.poster, width = 500),
                    imageLoader = posterImageLoader(anime.id),
                    contentDescription = anime.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).graphicsLayer { alpha = appear.value },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(anime.title, style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
                // Колокольчик: напоминать ли о новых сериях (трей и окошко питомца).
                // Есть только у избранного — о прочем и так не напоминаем. Сюда же
                // возвращается тайтл после «Не напоминать» в окошке.
                if (isFav) {
                    val muted = remember(state) { settings.isReleaseMuted(anime.id, state) }
                    IconButton(onClick = { settings.setReleaseMuted(anime.id, !muted) }) {
                        Icon(
                            if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                            contentDescription = if (muted) "Напоминать о сериях" else "Не напоминать о сериях",
                            tint = if (muted) TextTertiary else AccentOrange,
                        )
                    }
                }
                IconButton(onClick = { settings.toggleFavorite(anime) }) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Избранное",
                        tint = AccentOrange,
                    )
                }
            }
            // Личная оценка. Стоит СРАЗУ под названием, выше фактов и описания: это
            // единственный элемент страницы, который человек здесь ЗАПОЛНЯЕТ, а не
            // читает, и искать его под простынёй описания он не станет.
            val myRating by remember(settings, anime.id) {
                settings.state.map { settings.ratingOf(anime.id, it) }.distinctUntilChanged()
            }.collectAsState(settings.ratingOf(anime.id))
            RatingStars(
                rating = myRating,
                onRate = { settings.setRating(anime, it) },
                modifier = Modifier.padding(top = 10.dp).graphicsLayer { alpha = appear.value },
            )
            // Факты о тайтле — плашками, как карточки в статистике.
            // Страна берётся из полной карточки релиза — в списках каталога её нет,
            // поэтому показываем ту, что придёт с описанием.
            val country = detailedCountry.ifBlank { anime.country }
            val facts = buildList {
                if (anime.rating > 0) add("★ ${"%.1f".format(anime.rating)}")
                if (anime.year > 0) add(anime.year.toString())
                episodesFact(available = segments.count { it.playable }, total = maxOf(detailedEpisodesTotal, anime.episodesTotal))
                    ?.let { add(it) }
                country.takeIf { it.isNotBlank() }?.let { add("${countryFlag(it)} $it") }
            }
            if (facts.isNotEmpty()) {
                FlowChips(facts, modifier = Modifier.padding(top = 8.dp))
            }
            val genreChips = remember(anime.genres) {
                anime.genres.split(',', '·', '/').map(String::trim).filter { it.isNotBlank() }.take(6)
            }
            if (genreChips.isNotEmpty()) {
                FlowChips(genreChips, accent = false, modifier = Modifier.padding(top = 6.dp))
            }
            val studioLinks = credits?.studios.orEmpty().ifEmpty {
                anime.studio.split(',', '·', '/').map(String::trim).filter(String::isNotBlank)
                    .map { StudioCredit(0, it) }
            }
            if (studioLinks.isNotEmpty()) {
                CreditLinks(
                    label = if (studioLinks.size == 1) "Студия" else "Студии",
                    links = studioLinks.map { studio -> studio.name to { onOpenStudio(studio) } },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            val directors = credits?.directors.orEmpty()
            if (directors.isNotEmpty()) {
                CreditLinks(
                    label = if (directors.size == 1) "Режиссёр" else "Режиссёры",
                    links = directors.map { person -> person.name to { onOpenPerson(person) } },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (creditsFailed) {
                Text(
                    "Студия и режиссёр не загрузились · Повторить",
                    color = AccentOrange,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp).clickable { creditsRetryNonce++ },
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxSize().padding(16.dp)) {
            val shownDescription = fullDescription ?: anime.description
            if (shownDescription.isNotBlank()) {
                TitleDescriptionCard(anime.title, shownDescription, Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }
            // Персонажи — из того же ответа Shikimori, что студия и режиссёр.
            val characters = credits?.characters.orEmpty()
            if (characters.isNotEmpty()) {
                CharactersRow(characters, Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentOrange)
                }
                segments.isEmpty() -> Column {
                    // Переключатель сезонов живёт и здесь: раньше он рисовался только
                    // вместе со списком серий, и у сезона без серий (анонс, нет видео)
                    // уйти на другой сезон было невозможно.
                    if (!isCinema && seasons.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Серии", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            SeasonSelector(anime, seasons, state, onOpenRelated)
                        }
                    }
                    val emptyState = emptyEpisodesState(anime, schedule, segmentsFailed)
                    // Сбой источника и отсутствие серий — разные сообщения: во втором
                    // случае повторять нечего, в первом это единственное, что нужно.
                    if (emptyState == EmptyEpisodes.FAILED) {
                        Text("Источник не ответил — серии не загрузились.", color = TextPrimary)
                        Text(
                            "Это сбой связи или источника, а не отсутствие серий.",
                            color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                        Button(
                            onClick = { reloadNonce++ },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = OledBlack),
                            modifier = Modifier.padding(top = 10.dp),
                        ) { Text("Повторить", fontWeight = FontWeight.SemiBold) }
                    } else if (emptyState == EmptyEpisodes.UPCOMING) {
                        UpcomingBlock(
                            schedule = schedule,
                            onPremierePassed = {
                                // Таймер дошёл до нуля: перечитываем статус у источника и
                                // список серий. Серии «появятся» только если их отдаст источник.
                                schedule = repository.titleSchedule(anime, refresh = true)
                                reloadNonce++
                            },
                        )
                    } else {
                        Text(if (isCinema) "Не удалось получить видео." else "Серии пока недоступны.")
                        if (!isCinema && emptyState == EmptyEpisodes.NO_VIDEO_RELEASED) {
                            Text(
                                "Тайтл уже выходит, но у источника пока нет видео — это не анонс.",
                                color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    // Nothing to watch here yet — at least suggest where to go next.
                    SimilarBlock(recommendations, similarExpanded, { similarExpanded = !similarExpanded }, onOpenRelated)
                }
                else -> {
                    // Prominent primary action: resume the in-progress episode, else
                    // start from the first (single "film" segment for cinema).
                    val target = remember(segments, state) {
                        val prefKey = anime.id.substringBefore(":t")
                        val pick = settings.resumeSegment(
                            contentId = anime.id,
                            prefKey = prefKey,
                            segments = segments.map { it.number },
                            playable = { number -> segments.any { it.number == number && it.playable } },
                        )
                        segments.firstOrNull { it.number == pick } ?: segments.firstOrNull { it.playable }
                    }
                    // «Продолжить» — только когда серия действительно начата; иначе
                    // это «смотреть с такой-то серии», и подписывать её «продолжить»
                    // было бы враньём.
                    val resumeSeg = target?.takeIf { settings.progressFraction(anime.id, it.number) > 0f }
                    if (target != null) {
                        Button(
                            onClick = { settings.recordHistory(anime); onPlay(target) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = OledBlack),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(
                                when {
                                    resumeSeg != null && !isCinema -> "Продолжить · Серия ${resumeSeg.number}"
                                    resumeSeg != null -> "Продолжить"
                                    isCinema -> "Смотреть"
                                    else -> "Смотреть · Серия ${target.number}"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    val seasonNums = remember(segments) { segments.mapNotNull { seasonOf(it.title) }.distinct().sorted() }
                    val multiSeason = seasonNums.size > 1
                    val resumeSeason = remember(segments, target?.id) { initialSeasonForEpisodes(segments, target) }
                    LaunchedEffect(anime.id, seasonNums, resumeSeason) {
                        if (multiSeason) selectedSeason = resumeSeason
                    }
                    val shown = if (multiSeason) segments.filter { seasonOf(it.title) == selectedSeason } else segments
                    // Movie = one "Смотреть" segment: the big button covers it, no list.
                    if (!(isCinema && segments.size <= 1)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (multiSeason) "Сезоны и серии" else "Серии",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (seasons.isNotEmpty()) SeasonSelector(anime, seasons, state, onOpenRelated)
                        }
                        // Сколько серий всего / вышло / когда следующая — плашками, как
                        // карточки в статистике, а не строкой текста под заголовком.
                        if (!isCinema) {
                            EpisodesSummaryCard(
                                tiles = episodeSummaryTiles(
                                    schedule = schedule,
                                    available = segments.count { it.playable },
                                    catalogTotal = maxOf(detailedEpisodesTotal, anime.episodesTotal),
                                ),
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                        if (multiSeason) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                                items(seasonNums) { s ->
                                    SeasonChip("Сезон $s", s == selectedSeason) { selectedSeason = s }
                                }
                            }
                        }
                        // Прокрутка средней кнопкой — как на главной: список серий
                        // и комментарии здесь такие же длинные.
                        val episodesState = androidx.compose.foundation.lazy.rememberLazyListState()
                        // Серии — плитками в несколько колонок (макет), а не строками на
                        // всю ширину: на широком окне строка в 1800 px под «Серия 3» —
                        // пустое место. Ширина плитки ≥ 240 dp, колонок — сколько влезает.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val tilesPerRow = if (isCinema) 1 else maxOf(1, (maxWidth / scaledForFont(EPISODE_TILE_MIN_WIDTH)).toInt())
                        val rows = remember(shown, tilesPerRow) { shown.chunked(tilesPerRow) }
                        LazyColumn(
                            state = episodesState,
                            modifier = Modifier.browserAutoScroll(episodesState),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(rows, key = { it.first().id }) { rowSegments ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowSegments.forEach { seg ->
                                androidx.compose.runtime.key(seg.id) {
                                Box(Modifier.weight(1f)) {
                                val progress = remember(state, seg.id) { settings.progressFraction(anime.id, seg.number) }
                                val watched = remember(state, seg.id) { settings.isWatched(anime.id, seg.number) }
                                val label = (if (multiSeason) seg.title.replace(Regex("^С\\d+ · "), "") else seg.title)
                                    .ifBlank { "Серия ${seg.number}" }
                                EpisodeRow(
                                    title = if (seg.playable) label else "$label · поток пока недоступен",
                                    // Cinema segments carry their own TMDB date; anime
                                    // dates come from the separate schedule lookup.
                                    released = seg.releaseDate.ifBlank { null }?.let(::formatIsoDate)
                                        ?: airDates[seg.number]?.let(::formatEpochDate),
                                    progress = progress,
                                    enabled = seg.playable,
                                    watched = watched,
                                    onToggleWatched = { settings.setWatched(anime.id, seg.number, !watched) },
                                ) {
                                    settings.recordHistory(anime)
                                    onPlay(seg)
                                }
                                }
                                }
                                }
                                // Последний ряд неполный — пустые ячейки держат ширину плиток.
                                repeat(tilesPerRow - rowSegments.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            // «Похожие» — after the last episode, scrolling with the list.
                            if (recommendations.isNotEmpty()) {
                                item(key = "recommendations") {
                                    SimilarBlock(
                                        recommendations,
                                        similarExpanded,
                                        { similarExpanded = !similarExpanded },
                                        onOpenRelated,
                                    )
                                }
                            }
                            // Комментарии сообщества — снизу, с постраничной догрузкой.
                            if (comments.isNotEmpty()) {
                                item(key = "commentsHeader") {
                                    Text(
                                        "Комментарии",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                                    )
                                }
                                items(comments, key = { com.aniblaze.aggregator.source.commentIdentity(it) }) { comment ->
                                    Column {
                                        CommentRow(
                                            comment,
                                            fontSize = state.commentFontSize,
                                            repliesOpen = openReplies.containsKey(comment.id),
                                            onToggleReplies = if (comment.replyCount > 0) ({ toggleReplies(comment) }) else null,
                                        )
                                        // Ветка ответов — с отступом под родителем.
                                        openReplies[comment.id]?.forEach { reply ->
                                            Box(Modifier.padding(start = 40.dp)) {
                                                CommentRow(reply, fontSize = state.commentFontSize)
                                            }
                                        }
                                    }
                                }
                                if (!commentsEnded) {
                                    item(key = "commentsMore") {
                                        OutlinedButton(
                                            onClick = { commentBudget += DETAIL_COMMENT_PAGES },
                                            enabled = !commentsBusy,
                                            modifier = Modifier.padding(vertical = 6.dp),
                                        ) {
                                            Text(if (commentsBusy) "Загрузка…" else "Ещё комментарии")
                                        }
                                    }
                                }
                            }
                        }
                        }
                    } else {
                        // Фильм: списка серий нет — большая кнопка уже всё покрыла. Но
                        // «Похожие» пропадали ЗДЕСЬ ЖЕ, вместе со списком: блок жил
                        // внутри той же ветки. Теперь у него своя прокручиваемая колонка.
                        val movieScroll = rememberScrollState()
                        Column(
                            Modifier.fillMaxSize()
                                .browserAutoScroll(movieScroll)
                                .verticalScroll(movieScroll),
                        ) {
                            SimilarBlock(
                                recommendations,
                                similarExpanded,
                                { similarExpanded = !similarExpanded },
                                onOpenRelated,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreditLinks(
    label: String,
    links: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("$label:", color = TextSecondary, fontSize = 13.sp)
        links.forEachIndexed { index, (name, open) ->
            Text(
                name + if (index < links.lastIndex) "," else "",
                color = AccentOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = open).padding(vertical = 2.dp),
            )
        }
    }
}

/** One community comment: avatar, author, date, score, text. Spoilers open on click.
 *  [fontSize] — настройка «размер шрифта комментариев» (sp). */
@Composable
private fun CommentRow(
    comment: com.aniblaze.aggregator.model.TitleComment,
    fontSize: Int = 13,
    repliesOpen: Boolean = false,
    onToggleReplies: (() -> Unit)? = null,
) {
    var spoilerOpen by remember(comment.id) { mutableStateOf(false) }
    val metaSize = (fontSize - 1).coerceAtLeast(10).sp
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Surface2)) {
            if (comment.avatar.isNotBlank()) {
                AsyncImage(
                    model = comment.avatar,
                    contentDescription = comment.author,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = fontSize.sp)
                comment.timestamp.takeIf { it > 0 }?.let { ts ->
                    formatEpochDate(ts)?.let {
                        Text("  ·  $it", color = TextSecondary, fontSize = metaSize)
                    }
                }
                if (comment.votes != 0) {
                    Text(
                        "  ·  ${if (comment.votes > 0) "▲" else "▼"} ${kotlin.math.abs(comment.votes)}",
                        color = if (comment.votes > 0) AccentOrange else TextSecondary,
                        fontSize = metaSize,
                    )
                }
            }
            if (comment.isSpoiler && !spoilerOpen) {
                Text(
                    "Спойлер — показать",
                    color = AccentOrange,
                    fontSize = fontSize.sp,
                    modifier = Modifier.clickable { spoilerOpen = true }.padding(top = 2.dp),
                )
            } else {
                Text(comment.message, color = TextSecondary, fontSize = fontSize.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (onToggleReplies != null) {
                Text(
                    if (repliesOpen) "Скрыть ответы" else "Ответы (${comment.replyCount})",
                    color = AccentOrange,
                    fontSize = metaSize,
                    modifier = Modifier.clickable { onToggleReplies() }.padding(top = 4.dp),
                )
            }
        }
    }
}


/** Плашки-факты: рейтинг, год, число серий, студия, жанры. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(values: List<String>, accent: Boolean = true, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.forEach { value ->
            Box(
                Modifier.clip(Shapes.chip)
                    .background(if (accent) Surface2 else Color(0xFF17171E))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    value,
                    color = if (accent) TextPrimary else TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Флажок к названию страны. Незнакомая страна остаётся без флага, а не с чужим. */
/**
 * Плашка «сколько серий»: всего по каталогу — главное число, доступно к просмотру —
 * когда их меньше (онгоинг или анонс, у которого пока 0). Null — сказать нечего.
 */
internal fun episodesFact(available: Int, total: Int): String? = when {
    total > 0 && available in 0 until total -> "$available из $total ${episodeWord(total)}"
    total > 0 -> "$total ${episodeWord(total)}"
    available > 0 -> "$available ${episodeWord(available)}"
    else -> null
}

private fun countryFlag(country: String): String = when {
    country.startsWith("Япон", true) -> "🇯🇵"
    country.startsWith("Кит", true) -> "🇨🇳"
    country.startsWith("Коре", true) || country.startsWith("Южн", true) -> "🇰🇷"
    country.startsWith("США", true) || country.startsWith("Амер", true) -> "🇺🇸"
    country.startsWith("Росс", true) -> "🇷🇺"
    country.startsWith("Франц", true) -> "🇫🇷"
    country.startsWith("Тайв", true) -> "🇹🇼"
    else -> ""
}

private fun episodeWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "серий"
        mod10 == 1 -> "серия"
        mod10 in 2..4 -> "серии"
        else -> "серий"
    }
}

/**
 * Блок «Похожие»: компактная лента + «Показать ещё» на весь остаток.
 *
 * Двухступенчато, потому что подборка теперь заметно больше десятка: одной лентой её
 * пришлось бы прокручивать вбок полминуты, а сеткой сразу — она вытеснила бы со
 * страницы всё остальное. Первыми идут самые близкие (порядок задаёт
 * [com.aniblaze.desktop.Recommender.similar]), поэтому обрезка сверху ничего не теряет.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SimilarBlock(
    items: List<Anime>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: (Anime) -> Unit,
) {
    if (items.isEmpty()) return
    val previewCount = com.aniblaze.desktop.Recommender.SIMILAR_PREVIEW
    val preview = remember(items) { items.take(previewCount) }
    val rest = remember(items) { items.drop(previewCount) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Похожие", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        // Та же лента, что на главной, — и тянется правой кнопкой так же.
        val strip = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyRow(
            state = strip,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 10.dp).dragScroll(strip),
        ) {
            items(preview, key = { it.id }) { rec ->
                Box(Modifier.width(140.dp)) {
                    PosterCard(rec, onClick = { onOpen(rec) })
                }
            }
        }
        if (expanded && rest.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rest.forEach { rec ->
                    Box(Modifier.width(140.dp)) {
                        PosterCard(rec, onClick = { onOpen(rec) })
                    }
                }
            }
        }
        if (rest.isNotEmpty()) {
            OutlinedButton(onClick = onToggleExpanded, modifier = Modifier.padding(top = 10.dp)) {
                Text(if (expanded) "Свернуть" else "Показать ещё · ${rest.size}")
            }
        }
    }
}

/** Season number from a "С2 · Серия 1 · …" episode title, or null. */
private fun seasonOf(title: String): Int? = Regex("^С(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()

/** Initial chip for a flattened cinema series. The segment numbers span every
 * season, so always starting the chips at season 1 can hide the actual resume
 * episode (for example S2E1) even though the primary button targets it correctly. */
internal fun initialSeasonForEpisodes(segments: List<Segment>, target: Segment?): Int {
    val available = segments.mapNotNull { seasonOf(it.title) }.distinct().sorted()
    return target?.let { seasonOf(it.title) }?.takeIf { it in available }
        ?: available.firstOrNull()
        ?: 1
}

private val RU_MONTHS = listOf(
    "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
)

/** "10 янв 2025", or "через 3 дн." for an episode that hasn't aired yet. */
private fun formatDate(date: java.time.LocalDate, today: java.time.LocalDate = java.time.LocalDate.now()): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "сегодня"
        days == 1L -> "завтра"
        days in 2..13 -> "через $days дн."
        days == -1L -> "вчера"
        date.year == today.year -> "${date.dayOfMonth} ${RU_MONTHS[date.monthValue - 1]}"
        else -> "${date.dayOfMonth} ${RU_MONTHS[date.monthValue - 1]} ${date.year}"
    }
}

/** AniList airing timestamps are epoch seconds in the viewer's local zone. */
private fun formatEpochDate(epochSeconds: Long): String? = runCatching {
    formatDate(
        java.time.Instant.ofEpochSecond(epochSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate(),
    )
}.getOrNull()

/** TMDB hands cinema episodes a plain "2025-01-10". */
private fun formatIsoDate(iso: String): String? =
    runCatching { formatDate(java.time.LocalDate.parse(iso)) }.getOrNull()

/** "8 авг в 17:30" — for the next-episode line, where the hour matters. */
private fun formatDateTime(epochSeconds: Long, today: java.time.LocalDate = java.time.LocalDate.now()): String? = runCatching {
    val zoned = java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault())
    "${formatDate(zoned.toLocalDate(), today)} в %02d:%02d".format(zoned.hour, zoned.minute)
}.getOrNull()

/**
 * Почему список серий пуст. Три разных ответа, и путать их нельзя: сбой — предложить
 * повторить; анонс — показать ожидание; вышедшее без видео — сказать именно это.
 */
internal enum class EmptyEpisodes { FAILED, UPCOMING, NO_VIDEO_RELEASED, NO_VIDEO }

internal fun emptyEpisodesState(
    anime: Anime,
    schedule: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule,
    failed: Boolean,
): EmptyEpisodes = when {
    failed -> EmptyEpisodes.FAILED
    // Анонс — по расписанию (AniList/Shikimori) или по статусу каталога; отсутствие
    // серий у источника само по себе анонсом не считается.
    schedule.status == com.aniblaze.aggregator.source.EpisodeAirDates.Status.UPCOMING -> EmptyEpisodes.UPCOMING
    schedule.status == com.aniblaze.aggregator.source.EpisodeAirDates.Status.UNKNOWN && anime.airingStatus == 3 -> EmptyEpisodes.UPCOMING
    schedule.status == com.aniblaze.aggregator.source.EpisodeAirDates.Status.AIRING ||
        schedule.status == com.aniblaze.aggregator.source.EpisodeAirDates.Status.FINISHED ||
        anime.airingStatus in 1..2 -> EmptyEpisodes.NO_VIDEO_RELEASED
    else -> EmptyEpisodes.NO_VIDEO
}

/**
 * Плашки ожидания для анонса. Точность — ровно та, что у источника: точное время →
 * дата, время и обратный отсчёт; только дата → дата без таймера; только сезон и год →
 * «Зима 2027»; ничего → «пока не объявлена». Ничего не додумывается.
 */
internal fun upcomingTiles(
    schedule: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule,
    nowEpochSeconds: Long,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): List<EpisodeTile> {
    val tiles = mutableListOf(EpisodeTile("Статус", "Ещё не вышел", "анонс"))
    val exact = schedule.premiereAt.takeIf { it > 0 }
    val date = schedule.premiereOn
    when {
        exact != null -> {
            tiles += EpisodeTile("Премьера", formatDateTime(exact, today) ?: "—", "по местному времени", TileTone.ACCENT)
            val left = exact - nowEpochSeconds
            tiles += if (left > 0) {
                EpisodeTile("До премьеры", countdownLabel(left), "первая серия", TileTone.ACCENT)
            } else {
                EpisodeTile("До премьеры", "Время вышло", "проверяем источник")
            }
        }
        date != null -> tiles += EpisodeTile("Премьера", formatDate(date, today), "время не объявлено", TileTone.ACCENT)
        schedule.premiereSeason.isNotBlank() -> tiles += EpisodeTile("Премьера", schedule.premiereSeason, "точная дата не объявлена", TileTone.ACCENT)
        else -> tiles += EpisodeTile("Премьера", "Не объявлена", "дата выхода пока не объявлена", TileTone.MUTED)
    }
    return tiles
}

/** «12 д 04:33:10» / «04:33:10» — секунды до события, без округлений вверх. */
internal fun countdownLabel(secondsLeft: Long): String {
    val total = secondsLeft.coerceAtLeast(0)
    val days = total / 86_400
    val h = (total % 86_400) / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (days > 0) "%d д %02d:%02d:%02d".format(days, h, m, sec) else "%02d:%02d:%02d".format(h, m, sec)
}

/**
 * Страница анонсированного сезона: статус, премьера, отсчёт (только при точном
 * времени) и трейлер именно этого сезона. Те же плашки, что у сводки серий.
 */
@Composable
private fun UpcomingBlock(
    schedule: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule,
    onPremierePassed: suspend () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    val exact = schedule.premiereAt.takeIf { it > 0 }
    // Тик раз в секунду только пока есть что считать; дошли до нуля — один раз
    // перечитываем источник, серии сами собой «доступными» не объявляем.
    LaunchedEffect(exact) {
        if (exact == null) return@LaunchedEffect
        var refreshed = false
        while (true) {
            now = System.currentTimeMillis() / 1000
            if (now >= exact && !refreshed) {
                refreshed = true
                onPremierePassed()
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val tiles = remember(schedule, now) { upcomingTiles(schedule, now) }
    EpisodesSummaryCard(tiles, Modifier.padding(bottom = 12.dp))
    if (schedule.trailerYoutubeId.isNotBlank()) {
        TrailerCard(
            youtubeId = schedule.trailerYoutubeId,
            thumbnail = schedule.trailerThumbnail.ifBlank { "https://img.youtube.com/vi/${schedule.trailerYoutubeId}/hqdefault.jpg" },
            url = schedule.trailerUrl,
        )
    } else {
        Text("Трейлера у источника нет.", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
}

/**
 * Трейлер — превью с кнопкой, открывается в браузере. Встроить YouTube в libVLC
 * нельзя надёжно (его youtube-скрипт ломается регулярно), а пустой плеер хуже
 * честной ссылки.
 */
@Composable
private fun TrailerCard(youtubeId: String, thumbnail: String, url: String) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.padding(bottom = 16.dp)) {
        Text("Трейлер", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        Box(
            Modifier.width(360.dp).aspectRatio(16f / 9f).clip(Shapes.card).background(Surface2)
                .then(if (hovered) Modifier.border(BorderStroke(1.5.dp, AccentOrange), Shapes.card) else Modifier)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                        .onFailure { PlayerDiagnostics.failure("detail.trailer.open", it) }
                },
        ) {
            AsyncImage(
                model = thumbnail,
                contentDescription = "Трейлер $youtubeId",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.align(Alignment.Center).size(52.dp).clip(Shapes.pill).background(AccentOrange.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OledBlack, modifier = Modifier.size(30.dp))
            }
        }
        Text("Откроется на YouTube в браузере", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Ряд персонажей: круглый портрет и имя, главные первыми (они и в акценте). */
@Composable
private fun CharactersRow(characters: List<com.aniblaze.aggregator.model.CharacterCredit>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Персонажи", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(characters, key = { it.id }) { character ->
                Column(Modifier.width(scaledForFont(76.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(scaledForFont(64.dp)).clip(androidx.compose.foundation.shape.CircleShape).background(Surface2)
                            .then(if (character.main) Modifier.border(2.dp, AccentOrange.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (character.image.isNotBlank()) {
                            AsyncImage(
                                model = character.image,
                                contentDescription = character.name,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(character.name.take(1), color = TextSecondary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        character.name,
                        color = if (character.main) TextPrimary else TextSecondary,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        lineHeight = 13.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (character.seiyuu.isNotBlank()) {
                        Text(
                            character.seiyuu,
                            color = TextTertiary,
                            fontSize = 10.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Минимальная ширина плитки серии; колонок — сколько влезает. */
private val EPISODE_TILE_MIN_WIDTH = 240.dp

/** Оттенок плашки: ACCENT — ожидаемое событие (следующая серия, премьера), остальное нейтрально. */
internal enum class TileTone { NEUTRAL, ACCENT, MUTED }

/** Одна плашка сводки по сериям: подпись капителью, значение, пояснение. */
internal data class EpisodeTile(
    val label: String,
    val value: String,
    val caption: String = "",
    val tone: TileTone = TileTone.NEUTRAL,
) {
    val accent: Boolean get() = tone == TileTone.ACCENT
}

/**
 * Плашки под заголовком «Серии»: ВСЕГО (из расписания или каталога), ВЫШЛО (когда
 * меньше, чем всего), СЛЕДУЮЩАЯ / ПРЕМЬЕРА / ЗАВЕРШЁН — по статусу расписания.
 * Пусто — если сказать нечего (ни серий, ни расписания).
 */
internal fun episodeSummaryTiles(
    schedule: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule,
    available: Int,
    catalogTotal: Int,
    now: java.time.LocalDate = java.time.LocalDate.now(),
): List<EpisodeTile> {
    val total = schedule.totalEpisodes.takeIf { it > 0 } ?: catalogTotal.takeIf { it > 0 }
    val tiles = mutableListOf<EpisodeTile>()
    val status = schedule.status
    val finished = status == com.aniblaze.aggregator.source.EpisodeAirDates.Status.FINISHED
    when {
        total != null -> tiles += EpisodeTile("Всего", total.toString(), episodeWord(total))
        available > 0 -> tiles += EpisodeTile("Всего", available.toString(), episodeWord(available))
    }
    if (total != null && available in 1 until total && !finished) {
        tiles += EpisodeTile("Вышло", available.toString(), episodeWord(available))
    }
    when (status) {
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.AIRING -> {
            val next = schedule.nextEpisode.takeIf { it > 0 }
            val at = schedule.nextAiringAt.takeIf { it > 0 }?.let { formatDateTime(it, now) }
            tiles += if (next != null && at != null) {
                EpisodeTile("Следующая", "Серия $next", at, TileTone.ACCENT)
            } else {
                EpisodeTile("Статус", "Выходит", "дата следующей неизвестна")
            }
        }
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.FINISHED ->
            tiles += EpisodeTile("Статус", "Завершён", "все серии вышли")
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.UPCOMING -> {
            val exact = schedule.premiereAt.takeIf { it > 0 }?.let { formatDateTime(it, now) }
            val date = schedule.premiereOn?.let { formatDate(it, now) }
            tiles += when {
                exact != null -> EpisodeTile("Премьера", exact, "первая серия", TileTone.ACCENT)
                date != null -> EpisodeTile("Премьера", date, "время не объявлено", TileTone.ACCENT)
                schedule.premiereSeason.isNotBlank() -> EpisodeTile("Премьера", schedule.premiereSeason, "точная дата не объявлена", TileTone.ACCENT)
                else -> EpisodeTile("Премьера", "Ещё не вышло", "дата не объявлена", TileTone.ACCENT)
            }
        }
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.CANCELLED -> tiles += EpisodeTile("Статус", "Отменён", tone = TileTone.MUTED)
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.HIATUS -> tiles += EpisodeTile("Статус", "Пауза", "выход приостановлен", TileTone.MUTED)
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.UNKNOWN -> Unit
    }
    return tiles
}

@Composable
private fun EpisodesSummaryCard(tiles: List<EpisodeTile>, modifier: Modifier = Modifier) {
    if (tiles.isEmpty()) return
    // Та же поверхность, что у карточек серий ниже (Surface2, Shapes.chip): плашки
    // читаются как часть списка, а не как чужой виджет. Единственный цвет — акцент
    // приложения на значении, и только там, где есть что ждать (следующая серия,
    // премьера). Статус «завершён» — обычным текстом: это факт, а не событие.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { tile ->
            Column(
                Modifier.weight(1f)
                    .clip(Shapes.chip)
                    .background(Surface2)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(tile.label.uppercase(), color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    tile.value,
                    color = if (tile.tone == TileTone.ACCENT) AccentOrange else TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
                if (tile.caption.isNotBlank()) {
                    Text(tile.caption, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The line under the "Серии" header: when the next episode lands, or that the show
 * is over. Returns the text plus whether to highlight it (a pending episode is news;
 * a finished run is just context). Null = nothing worth saying.
 */
@Suppress("unused")
private fun scheduleLabel(
    schedule: com.aniblaze.aggregator.source.EpisodeAirDates.TitleSchedule,
    knownEpisodes: Int,
): Pair<String, Boolean>? {
    val total = schedule.totalEpisodes.takeIf { it > 0 }
    return when (schedule.status) {
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.AIRING -> {
            val next = schedule.nextEpisode.takeIf { it > 0 }
            val at = schedule.nextAiringAt.takeIf { it > 0 }?.let(::formatDateTime)
            if (next != null && at != null) {
                ("Серия $next выйдет $at" + (total?.let { " · всего $it" } ?: "")) to true
            } else {
                // Airing, but with no scheduled slot (between cours, or the catalog
                // has not been updated yet) — say only what is actually known.
                ("Выходит" + (total?.let { " · всего $it серий" } ?: "")) to false
            }
        }
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.FINISHED ->
            "Проект завершён · всего ${total ?: knownEpisodes} серий" to false
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.UPCOMING -> {
            val at = schedule.nextAiringAt.takeIf { it > 0 }?.let(::formatDateTime)
            (if (at != null) "Премьера $at" else "Ещё не вышло") to true
        }
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.CANCELLED -> "Проект отменён" to false
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.HIATUS -> "Выход приостановлен" to false
        com.aniblaze.aggregator.source.EpisodeAirDates.Status.UNKNOWN -> null
    }
}

@Composable
private fun SeasonChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(Shapes.chip)
            .background(if (active) AccentOrange else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (active) OledBlack else TextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EpisodeRow(
    title: String,
    /** "12 января 2025" / "вышла завтра" — null when no date is known. */
    released: String? = null,
    progress: Float = 0f,
    enabled: Boolean = true,
    watched: Boolean = false,
    onToggleWatched: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier.fillMaxWidth()
            .clip(Shapes.chip)
            .background(if (hovered) Surface3 else Surface2)
            .then(if (hovered) Modifier.border(BorderStroke(1.dp, AccentOrange.copy(alpha = 0.6f)), Shapes.chip) else Modifier)
            .hoverable(interaction)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = when {
                        !enabled -> TextSecondary.copy(alpha = 0.6f)
                        watched && !hovered -> TextSecondary.copy(alpha = 0.55f) // dimmed once seen
                        hovered -> TextPrimary
                        else -> TextSecondary
                    },
                )
                // Status line: the resume/watched state first (it is the actionable
                // bit), with the release date appended so both fit on one row.
                val status = listOfNotNull(
                    when {
                        progress > 0f -> "Продолжить · ${(progress * 100).toInt()}%"
                        watched -> "Просмотрено"
                        else -> null
                    },
                    released,
                )
                if (status.isNotEmpty()) {
                    Text(
                        status.joinToString("  ·  "),
                        color = if (progress > 0f) AccentOrange else TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // Watched tick — click to toggle by hand (a finished episode marks itself).
            if (onToggleWatched != null) {
                IconButton(onClick = onToggleWatched) {
                    Icon(
                        if (watched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = if (watched) "Отметить непросмотренным" else "Отметить просмотренным",
                        tint = if (watched) AccentOrange else TextSecondary.copy(alpha = 0.5f),
                    )
                }
            }
            Icon(Icons.Filled.PlayArrow, contentDescription = "Смотреть", tint = if (enabled) AccentOrange else TextSecondary.copy(alpha = 0.35f))
        }
        // Slim resume bar showing how far this episode was watched.
        if (progress > 0f) {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Surface1),
            ) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                        .clip(RoundedCornerShape(2.dp)).background(AccentOrange),
                )
            }
        }
    }
}

@Composable
private fun SeasonSelector(current: Anime, seasons: List<Anime>, state: com.aniblaze.desktop.PersistedState, onOpen: (Anime) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // The franchise comes back in release order; the current title keeps its place in
    // that order instead of being hoisted, so the menu reads as a timeline. It is only
    // appended when the franchise list somehow misses it.
    val options = remember(current, seasons, state) {
        seasonMenu(current, seasons, state)
    }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (options.size > 1) "Сезоны · ${options.size}" else "Сезон", maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        // Сезоны — в порядке выхода; фильмы, OVA и спешлы — отдельным блоком ниже,
        // чтобы «Сезон 3» не терялся между двумя спешлами. Номера в списке общие:
        // это порядок просмотра, и он не должен меняться от группировки.
        val (mains, extras) = remember(options) { options.partition { it.label.substringAfter(". ").startsWith("Сезон ") } }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (mains + extras).forEachIndexed { position, entry ->
                if (extras.isNotEmpty() && mains.isNotEmpty() && position == mains.size) {
                    androidx.compose.material3.HorizontalDivider(color = Surface2, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Фильмы, OVA и спешлы", color = TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                val s = entry.anime
                val isCurrent = s.id == current.id
                DropdownMenuItem(
                    text = {
                        Column {
                        Text(entry.label + if (entry.status.isNotBlank()) " · ${entry.status}" else "",
                            fontWeight = FontWeight.SemiBold, color = if (entry.status == "Сейчас смотрите") AccentOrange else TextPrimary)
                        Text(
                            if (s.year > 0) "${s.title} · ${s.year}" else s.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) AccentOrange else TextPrimary,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.widthIn(max = 420.dp),
                        )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!isCurrent) onOpen(s)
                    },
                )
            }
        }
    }
}

/**
 * Сколько страниц обсуждения берётся за один заход на странице тайтла.
 *
 * Это шаг подгрузки по кнопке, а не потолок: репозиторий идёт, пока источник приносит
 * новое, и сам сообщает, что обход закончен (см. DesktopRepository.commentFeed).
 * Шесть страниц — это полтораста записей, экран такой список показывает сразу и без
 * задержки, а кому мало, тот нажмёт «Ещё».
 */
private const val DETAIL_COMMENT_PAGES = 6
