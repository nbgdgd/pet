package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.model.PersonCredit
import com.aniblaze.aggregator.model.StudioCredit
import com.aniblaze.desktop.DesktopRepository
import kotlinx.coroutines.CancellationException

private enum class CreditSort(val label: String) {
    RATING("По рейтингу"), YEAR("По году"), TITLE("По названию"),
}

private fun sortedCredits(items: List<Anime>, sort: CreditSort): List<Anime> = when (sort) {
    CreditSort.RATING -> items.sortedWith(compareByDescending<Anime> { it.rating }.thenByDescending { it.year })
    CreditSort.YEAR -> items.sortedWith(compareByDescending<Anime> { it.year }.thenByDescending { it.rating })
    CreditSort.TITLE -> items.sortedBy { it.title.lowercase() }
}

@Composable
fun StudioScreen(
    studio: StudioCredit,
    repository: DesktopRepository,
    onOpenTitle: (Anime) -> Unit,
) {
    var items by remember(studio) { mutableStateOf<List<Anime>>(emptyList()) }
    var page by remember(studio) { mutableStateOf(1) }
    var loading by remember(studio) { mutableStateOf(false) }
    var endReached by remember(studio) { mutableStateOf(false) }
    var failed by remember(studio) { mutableStateOf(false) }
    var retryKey by remember(studio) { mutableStateOf(0) }
    var sort by remember(studio) { mutableStateOf(CreditSort.RATING) }

    LaunchedEffect(studio, page, retryKey) {
        if (endReached) return@LaunchedEffect
        loading = true
        failed = false
        try {
            val result = repository.studioTitles(studio, page)
            items = (items + result.titles).distinctBy { it.id }
            endReached = !result.hasMore
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    CreditCatalog(
        title = studio.name,
        subtitle = "Студия · ${items.size} ${creditTitleWord(items.size)} в AniBlaze",
        items = sortedCredits(items, sort),
        sort = sort,
        onSort = { sort = it },
        loading = loading,
        failed = failed,
        onRetry = { retryKey++ },
        onLoadMore = if (!loading && !endReached && !failed) ({ page++ }) else null,
        onOpenTitle = onOpenTitle,
    )
}

@Composable
fun PersonScreen(
    person: PersonCredit,
    repository: DesktopRepository,
    onOpenTitle: (Anime) -> Unit,
) {
    var details by remember(person.id) { mutableStateOf<com.aniblaze.aggregator.model.PersonDetails?>(null) }
    var loaded by remember(person.id) { mutableStateOf(false) }
    var failed by remember(person.id) { mutableStateOf(false) }
    var retryKey by remember(person.id) { mutableStateOf(0) }
    var sort by remember(person.id) { mutableStateOf(CreditSort.RATING) }
    LaunchedEffect(person.id, retryKey) {
        loaded = false
        failed = false
        try {
            details = repository.personDetails(person)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loaded = true
        }
    }
    val resolved = details?.person ?: person
    val works = details?.works.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (resolved.photo.isNotBlank()) {
                AsyncImage(
                    model = resolved.photo,
                    contentDescription = resolved.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(Surface2),
                )
            }
            Column {
                Text(resolved.name, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Режиссёр · ${works.size} ${creditTitleWord(works.size)} в AniBlaze", color = TextSecondary)
                CreditSortRow(sort, onSort = { sort = it })
            }
        }
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentOrange)
            }
            failed -> CreditStatus(
                message = "Не удалось загрузить работы режиссёра.",
                action = "Повторить",
                onAction = { retryKey++ },
            )
            works.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Режиссёрские работы, доступные в AniBlaze, не найдены.", color = TextSecondary)
            }
            else -> {
                val ordered = sortedCredits(works.map { it.anime }, sort)
                val roleById = works.associate { it.anime.id to buildString {
                    append(it.role)
                    if (it.anime.year > 0) append(" · ${it.anime.year}")
                    if (it.anime.status.isNotBlank()) append(" · ${it.anime.status}")
                } }
                PosterGrid(ordered, isLoading = false, subtitles = roleById, onClick = onOpenTitle)
            }
        }
    }
}

@Composable
private fun CreditCatalog(
    title: String,
    subtitle: String,
    items: List<Anime>,
    sort: CreditSort,
    onSort: (CreditSort) -> Unit,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onLoadMore: (() -> Unit)?,
    onOpenTitle: (Anime) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 6.dp)) {
            Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary)
            CreditSortRow(sort, onSort)
        }
        when {
            loading && items.isEmpty() -> Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentOrange)
            }
            failed -> CreditStatus("Не удалось загрузить тайтлы студии.", "Повторить", onRetry)
            items.isEmpty() && onLoadMore != null -> CreditStatus(
                "На этой странице нет тайтлов из включённых источников.",
                "Проверить следующую",
                onLoadMore,
            )
            items.isEmpty() -> Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Тайтлы студии, доступные в AniBlaze, не найдены.", color = TextSecondary)
            }
            else -> PosterGrid(
                items = items,
                isLoading = loading,
                modifier = Modifier.weight(1f),
                onLoadMore = onLoadMore,
                subtitles = items.associate { anime ->
                    anime.id to listOfNotNull(
                        anime.year.takeIf { it > 0 }?.toString(),
                        anime.status.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
                },
                onClick = onOpenTitle,
            )
        }
    }
}

@Composable
private fun CreditStatus(message: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = TextSecondary)
        Text(
            action,
            color = AccentOrange,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp).clip(Shapes.chip).clickable(onClick = onAction)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun CreditSortRow(selected: CreditSort, onSort: (CreditSort) -> Unit) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CreditSort.entries.forEach { option ->
            Text(
                option.label,
                color = if (option == selected) AccentOrange else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(Shapes.chip).background(Surface2).clickable { onSort(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun creditTitleWord(count: Int): String {
    val mod100 = count % 100
    return when {
        mod100 in 11..14 -> "тайтлов"
        count % 10 == 1 -> "тайтл"
        count % 10 in 2..4 -> "тайтла"
        else -> "тайтлов"
    }
}
