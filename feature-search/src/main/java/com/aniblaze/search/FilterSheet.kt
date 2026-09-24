package com.aniblaze.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.aggregator.model.CatalogFilter
import com.aniblaze.aggregator.model.CatalogSort
import com.aniblaze.aggregator.model.CatalogTag
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.Surface2
import com.aniblaze.ui.theme.Surface3
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

/**
 * Фильтры каталога в мобильном исполнении.
 *
 * На ПК это боковая панель, которая всегда на виду. На телефоне так нельзя: ширины
 * нет, и прежние две выпадашки («Жанр», «Год») занимали место постоянно, а выбирать в
 * них приходилось из всплывающего меню — на сенсорном экране это худший из вариантов.
 *
 * Здесь вместо них:
 *  • узкая полоса над лентой — сортировка и кнопка «Фильтры» со счётчиком;
 *  • всё остальное — в шторке снизу, куда дотягивается большой палец;
 *  • выбранное показано чипами, каждый снимается одним нажатием;
 *  • редактируется КОПИЯ фильтра, лента перезагружается один раз по «Показать» —
 *    иначе каждый тап по жанру дёргал бы сеть, пока шторка ещё открыта.
 *
 * Все нажимаемые элементы не ниже [TAP_MIN] — минимум, ниже которого попадание пальцем
 * становится лотереей.
 */
private val TAP_MIN = 44.dp

@Composable
fun CatalogFilterBar(
    filter: CatalogFilter,
    sorts: List<CatalogSort>,
    onSort: (CatalogSort) -> Unit,
    onOpenFilters: () -> Unit,
    onFilterChange: (CatalogFilter) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Каталог",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            FilterButton(filter.activeCount, onOpenFilters)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorts, key = { it.name }) { s ->
                Chip(s.label, active = filter.sort == s) { onSort(s) }
            }
        }
        val chips = filter.chips()
        if (chips.isNotEmpty()) {
            LazyRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips.size, key = { chips[it].label }) { i ->
                    val chip = chips[i]
                    RemovableChip(chip.label) { onFilterChange(chip.remove()) }
                }
            }
        }
    }
}

@Composable
private fun FilterButton(activeCount: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .defaultMinSize(minHeight = TAP_MIN)
            .clip(RoundedCornerShape(12.dp))
            .background(if (activeCount > 0) AccentOrange else Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = "Фильтры",
            tint = if (activeCount > 0) OledBlack else TextPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (activeCount > 0) "Фильтры · $activeCount" else "Фильтры",
            color = if (activeCount > 0) OledBlack else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogFilterSheet(
    initial: CatalogFilter,
    viewModel: CatalogViewModel,
    onDismiss: () -> Unit,
    onApply: (CatalogFilter) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Черновик. Лента не трогается, пока не нажали «Показать».
    var draft by remember(initial) { mutableStateOf(initial) }
    var showMoreTags by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            SheetSection("Жанры")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = if (showMoreTags) viewModel.primaryTags + viewModel.moreTags else viewModel.primaryTags
                shown.forEach { tag ->
                    Chip(tag.label, active = tag.key in draft.tags) { draft = draft.toggleTag(tag.key) }
                }
                if (!showMoreTags && viewModel.moreTags.isNotEmpty()) {
                    Chip("Ещё · ${viewModel.moreTags.size}", active = false) { showMoreTags = true }
                }
            }

            SheetSection("Статус")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.statuses.forEach { st ->
                    Chip(st.label, active = draft.status == st) {
                        draft = draft.copy(status = if (draft.status == st) null else st)
                    }
                }
            }

            SheetSection("Тип")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.contentTypes.forEach { t ->
                    Chip(t.label, active = draft.contentType == t) {
                        draft = draft.copy(contentType = if (draft.contentType == t) null else t)
                    }
                }
            }

            SheetSection("Серий")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.episodeRanges.forEach { r ->
                    Chip(r.label, active = draft.episodes == r) {
                        draft = draft.copy(episodes = if (draft.episodes == r) null else r)
                    }
                }
            }

            SheetSection("Возраст")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.ageRatings.forEach { a ->
                    Chip(a.label, active = draft.ageRating == a) {
                        draft = draft.copy(ageRating = if (draft.ageRating == a) null else a)
                    }
                }
            }

            SheetSection("Оценка не ниже")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.ratingSteps.forEach { r ->
                    Chip("%.0f".format(r), active = draft.minRating == r) {
                        draft = draft.copy(minRating = if (draft.minRating == r) 0.0 else r)
                    }
                }
            }

            // Год — двумя горизонтальными лентами вместо выпадающих списков: пролистать
            // годы пальцем быстрее, чем целиться в пункт всплывающего меню.
            SheetSection("Год: с")
            YearRow(viewModel.years, draft.yearFrom) { draft = draft.copy(yearFrom = it) }
            SheetSection("Год: по")
            YearRow(viewModel.years, draft.yearTo) { draft = draft.copy(yearTo = it) }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetButton("Сбросить", filled = false, modifier = Modifier.weight(1f)) {
                    draft = draft.cleared()
                }
                SheetButton("Показать", filled = true, modifier = Modifier.weight(1f)) {
                    onApply(draft)
                }
            }
        }
    }
}

@Composable
private fun YearRow(years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "any") { Chip("Любой", active = selected == 0) { onSelect(0) } }
        items(years, key = { it }) { y ->
            Chip(y.toString(), active = selected == y) { onSelect(if (selected == y) 0 else y) }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title,
        color = TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) AccentOrange else Surface3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (filled) OledBlack else TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minHeight = TAP_MIN)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) AccentOrange else Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) OledBlack else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .defaultMinSize(minHeight = TAP_MIN)
            .clip(RoundedCornerShape(12.dp))
            .background(AccentOrange.copy(alpha = 0.18f))
            .clickable(onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.Close,
            contentDescription = "Убрать «$label»",
            tint = AccentOrange,
            modifier = Modifier.width(16.dp),
        )
    }
}

/** Экспортируется для тестов ранжирования: список первичных тегов не должен пустеть. */
internal val PRIMARY_TAGS: List<CatalogTag> = CatalogTag.entries.filter { it.primary }
