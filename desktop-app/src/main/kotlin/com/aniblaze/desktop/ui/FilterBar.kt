package com.aniblaze.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.aniblaze.aggregator.model.FilterFacets
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import com.aniblaze.desktop.FILTER_PRESETS_MAX
import com.aniblaze.desktop.PRESET_NAME_MAX
import com.aniblaze.desktop.PersistedFilter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Полоса фильтров над лентой: кнопка «Фильтры», сортировка, выбранное тегами и
 * счётчик найденного.
 *
 * Панель с самими фасетами НЕ раскрыта по умолчанию и не занимает места, пока её не
 * позвали, — иначе над каждым разделом висела бы простыня из семи блоков. Всё, что
 * выбрано, при этом видно всегда: строка тегов остаётся на экране и снимается по
 * одному нажатию.
 *
 * Узкое окно (телефонная ширина) панель не раскрывает под полосой, а выдвигает
 * отдельным слоем поверх ленты — [FilterSheet]. Пять блоков фасетов в колонку шириной
 * с телефон занимают экран целиком, и лента под ними всё равно не видна.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBar(
    facets: FilterFacets,
    filter: CatalogFilter,
    /** Сколько нашлось. Отрицательное — «столько уже загружено», точного числа нет. */
    found: Int,
    /** Панель раскрыта (в узком окне — выдвинута отдельным слоем). */
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    /** Узкое окно: панель уезжает в отдельный слой, здесь остаётся только полоса. */
    compact: Boolean,
    onFilter: (CatalogFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** Раздел, в котором живут сохранённые наборы: у кино и аниме они разные. */
    presetScope: String = "anime",
) {
    val chips = remember(filter) { filter.chips() }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterToggle(filter.activeCount, open) { onOpenChange(!open) }
            SortMenu(facets.sorts, filter.sort) { onFilter(filter.copy(sort = it)) }
            Box(Modifier.weight(1f))
            FoundCount(found)
        }
        FilterPresetsRow(presetScope, filter, onFilter)
        if (chips.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { chip ->
                    SelectedChip(chip.label) { onFilter(chip.remove()) }
                }
                Box(
                    Modifier.clip(Shapes.chip).clickable { onFilter(filter.cleared()) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("Сбросить всё", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Широкое окно — панель разворачивается прямо под полосой.
        AnimatedVisibility(
            visible = open && !compact,
            enter = fadeIn(tween(160)) + expandVertically(tween(200, easing = MotionEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160, easing = MotionEasing)),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                FilterFacetBlocks(facets, filter, onFilter)
            }
        }
    }
}

/**
 * Выдвижная панель фильтров для узкого окна. Кладётся ПОСЛЕДНИМ элементом в Box со
 * страницей, чтобы лечь поверх ленты; затемнение позади закрывает её по нажатию.
 */
@Composable
fun FilterSheet(
    facets: FilterFacets,
    filter: CatalogFilter,
    visible: Boolean,
    onClose: () -> Unit,
    onFilter: (CatalogFilter) -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(140)), exit = fadeOut(tween(120))) {
        Box(
            Modifier.fillMaxSize().background(Scrim)
                // Затемнение ловит нажатие и закрывает; indication нет, чтобы фон не
                // мигал рябью на каждое касание.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInHorizontally(tween(220, easing = MotionEasing)) { it },
        exit = slideOutHorizontally(tween(180, easing = MotionEasing)) { it },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.fillMaxHeight().width(340.dp).background(Surface1)
                    // Нажатия внутри панели не должны доходить до затемнения позади.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Фильтры", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box(Modifier.clip(Shapes.pill).clickable(onClick = onClose).padding(6.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = TextSecondary)
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    FilterFacetBlocks(facets, filter, onFilter)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f).clip(Shapes.chip).background(Surface2)
                            .clickable { onFilter(filter.cleared()) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Сбросить всё", color = TextSecondary, fontSize = 13.sp) }
                    Box(
                        Modifier.weight(1f).clip(Shapes.chip).background(AccentOrange)
                            .clickable(onClick = onClose).padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Показать", color = OledBlack, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/**
 * Сами блоки фасетов. Рисуются ТОЛЬКО те, что раздел объявил непустыми: у кино нет
 * количества серий, у сериалов — возрастного сертификата, и пустая рубрика с нулём
 * кнопок выглядела бы поломкой.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterFacetBlocks(facets: FilterFacets, filter: CatalogFilter, onFilter: (CatalogFilter) -> Unit) {
    if (facets.tags.isNotEmpty()) {
        var showAll by remember { mutableStateOf(false) }
        val primary = remember(facets.tags) { facets.tags.filter { it.primary } }
        // Выбранные теги видны всегда, даже если они из скрытой части: иначе нажатие
        // на «Свернуть» прятало бы то, что человек только что выбрал.
        val shown = remember(facets.tags, showAll, filter.tags) {
            if (showAll) facets.tags
            else (primary + facets.tags.filter { it.key in filter.tags }).distinct()
        }
        FacetLabel("Жанры и темы")
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shown.forEach { tag ->
                FilterPill(tag.label, tag.key in filter.tags, key = "tag:${tag.key}") { onFilter(filter.toggleTag(tag.key)) }
            }
            if (facets.tags.size > primary.size) {
                Row(
                    Modifier.clip(Shapes.chip).clickable { showAll = !showAll }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (showAll) "Свернуть" else "Ещё", color = AccentOrange, fontSize = 13.sp)
                    Icon(
                        if (showAll) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null, tint = AccentOrange, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    if (facets.years.isNotEmpty()) {
        FacetLabel("Год выхода")
        FacetRow {
            facets.years.forEach { range ->
                val active = filter.yearFrom == range.first && filter.yearTo == range.last
                FilterPill(yearLabel(range), active, key = "year:${range.first}-${range.last}") {
                    onFilter(
                        if (active) filter.copy(yearFrom = 0, yearTo = 0)
                        else filter.copy(yearFrom = range.first, yearTo = range.last),
                    )
                }
            }
        }
    }

    if (facets.statuses.isNotEmpty()) {
        FacetLabel("Статус")
        FacetRow {
            facets.statuses.forEach { status ->
                FilterPill(status.label, filter.status == status, key = "status:${status.name}") {
                    onFilter(filter.copy(status = if (filter.status == status) null else status))
                }
            }
        }
    }

    if (facets.contentTypes.isNotEmpty()) {
        FacetLabel("Тип")
        FacetRow {
            facets.contentTypes.forEach { type ->
                FilterPill(type.label, filter.contentType == type, key = "type:${type.name}") {
                    onFilter(filter.copy(contentType = if (filter.contentType == type) null else type))
                }
            }
        }
    }

    if (facets.ageRatings.isNotEmpty()) {
        FacetLabel("Возрастной рейтинг")
        FacetRow {
            facets.ageRatings.forEach { age ->
                FilterPill(age.label, filter.ageRating == age, key = "age:${age.name}") {
                    onFilter(filter.copy(ageRating = if (filter.ageRating == age) null else age))
                }
            }
        }
    }

    if (facets.sourceMaterials.isNotEmpty()) {
        FacetLabel("Первоисточник")
        FacetRow {
            facets.sourceMaterials.forEach { material ->
                FilterPill(material.label, filter.sourceMaterial == material.key, key = "material:${material.key}") {
                    onFilter(filter.copy(sourceMaterial = if (filter.sourceMaterial == material.key) "" else material.key))
                }
            }
        }
    }

    if (facets.dubbings.isNotEmpty()) {
        FacetLabel("Озвучка")
        FacetRow {
            facets.dubbings.forEach { studio ->
                FilterPill(studio.label, filter.dubbing == studio.key, key = "dub:${studio.key}") {
                    onFilter(filter.copy(dubbing = if (filter.dubbing == studio.key) "" else studio.key))
                }
            }
        }
    }

    if (facets.countries.isNotEmpty()) {
        FacetLabel("Страна")
        FacetRow {
            facets.countries.forEach { country ->
                FilterPill(country, filter.country == country, key = "country:$country") {
                    onFilter(filter.copy(country = if (filter.country == country) "" else country))
                }
            }
        }
    }

    if (facets.episodes.isNotEmpty()) {
        FacetLabel("Количество серий")
        FacetRow {
            facets.episodes.forEach { range ->
                FilterPill(range.label, filter.episodes == range, key = "episodes:${range.name}") {
                    onFilter(filter.copy(episodes = if (filter.episodes == range) null else range))
                }
            }
        }
    }

    FacetLabel("Просмотренное")
    FacetRow {
        FilterPill("Скрыть просмотренное", filter.hideWatched, key = "hideWatched") {
            onFilter(filter.copy(hideWatched = !filter.hideWatched))
        }
    }
    if (facets.protagonists.isNotEmpty()) {
        FacetLabel("Главный герой")
        FacetRow {
            facets.protagonists.forEach { hero ->
                FilterPill(hero.label, filter.protagonist == hero.key, key = "hero:${hero.key}") {
                    onFilter(filter.copy(protagonist = if (filter.protagonist == hero.key) "" else hero.key))
                }
            }
        }
    }

    if (facets.hiddenGems) {
        FacetLabel("Подборка")
        FacetRow {
            // Высокая оценка при малой аудитории — то, что не всплывает в «популярном».
            FilterPill("Скрытые жемчужины", filter.hiddenGems, key = "gems") {
                onFilter(filter.copy(hiddenGems = !filter.hiddenGems))
            }
        }
    }

    if (facets.minRatings.isNotEmpty()) {
        FacetLabel("Оценка не ниже")
        FacetRow {
            facets.minRatings.forEach { min ->
                FilterPill("%.0f+".format(min), filter.minRating == min, key = "minRating:$min") {
                    onFilter(filter.copy(minRating = if (filter.minRating == min) 0.0 else min))
                }
            }
        }
    }
}

private fun yearLabel(range: IntRange): String = when {
    range.first == range.last -> range.first.toString()
    range.first <= 1900 -> "до ${range.last + 1}"
    else -> "${range.first}—${range.last}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetRow(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun FacetLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

/** Кнопка «Фильтры» со счётчиком выбранного — он и подсказывает, что фильтр включён. */
@Composable
private fun FilterToggle(count: Int, open: Boolean, onClick: () -> Unit) {
    val active = count > 0 || open
    Row(
        Modifier.clip(Shapes.chip)
            .background(if (active) AccentOrange.copy(alpha = 0.20f) else Surface2)
            .border(BorderStroke(1.dp, if (active) AccentOrange.copy(alpha = 0.55f) else Surface2), Shapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.FilterAlt, contentDescription = null,
            tint = if (active) AccentOrange else TextSecondary, modifier = Modifier.size(17.dp),
        )
        Text(
            "Фильтры",
            color = if (active) AccentOrange else TextSecondary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
        if (count > 0) {
            Box(
                Modifier.padding(start = 6.dp).clip(Shapes.pill).background(AccentOrange)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("$count", color = OledBlack, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SortMenu(options: List<CatalogSort>, current: CatalogSort, onSelect: (CatalogSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 8.dp)) {
        Row(
            Modifier.clip(Shapes.chip).background(Surface2).clickable { open = true }
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current.label, color = TextPrimary, fontSize = 13.sp)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Сортировка", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == current) "● ${option.label}" else option.label) },
                    onClick = { open = false; if (option != current) onSelect(option) },
                )
            }
        }
    }
}

/**
 * Счётчик найденного.
 *
 * Отрицательное число значит «столько УЖЕ ЗАГРУЖЕНО, и это не всё»: точного итога
 * каталог аниме не отдаёт (его `total_count` равен размеру страницы), а выдумывать
 * там красивое число было бы враньём. У кино TMDB отдаёт настоящий итог, и он
 * показывается как есть.
 */
@Composable
private fun FoundCount(found: Int) {
    if (found == 0) return
    val text = if (found < 0) "Найдено ${-found}+" else "Найдено $found"
    Text(text, color = TextSecondary, fontSize = 13.sp)
}

@Composable
private fun SelectedChip(label: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(Shapes.chip).background(AccentOrange.copy(alpha = 0.20f))
            .border(BorderStroke(1.dp, AccentOrange.copy(alpha = 0.5f)), Shapes.chip)
            .clickable(onClick = onRemove)
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AccentOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Icon(
            Icons.Filled.Close, contentDescription = "Убрать «$label»",
            tint = AccentOrange, modifier = Modifier.padding(start = 4.dp).size(14.dp),
        )
    }
}

/**
 * Сохранённые наборы фильтров: строка пилюль под полосой. Нажатие применяет набор,
 * крестик удаляет, «+ Сохранить набор» появляется, когда есть что сохранять, и
 * раскрывает поле для имени (Enter / галочка — сохранить, Esc — отмена).
 * Строка не занимает места, пока наборов нет и сохранять нечего.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPresetsRow(scope: String, filter: CatalogFilter, onFilter: (CatalogFilter) -> Unit) {
    val settings = LocalAppSettings.current ?: return
    // Подписка на одно поле: состояние настроек меняется на каждую запись прогресса,
    // а строка наборов должна перерисовываться только при смене самих наборов.
    val allPresets by remember(settings) { settings.state.map { it.filterPresets }.distinctUntilChanged() }
        .collectAsState(settings.state.value.filterPresets)
    val presets = remember(allPresets, scope) { allPresets.filter { it.scope == scope } }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val current = remember(filter) { PersistedFilter.of(filter) }
    if (presets.isEmpty() && filter.activeCount == 0) return
    fun commit() {
        settings.saveFilterPreset(name, scope, filter)
        naming = false
        name = ""
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        presets.forEach { preset ->
            val active = preset.filter == current
            Row(
                Modifier.clip(Shapes.chip)
                    .background(if (active) AccentOrange.copy(alpha = 0.28f) else Surface2)
                    .border(BorderStroke(1.dp, if (active) AccentOrange.copy(alpha = 0.55f) else Surface2), Shapes.chip)
                    .clickable { onFilter(preset.filter.toFilter()) }
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = if (active) AccentOrange else TextSecondary, modifier = Modifier.size(13.dp))
                Text(preset.name, color = if (active) AccentOrange else TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp))
                Box(Modifier.clip(Shapes.pill).clickable { settings.deleteFilterPreset(preset.name, scope) }.padding(2.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Удалить набор", tint = TextTertiary, modifier = Modifier.size(12.dp))
                }
            }
        }
        // Текущий фильтр уже сохранён под каким-то именем — предлагать «сохранить» нечего.
        val saved = presets.any { it.filter == current }
        when {
            naming -> Row(
                Modifier.clip(Shapes.chip).background(Surface2).border(BorderStroke(1.dp, AccentOrange.copy(alpha = 0.55f)), Shapes.chip)
                    .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val focus = remember { FocusRequester() }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(PRESET_NAME_MAX) },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                    cursorBrush = SolidColor(AccentOrange),
                    modifier = Modifier.width(150.dp).focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> { commit(); true }
                            Key.Escape -> { naming = false; name = ""; true }
                            else -> false
                        }
                    },
                    decorationBox = { inner ->
                        Box { if (name.isEmpty()) Text("Имя набора", color = TextTertiary, fontSize = 12.sp); inner() }
                    },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                Box(Modifier.clip(Shapes.pill).clickable(enabled = name.isNotBlank()) { commit() }.padding(3.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = "Сохранить", tint = if (name.isBlank()) TextTertiary else AccentOrange, modifier = Modifier.size(14.dp))
                }
                Box(Modifier.clip(Shapes.pill).clickable { naming = false; name = "" }.padding(3.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Отмена", tint = TextTertiary, modifier = Modifier.size(14.dp))
                }
            }
            filter.activeCount > 0 && !saved && presets.size < FILTER_PRESETS_MAX -> Box(
                Modifier.clip(Shapes.chip).border(BorderStroke(1.dp, Surface2), Shapes.chip)
                    .clickable { naming = true }.padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("+ Сохранить набор", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Условия, которые зритель включает часто, — панель подсвечивает их обводкой. */
val LocalFrequentFilterKeys = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

@Composable
private fun FilterPill(label: String, active: Boolean, key: String? = null, onClick: () -> Unit) {
    // Частое условие получает тонкую обводку в акцент — заметно, но не спорит с
    // заливкой включённого.
    val frequent = key != null && key in LocalFrequentFilterKeys.current
    val border = when {
        active -> AccentOrange.copy(alpha = 0.55f)
        frequent -> AccentOrange.copy(alpha = 0.6f)
        else -> Surface2
    }
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentOrange.copy(alpha = 0.28f) else Surface2)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (active) AccentOrange else TextPrimary, fontSize = 13.sp)
    }
}

/** Ширина, ниже которой панель фильтров выдвигается отдельным слоем, а не под полосой. */
val COMPACT_WIDTH = 720.dp
