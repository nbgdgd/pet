package com.aniblaze.app.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.aggregator.repository.AnimeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

/**
 * Одна карточка расписания. Всё, что показывает экран, уже строкой: формат рейтинга
 * и мета-строки считается здесь, а не в композиции — на длинном списке под пальцем
 * каждый `"%.1f".format()` в теле @Composable повторяется на каждом кадре скролла.
 */
data class ScheduleTitle(
    val id: String,
    val title: String,
    val poster: String,
    /** Пустая строка = рейтинга нет, бейдж не рисуем. */
    val ratingLabel: String,
    /** Год · студия — то, что реально пришло из каталога; пусто, если ничего нет. */
    val meta: String,
)

/** День недели с тайтлами. Дни без выходов в список не попадают. */
data class ScheduleDay(
    /** 1=Пн .. 7=Вс. */
    val weekday: Int,
    /** Короткая подпись вкладки: «Сегодня» / «Завтра» / «Пн». */
    val tabLabel: String,
    /** Полная подпись залипающего заголовка: «Понедельник · сегодня». */
    val label: String,
    val titles: List<ScheduleTitle>,
    /**
     * Позиция заголовка этого дня в плоском списке LazyColumn (заголовок + его
     * карточки). Нужна и чтобы вкладка проматывала список к своему дню, и чтобы по
     * первому видимому индексу понять, какую вкладку подсветить, — обе задачи иначе
     * пришлось бы решать пересчётом по всему списку прямо в композиции.
     */
    val headerIndex: Int,
)

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState

    /** Загрузка прошла, но выходов нет — это не ошибка. */
    data object Empty : ScheduleUiState

    /** Загрузка не удалась. Отделено от [Empty] намеренно: иначе экран обвиняет
     *  расписание в том, что не ответила сеть, и не предлагает повторить. */
    data object Failed : ScheduleUiState

    data class Ready(val days: List<ScheduleDay>) : ScheduleUiState
}

/**
 * «Расписание» — календарь онгоингов: по строке на день недели, сегодня первым,
 * чтобы «когда что выходит» читалось одним взглядом, а не вычитывалось из ленты
 * онгоингов.
 *
 * Внимание: дни — ЯПОНСКИЕ даты эфира; озвучка приезжает примерно на сутки позже
 * (уведомитель новых серий считает реальные серии у источника ровно поэтому).
 */
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: AnimeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ScheduleUiState>(ScheduleUiState.Loading)
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = ScheduleUiState.Loading
            // Отдельного «расписания» у Android-репозитория нет: на ПК weekSchedule()
            // это тот же catalog("ongoing"), отфильтрованный по broadcast. Здесь
            // повторено один в один, чтобы не плодить метод в чужом модуле.
            val titles = try {
                repository.catalog("ongoing")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "[Schedule] catalog(ongoing) failed")
                _state.value = ScheduleUiState.Failed
                return@launch
            }
            val days = withContext(Dispatchers.Default) {
                buildDays(titles, LocalDate.now().dayOfWeek.value)
            }
            _state.value = if (days.isEmpty()) ScheduleUiState.Empty else ScheduleUiState.Ready(days)
        }
    }
}

private val RU_DAYS = listOf(
    "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье",
)

private val RU_DAYS_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Группировка по дню эфира, сегодня первым, дальше неделя по порядку выхода.
 * [today] — 1=Пн..7=Вс.
 */
internal fun buildDays(titles: List<Anime>, today: Int): List<ScheduleDay> {
    val byDay = titles.filter { it.broadcast in 1..7 }.groupBy { it.broadcast }
    if (byDay.isEmpty()) return emptyList()

    val tomorrow = (today % 7) + 1
    val days = ArrayList<ScheduleDay>(7)
    var index = 0
    for (offset in 0..6) {
        val weekday = ((today - 1 + offset) % 7) + 1
        val dayTitles = byDay[weekday].orEmpty()
        if (dayTitles.isEmpty()) continue
        val name = RU_DAYS[weekday - 1]
        days += ScheduleDay(
            weekday = weekday,
            tabLabel = when (weekday) {
                today -> "Сегодня"
                tomorrow -> "Завтра"
                else -> RU_DAYS_SHORT[weekday - 1]
            },
            label = when (weekday) {
                today -> "$name · сегодня"
                tomorrow -> "$name · завтра"
                else -> name
            },
            titles = dayTitles.map { it.toScheduleTitle() },
            headerIndex = index,
        )
        // Заголовок + карточки дня — столько позиций займёт день в LazyColumn.
        index += 1 + dayTitles.size
    }
    return days
}

private fun Anime.toScheduleTitle() = ScheduleTitle(
    id = id,
    title = title,
    poster = poster,
    ratingLabel = if (rating > 0) "%.1f".format(rating) else "",
    meta = listOfNotNull(
        year.takeIf { it > 0 }?.toString(),
        studio.takeIf { it.isNotBlank() },
    ).joinToString(" · "),
)
