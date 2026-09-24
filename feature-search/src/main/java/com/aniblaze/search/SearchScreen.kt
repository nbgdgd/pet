package com.aniblaze.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.ui.components.EmptyState
import com.aniblaze.ui.components.LoadingState
import com.aniblaze.ui.components.tvFocusRing
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.Surface2
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

@Composable
fun SearchScreen(
    onAnimeClick: (String) -> Unit,
    refreshTick: Int = 0,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    // Re-tapping the Search tab re-runs the active query (browse refresh handled below).
    androidx.compose.runtime.LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && state.query.length >= 2) viewModel.onIntent(SearchIntent.Submit)
    }

    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onIntent(SearchIntent.QueryChanged(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp),
            placeholder = { Text("Поиск аниме…", color = TextTertiary) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AccentOrange) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
        )

        when {
            state.query.length < 2 -> Column(Modifier.fillMaxSize()) {
                SearchSuggestions(
                    history = history,
                    popular = viewModel.popular,
                    onPick = { viewModel.searchFor(it) },
                    onClear = { viewModel.clearHistory() },
                )
                Box(Modifier.weight(1f)) {
                    CatalogBrowse(onAnimeClick = onAnimeClick, refreshTick = refreshTick)
                }
            }
            state.isSearching -> LoadingState()
            state.error != null -> EmptyState(state.error!!)
            state.results.isEmpty() -> EmptyState("Ничего не найдено по «${state.query}»")
            // Только results, а не весь SearchState: query меняется на каждое нажатие
            // клавиши, и список результатов перекомпоновывался вместе с ним, хотя сами
            // результаты приходят лишь раз в 200 мс после debounce.
            else -> Column(Modifier.fillMaxSize()) {
                SearchHintLine(state.hint)
                ResultList(state.results) { id -> viewModel.recordSearch(state.query); onAnimeClick(id) }
            }
        }
    }
}

@Composable
private fun SearchSuggestions(
    history: List<String>,
    popular: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (history.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Недавние", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "Очистить",
                    color = AccentOrange,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onClear() }.padding(4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            ChipRow(history, accent = false, onPick = onPick)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "Популярное",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(popular, accent = true, onPick = onPick)
    }
}

@Composable
private fun ChipRow(labels: List<String>, accent: Boolean, onPick: (String) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels, key = { it }) { Chip(it, accent) { onPick(it) } }
    }
}

@Composable
private fun Chip(label: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (accent) AccentOrange.copy(alpha = 0.16f) else Surface2)
            .tvFocusRing(20.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (accent) AccentOrange else TextPrimary, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * «Искали „наруто“» над выдачей — одной строкой, без иконок и кнопок: на телефоне
 * место над результатами дорогое, а объяснить надо ровно один факт.
 */
@Composable
private fun SearchHintLine(hint: String) {
    if (hint.isBlank()) return
    Text(
        hint,
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
}

@Composable
private fun ResultList(results: List<Anime>, onAnimeClick: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(results, key = { it.id }) { anime ->
            SearchResultRow(anime, Modifier.widthIn(max = 720.dp).fillMaxWidth()) { onAnimeClick(anime.id) }
        }
    }
}

@Composable
private fun SearchResultRow(anime: Anime, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .tvFocusRing(14.dp)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = anime.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(62.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                anime.title,
                color = TextPrimary,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (anime.year > 0) {
                    Text("${anime.year}", color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                }
                if (anime.rating > 0.0) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(String.format("%.1f", anime.rating), color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

private val TextTertiary = com.aniblaze.ui.theme.TextTertiary
