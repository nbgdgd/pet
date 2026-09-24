package com.aniblaze.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.Segment
import com.aniblaze.aggregator.repository.TitleWatchState
import com.aniblaze.ui.components.AccentGradient
import com.aniblaze.ui.components.LoadingState
import com.aniblaze.ui.components.PosterCard
import com.aniblaze.ui.components.SectionHeader
import com.aniblaze.ui.components.Skeleton
import com.aniblaze.ui.components.bouncePress
import com.aniblaze.ui.components.glass
import com.aniblaze.ui.components.rememberTvInitialFocus
import com.aniblaze.ui.components.staggerIn
import com.aniblaze.ui.components.tvFocusRing
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onPlaySegment: (contentId: String, segmentId: String, streamMode: String) -> Unit,
    onAnimeClick: (String) -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    if (state.isLoading) {
        DetailSkeleton()
        return
    }
    val anime = state.anime ?: return

    val contentKind = remember(anime.id, state.segments) {
        detailContentKind(anime.id, state.segments)
    }
    var pendingCinemaPlay by remember(anime.id) { mutableStateOf<Pair<String, String>?>(null) }
    val requestPlay: (String, String) -> Unit = { contentId, segmentId ->
        if (contentKind == DetailContentKind.OTHER) {
            onPlaySegment(contentId, segmentId, "auto")
        } else {
            pendingCinemaPlay = contentId to segmentId
        }
    }
    val cinemaSeasonGroups = remember(contentKind, state.segments) {
        if (contentKind == DetailContentKind.CINEMA_SERIES) groupCinemaSeasons(state.segments)
        else emptyMap()
    }
    val cinemaSeasons = remember(cinemaSeasonGroups) { cinemaSeasonGroups.keys.sorted() }
    var selectedCinemaSeason by rememberSaveable(anime.id) {
        mutableIntStateOf(cinemaSeasons.lastOrNull() ?: 1)
    }
    LaunchedEffect(cinemaSeasons) {
        if (cinemaSeasons.isNotEmpty() && selectedCinemaSeason !in cinemaSeasons) {
            selectedCinemaSeason = cinemaSeasons.last()
        }
    }
    val shownSegments = when (contentKind) {
        DetailContentKind.CINEMA_MOVIE -> emptyList()
        DetailContentKind.CINEMA_SERIES -> cinemaSeasonGroups[selectedCinemaSeason].orEmpty()
        DetailContentKind.OTHER -> state.segments
    }

    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidthDp = config.screenWidthDp
    // Smart TV: стартовать пульт с кнопки воспроизведения. Тачскрины не затронуты.
    val tvFocus = rememberTvInitialFocus(!state.isLoading)

    if (landscape) {
        // Wider screens get a bigger info panel; very narrow landscape (< 600dp) keeps 40/60
        val infoWeight = when {
            screenWidthDp >= 900 -> 0.50f
            screenWidthDp >= 700 -> 0.46f
            else -> 0.40f
        }
        val headerH = when {
            screenWidthDp >= 900 -> 260.dp
            screenWidthDp >= 700 -> 220.dp
            else -> 180.dp
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(infoWeight)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                DetailHeader(anime.poster, anime.title, headerH, onBack)
                DetailInfo(
                    state = state,
                    anime = anime,
                    haptic = haptic,
                    viewModel = viewModel,
                    onPlaySegment = requestPlay,
                    contentKind = contentKind,
                    cinemaSeasons = cinemaSeasons,
                    selectedCinemaSeason = selectedCinemaSeason,
                    shownSegments = shownSegments,
                    onCinemaSeason = { selectedCinemaSeason = it },
                    tvFocus = tvFocus,
                )
            }
            LazyColumn(Modifier.weight(1f - infoWeight).fillMaxHeight()) {
                itemsIndexed(shownSegments, key = { _, s -> s.id }) { idx, segment ->
                    SegmentRow(
                        segment,
                        idx,
                        watched = segment.id in state.watched,
                        cinemaSeries = contentKind == DetailContentKind.CINEMA_SERIES,
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        requestPlay(anime.id, segment.id)
                    }
                }
                item { Box(Modifier.height(40.dp)) }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            item { DetailHeader(anime.poster, anime.title, 320.dp, onBack) }
            item {
                DetailInfo(
                    state = state,
                    anime = anime,
                    haptic = haptic,
                    viewModel = viewModel,
                    onPlaySegment = requestPlay,
                    contentKind = contentKind,
                    cinemaSeasons = cinemaSeasons,
                    selectedCinemaSeason = selectedCinemaSeason,
                    shownSegments = shownSegments,
                    onCinemaSeason = { selectedCinemaSeason = it },
                    tvFocus = tvFocus,
                )
            }
            itemsIndexed(shownSegments, key = { _, s -> s.id }) { idx, segment ->
                SegmentRow(
                    segment,
                    idx,
                    watched = segment.id in state.watched,
                    cinemaSeries = contentKind == DetailContentKind.CINEMA_SERIES,
                    // Air date: cinema segments carry TMDB's own date; anime dates
                    // come from the AniList lookup keyed by episode number.
                    released = segment.releaseDate.ifBlank { null }?.let(::formatIsoDate)
                        ?: state.schedule.dates[segment.number]?.let(::formatEpochDate),
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    requestPlay(anime.id, segment.id)
                }
            }
            if (state.similar.isNotEmpty()) {
                item { SimilarRow(state.similar, onAnimeClick) }
            }
            if (state.comments.isNotEmpty() || state.commentsBusy || state.commentsEnded) {
                item(key = "commentsHeader") { SectionHeader("Комментарии") }
                items(state.comments, key = { "c${it.id}" }) { comment ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        CommentRow(
                            comment,
                            repliesOpen = state.openReplies.containsKey(comment.id),
                            onToggleReplies = if (comment.replyCount > 0) {
                                { viewModel.onIntent(DetailIntent.ToggleReplies(comment.id)) }
                            } else null,
                        )
                        state.openReplies[comment.id]?.forEach { reply ->
                            Box(Modifier.padding(start = 36.dp)) { CommentRow(reply) }
                        }
                    }
                }
                if (state.comments.isEmpty() && state.commentsEnded && !state.commentsBusy) {
                    item(key = "commentsEmpty") {
                        Text(
                            "Комментариев пока нет",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (!state.commentsEnded) {
                    item(key = "commentsMore") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (state.commentsBusy) "Загрузка…" else "Ещё комментарии",
                                color = AccentOrange,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable(enabled = !state.commentsBusy) {
                                        viewModel.onIntent(DetailIntent.LoadMoreComments)
                                    }
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
            item { Box(Modifier.height(80.dp)) }
        }
    }

    pendingCinemaPlay?.let { (contentId, segmentId) ->
        ModalBottomSheet(
            onDismissRequest = { pendingCinemaPlay = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xF217171C),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Как загрузить?", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Источник будет виден в плеере и его можно будет отличить сразу.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                PlaybackChoiceRow(
                    title = "Через парсер",
                    subtitle = "Обычный поток Lampa — без P2P",
                ) {
                    pendingCinemaPlay = null
                    onPlaySegment(contentId, segmentId, "parser")
                }
                PlaybackChoiceRow(
                    title = "Через торрент",
                    subtitle = "Torrentio / другой указанный Stremio-аддон",
                ) {
                    pendingCinemaPlay = null
                    onPlaySegment(contentId, segmentId, "torrent")
                }
            }
        }
    }
}

@Composable
private fun PlaybackChoiceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB322222A))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Один комментарий: аватар, ник, дата, рейтинг, текст; спойлер открывается по тапу. */
@Composable
private fun CommentRow(
    comment: com.aniblaze.aggregator.model.TitleComment,
    repliesOpen: Boolean = false,
    onToggleReplies: (() -> Unit)? = null,
) {
    var spoilerOpen by remember(comment.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF23232B)),
        ) {
            if (comment.avatar.isNotBlank()) {
                AsyncImage(
                    model = comment.avatar,
                    contentDescription = comment.author,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                comment.timestamp.takeIf { it > 0 }?.let { ts ->
                    formatEpochDate(ts)?.let { Text("  ·  $it", color = TextSecondary, fontSize = 11.sp) }
                }
                if (comment.votes != 0) {
                    Text(
                        "  ·  ${if (comment.votes > 0) "▲" else "▼"} ${kotlin.math.abs(comment.votes)}",
                        color = if (comment.votes > 0) AccentOrange else TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
            if (comment.isSpoiler && !spoilerOpen) {
                Text(
                    "Спойлер — показать",
                    color = AccentOrange,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { spoilerOpen = true }
                        .padding(top = 2.dp),
                )
            } else {
                Text(comment.message, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (onToggleReplies != null) {
                Text(
                    if (repliesOpen) "Скрыть ответы" else "Ответы (${comment.replyCount})",
                    color = AccentOrange,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onToggleReplies() }
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SimilarRow(titles: List<Anime>, onAnimeClick: (String) -> Unit) {
    SectionHeader("Похожие")
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(titles, key = { it.id }, contentType = { "poster" }) { anime ->
            Box(Modifier.width(120.dp).padding(end = 12.dp)) {
                PosterCard(
                    anime.title,
                    anime.poster,
                    anime.rating,
                    ratingMax = anime.ratingMax,
                    cellWidth = 120.dp,
                    onClick = { onAnimeClick(anime.id) },
                )
            }
        }
    }
}

@Composable
private fun DetailSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Skeleton(Modifier.fillMaxWidth().height(300.dp), cornerRadius = 0.dp)
        Column(Modifier.padding(16.dp)) {
            Skeleton(Modifier.fillMaxWidth(0.7f).height(26.dp))
            Spacer(Modifier.height(12.dp))
            repeat(3) {
                Skeleton(Modifier.fillMaxWidth().height(13.dp))
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(20.dp))
            repeat(5) {
                Skeleton(Modifier.fillMaxWidth().height(54.dp), cornerRadius = 14.dp)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DetailHeader(poster: String, title: String, height: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height)) {
        AsyncImage(
            model = poster,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.3f to Color.Transparent,
                        0.8f to OledBlack,
                        1f to OledBlack,
                    ),
                ),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(12.dp).glass(CircleRadius).bouncePress(),
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
    }
}

@Composable
private fun DetailInfo(
    state: DetailState,
    anime: Anime,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    viewModel: DetailViewModel,
    onPlaySegment: (String, String) -> Unit,
    contentKind: DetailContentKind,
    cinemaSeasons: List<Int>,
    selectedCinemaSeason: Int,
    shownSegments: List<Segment>,
    onCinemaSeason: (Int) -> Unit,
    tvFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                anime.title,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.onIntent(DetailIntent.ToggleFavorite)
            }) {
                Icon(
                    if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = AccentOrange,
                )
            }
        }
        // Полный текст с релиза, когда догрузился; карточный обрезок — сразу.
        val description = state.fullDescription ?: anime.description
        if (description.isNotBlank()) {
            Text(
                description,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    if (state.segmentsLoading) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = AccentOrange,
                strokeWidth = 2.dp,
            )
            Text(
                if (contentKind == DetailContentKind.CINEMA_MOVIE) "Загрузка видео…" else "Загрузка серий…",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        return
    }
    state.resume?.let { rp ->
        val resumeSegment = state.segments.firstOrNull { it.id == rp.segmentId }
        val num = when (contentKind) {
            DetailContentKind.CINEMA_MOVIE -> null
            DetailContentKind.CINEMA_SERIES -> resumeSegment?.let(::cinemaEpisodeNumber)
            DetailContentKind.OTHER -> resumeSegment?.number
        }
        val season = if (contentKind == DetailContentKind.CINEMA_SERIES) {
            resumeSegment?.let(::cinemaSeasonNumber)
        } else null
        ContinueButton(num, season, rp.positionMs, tvFocus) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onPlaySegment(anime.id, rp.segmentId)
        }
    }
    when (contentKind) {
        DetailContentKind.CINEMA_MOVIE -> {
            if (state.resume == null) {
                state.segments.firstOrNull()?.let { movie ->
                    MoviePlayButton(tvFocus) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlaySegment(anime.id, movie.id)
                    }
                }
            }
        }
        DetailContentKind.CINEMA_SERIES -> CinemaSeriesSelectorRow(
            seasons = cinemaSeasons,
            selectedSeason = selectedCinemaSeason,
            segments = shownSegments,
            onSeason = onCinemaSeason,
            onEpisode = { onPlaySegment(anime.id, it.id) },
        )
        DetailContentKind.OTHER -> SelectorRow(
            current = anime,
            seasons = state.seasons,
            segments = state.segments,
            progress = state.seasonProgress,
            onSeason = { viewModel.onIntent(DetailIntent.SelectSeason(it)) },
            onEpisode = { onPlaySegment(anime.id, it.id) },
        )
    }
    if (contentKind != DetailContentKind.CINEMA_MOVIE) {
        Text(
            "Серии",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
        )
        // Выходит ли ещё / когда следующая серия / завершён.
        scheduleLabel(state.schedule, state.segments.size)?.let { (text, highlight) ->
            Text(
                text,
                color = if (highlight) AccentOrange else TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
    if (state.segments.isEmpty()) {
        val ctx = LocalContext.current
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AccentGradient)
                .clickable {
                    val q = android.net.Uri.encode("${anime.title} трейлер")
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.youtube.com/results?search_query=$q"),
                            ),
                        )
                    }
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OledBlack)
            Text(
                "Трейлер на YouTube",
                color = OledBlack,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            state.error ?: if (contentKind == DetailContentKind.CINEMA_MOVIE) {
                "Видео пока недоступно для этого фильма."
            } else {
                "Серии пока недоступны — это анонс."
            },
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SegmentRow(
    segment: Segment,
    index: Int,
    watched: Boolean,
    cinemaSeries: Boolean,
    /** "10 янв 2025" / "завтра" — null when the air date is unknown. */
    released: String? = null,
    onClick: () -> Unit,
) {
    val shownNumber = if (cinemaSeries) cinemaEpisodeNumber(segment) else segment.number
    val shownTitle = if (cinemaSeries) cinemaEpisodeTitle(segment) else segment.title
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(250 + index * 50)) + slideInVertically(tween(300 + index * 50)) { it / 2 },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .glass(14.dp)
                .tvFocusRing(14.dp)
                .bouncePress()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Серия $shownNumber",
                        color = if (watched) TextSecondary else TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    if (watched) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Просмотрено",
                            tint = AccentOrange,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        )
                    }
                }
                if (shownTitle.isNotBlank() && shownTitle != "Серия $shownNumber" &&
                    shownTitle != "Episode $shownNumber"
                ) {
                    Text(shownTitle, color = TextSecondary, fontSize = 12.sp)
                }
                released?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = OledBlack)
            }
        }
    }
}

@Composable
private fun ContinueButton(
    episodeNumber: Int?,
    seasonNumber: Int?,
    positionMs: Long,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AccentGradient)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusRing(16.dp)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OledBlack)
        Column(Modifier.padding(start = 10.dp)) {
            Text("Продолжить просмотр", color = OledBlack, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val sub = buildString {
                if (seasonNumber != null) append("Сезон $seasonNumber · ")
                if (episodeNumber != null) append("Серия $episodeNumber · ")
                append(fmtTime(positionMs))
            }
            Text(sub, color = OledBlack.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MoviePlayButton(
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AccentGradient)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusRing(16.dp)
            .bouncePress()
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OledBlack)
        Text(
            "Смотреть фильм",
            color = OledBlack,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CinemaSeriesSelectorRow(
    seasons: List<Int>,
    selectedSeason: Int,
    segments: List<Segment>,
    onSeason: (Int) -> Unit,
    onEpisode: (Segment) -> Unit,
) {
    if (seasons.isEmpty() && segments.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (seasons.isNotEmpty()) {
            DropdownButton(
                title = "Сезон",
                value = "Сезон $selectedSeason",
                options = seasons,
                optionText = { "Сезон $it" },
                onSelect = onSeason,
                modifier = Modifier.weight(1f),
            )
        }
        if (segments.isNotEmpty()) {
            DropdownButton(
                title = "Серия",
                value = episodeCountLabel(segments.size),
                options = segments,
                optionText = { "Серия ${cinemaEpisodeNumber(it)}" },
                onSelect = onEpisode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SelectorRow(
    current: Anime,
    seasons: List<Anime>,
    segments: List<Segment>,
    progress: Map<String, TitleWatchState>,
    onSeason: (String) -> Unit,
    onEpisode: (Segment) -> Unit,
) {
    if (seasons.isEmpty() && segments.isEmpty()) return
    val options = remember(current, seasons, progress, segments.size) {
        seasonListOptions(current, seasons, progress, segments.size)
    }
    var seasonSheet by rememberSaveable(current.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (seasons.isNotEmpty()) {
            // Выпадающее меню на телефоне неудобно: у крупных франшиз (у «Клинка,
            // рассекающего демонов» — 12 записей) названия длинные, а меню
            // прижимается к кнопке и режется. Открываем нижний лист.
            SheetButton(
                title = if (options.size > 1) "Сезоны · ${options.size}" else "Сезон",
                value = options.firstOrNull { it.anime.id == current.id }?.let { "${it.order}. ${it.anime.title}" }
                    ?: current.title,
                onClick = { seasonSheet = true },
                modifier = Modifier.weight(1f),
            )
        }
        if (segments.isNotEmpty()) {
            DropdownButton(
                title = "Серия",
                value = episodeCountLabel(segments.size),
                options = segments,
                optionText = { "Серия ${it.number}" },
                onSelect = onEpisode,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (seasonSheet) {
        SeasonSheet(
            options = options,
            currentId = current.id,
            onDismiss = { seasonSheet = false },
            onPick = { picked ->
                seasonSheet = false
                onSeason(picked.anime.id)
            },
        )
    }
}

/**
 * Порядок пунктов в списке сезонов — как в оригинале на ПК: франшиза приходит
 * в порядке выхода, и текущий тайтл ОСТАЁТСЯ на своём месте в этой цепочке,
 * а не всплывает наверх — тогда список читается как таймлайн. В конец текущий
 * дописывается только если франшиза его почему-то не содержит (например, id
 * пришёл от другого источника и с анихартовскими не совпал).
 */
internal fun seasonOptions(current: Anime, seasons: List<Anime>): List<Anime> =
    (if (seasons.any { it.id == current.id }) seasons else seasons + current).distinctBy { it.id }

internal enum class SeasonProgressLabel(val text: String) {
    WATCHING("Сейчас смотрите"),
    WATCHED("Просмотрено"),
    NEXT("Следующий"),
}

internal data class SeasonListOption(
    val anime: Anime,
    val order: Int,
    val label: SeasonProgressLabel?,
)

/** Метки выводятся из сохранённого прогресса, а не из выбранного сейчас пункта. */
internal fun seasonListOptions(
    current: Anime,
    seasons: List<Anime>,
    progress: Map<String, TitleWatchState>,
    currentSegmentCount: Int,
): List<SeasonListOption> {
    val ordered = seasonOptions(current, seasons)
    fun totalFor(anime: Anime): Int =
        if (anime.id == current.id && currentSegmentCount > 0) currentSegmentCount else anime.episodesTotal
    fun completed(anime: Anime): Boolean {
        val total = totalFor(anime)
        return total > 0 && (progress[anime.id]?.completedEpisodes ?: 0) >= total
    }
    val partial = ordered.filter { anime ->
        val state = progress[anime.id] ?: return@filter false
        !completed(anime) && (state.completedEpisodes > 0 || state.hasInProgress)
    }
    val watchingId = partial.maxByOrNull { progress[it.id]?.lastUpdatedAt ?: 0L }?.id
    val completedIds = ordered.filter(::completed).mapTo(mutableSetOf()) { it.id }
    val after = watchingId?.let { id -> ordered.indexOfFirst { it.id == id } + 1 }
        ?: ordered.indexOfFirst { it.id !in completedIds }.let { if (it < 0) ordered.size else it }
    val nextId = ordered.drop(after).firstOrNull {
        it.id !in completedIds && it.id != watchingId
    }?.id
        ?: if (watchingId == null && completedIds.isEmpty()) ordered.firstOrNull()?.id else null
    return ordered.mapIndexed { index, anime ->
        val label = when (anime.id) {
            watchingId -> SeasonProgressLabel.WATCHING
            in completedIds -> SeasonProgressLabel.WATCHED
            nextId -> SeasonProgressLabel.NEXT
            else -> null
        }
        SeasonListOption(anime, index + 1, label)
    }
}

/**
 * Нижний лист со всеми сезонами франшизы: название, год и подсветка текущего.
 *
 * Список полный, а не трёхпунктовая затравка из `related_releases`: источник
 * дотягивает франшизу постранично из `/related/{franchiseId}` (см. AnixartSource),
 * поэтому пунктов бывает 12, а не 4 — под них и нужен лист, а не меню.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonSheet(
    options: List<SeasonListOption>,
    currentId: String,
    onDismiss: () -> Unit,
    onPick: (SeasonListOption) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    // Текущий сезон у длинной франшизы легко оказывается десятым из двенадцати —
    // открываем лист сразу на нём, иначе подсветку не видно без прокрутки.
    LaunchedEffect(options, currentId) {
        val idx = options.indexOfFirst { it.anime.id == currentId }
        if (idx > 0) listState.scrollToItem(idx)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OledBlack,
    ) {
        Text(
            "Сезоны франшизы",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        ) {
            items(options, key = { it.anime.id }) { option ->
                val season = option.anime
                val isCurrent = season.id == currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCurrent) { onPick(option) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${option.order}. ${season.title}",
                            color = if (isCurrent) AccentOrange else TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (season.year > 0) {
                            Text(
                                season.year.toString(),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        option.label?.let { label ->
                            val color = when (label) {
                                SeasonProgressLabel.WATCHING -> AccentOrange
                                SeasonProgressLabel.WATCHED -> Color(0xFF45C89A)
                                SeasonProgressLabel.NEXT -> Color(0xFF70A9E8)
                            }
                            Text(
                                label.text,
                                color = color,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (isCurrent) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Текущий сезон",
                            tint = AccentOrange,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            // Место под жест «домой», иначе последний пункт лежит прямо на нём.
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Кнопка вида DropdownButton, но открывающая нижний лист, а не меню. */
@Composable
private fun SheetButton(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .glass(12.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextSecondary, fontSize = 11.sp)
            Text(
                value,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextSecondary)
    }
}

@Composable
private fun <T> DropdownButton(
    title: String,
    value: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .glass(12.dp)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextSecondary, fontSize = 11.sp)
                Text(
                    value,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(optionText(opt)) },
                    onClick = {
                        expanded = false
                        onSelect(opt)
                    },
                )
            }
        }
    }
}

private val CircleRadius = 24.dp

internal enum class DetailContentKind { CINEMA_MOVIE, CINEMA_SERIES, OTHER }

private val cinemaSeasonRegex = Regex("""^[СсSs]\s*(\d+)""")
private val cinemaEpisodeRegex = Regex("""(?:Серия|Эпизод|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE)

internal fun detailContentKind(contentId: String, segments: List<Segment>): DetailContentKind {
    val baseId = contentId.substringBeforeLast(":t")
    return when {
        baseId.startsWith("tmdbtv:") -> DetailContentKind.CINEMA_SERIES
        baseId.startsWith("tmdb:") -> DetailContentKind.CINEMA_MOVIE
        "rezka." in baseId.lowercase() && segments.any {
            cinemaSeasonNumber(it) != null || cinemaEpisodeRegex.containsMatchIn(it.title)
        } -> DetailContentKind.CINEMA_SERIES
        "rezka." in baseId.lowercase() && segments.singleOrNull()?.title.equals("Смотреть", ignoreCase = true) ->
            DetailContentKind.CINEMA_MOVIE
        else -> DetailContentKind.OTHER
    }
}

internal fun cinemaSeasonNumber(segment: Segment): Int? =
    cinemaSeasonRegex.find(segment.title.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

// ---- Даты выхода серий ----

private val RU_MONTHS = listOf(
    "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
)

/** "10 янв", "12 мар 2024"; для невышедших — "завтра" / "через N дн.". */
private fun formatDate(date: java.time.LocalDate): String {
    val today = java.time.LocalDate.now()
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

/** AniList timestamps are epoch seconds, shown in the local zone. */
internal fun formatEpochDate(epochSeconds: Long): String? = runCatching {
    formatDate(
        java.time.Instant.ofEpochSecond(epochSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate(),
    )
}.getOrNull()

/** TMDB hands cinema episodes a plain "2025-01-10". */
internal fun formatIsoDate(iso: String): String? =
    runCatching { formatDate(java.time.LocalDate.parse(iso)) }.getOrNull()

/** "8 авг в 17:30" — for the next-episode line, where the hour matters. */
private fun formatDateTime(epochSeconds: Long): String? = runCatching {
    val zoned = java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault())
    "${formatDate(zoned.toLocalDate())} в %02d:%02d".format(zoned.hour, zoned.minute)
}.getOrNull()

/**
 * The line under the "Серии" header: when the next episode lands, or that the show
 * is over. Returns the text plus whether to highlight it (a pending episode is news;
 * a finished run is just context). Null = nothing worth saying.
 */
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
                // Airing, but with no scheduled slot (between cours, or AniList has
                // not been updated yet) — say only what is actually known.
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

internal fun cinemaEpisodeNumber(segment: Segment): Int =
    cinemaEpisodeRegex.find(segment.title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: segment.number

internal fun cinemaEpisodeTitle(segment: Segment): String {
    val match = cinemaEpisodeRegex.find(segment.title) ?: return segment.title
    return segment.title
        .substring(match.range.last + 1)
        .trim()
        .trimStart('·', '•', '-', '—')
        .trim()
}

internal fun groupCinemaSeasons(segments: List<Segment>): Map<Int, List<Segment>> =
    segments.groupBy { cinemaSeasonNumber(it) ?: 1 }

internal fun episodeCountLabel(count: Int): String {
    val mod100 = count % 100
    val suffix = when {
        mod100 in 11..14 -> "серий"
        count % 10 == 1 -> "серия"
        count % 10 in 2..4 -> "серии"
        else -> "серий"
    }
    return "$count $suffix"
}
