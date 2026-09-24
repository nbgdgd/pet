package com.aniblaze.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.ui.components.EmptyState
import com.aniblaze.ui.components.PosterCard
import com.aniblaze.ui.components.SectionHeader
import com.aniblaze.ui.components.gridCellWidth
import com.aniblaze.ui.components.resolveGridColumns
import com.aniblaze.ui.theme.AccentOrange
import java.time.LocalDate

@Composable
fun FavoritesScreen(
    onAnimeClick: (String) -> Unit,
    refreshTick: Int = 0,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val marks by viewModel.watchMarks.collectAsStateWithLifecycle()
    val colSetting by viewModel.gridColumns.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) gridState.animateScrollToItem(0)
    }

    if (favorites.isEmpty()) {
        EmptyState("Добавьте аниме в избранное, нажав на сердечко.")
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = resolveGridColumns(colSetting, maxWidth)
        val cell = gridCellWidth(maxWidth, columns, horizontalPadding = 12.dp, spacing = 10.dp)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Избранное")
            }
            items(favorites, key = { it.id }) { anime ->
                Column {
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
                    if (anime.broadcast in 1..7) {
                        Text(
                            text = nextEpisodeLabel(anime.broadcast),
                            color = AccentOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun nextEpisodeLabel(broadcast: Int): String {
    val today = LocalDate.now().dayOfWeek.value
    val days = ((broadcast - today) % 7 + 7) % 7
    return when (days) {
        0 -> "Серия сегодня"
        1 -> "Серия завтра"
        else -> "Серия через $days дн."
    }
}
