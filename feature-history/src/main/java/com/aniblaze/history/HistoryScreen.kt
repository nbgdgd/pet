package com.aniblaze.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.ui.components.EmptyState
import com.aniblaze.ui.components.PosterCard
import com.aniblaze.ui.components.resolveGridColumns
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.TextPrimary

@Composable
fun HistoryScreen(
    onAnimeClick: (String) -> Unit,
    refreshTick: Int = 0,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val marks by viewModel.watchMarks.collectAsStateWithLifecycle()
    val colSetting by viewModel.gridColumns.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) gridState.animateScrollToItem(0)
    }

    if (history.isEmpty()) {
        EmptyState("Ещё ничего не просмотрено.")
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = resolveGridColumns(colSetting, maxWidth)
        val cell = com.aniblaze.ui.components.gridCellWidth(
            maxWidth,
            columns,
            horizontalPadding = 12.dp,
            spacing = 10.dp,
        )
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("История", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить", tint = AccentOrange)
                    }
                }
            }
            items(history, key = { it.id }) { anime ->
                PosterCard(
                    title = anime.title,
                    posterUrl = anime.poster,
                    rating = anime.rating,
                    ratingMax = anime.ratingMax,
                    cellWidth = cell,
                    onClick = { onAnimeClick(anime.id) },
                    dimmed = anime.id in marks.unfinished,
                    watched = anime.id in marks.completed,
                )
            }
        }
    }
}
