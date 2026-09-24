package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.DesktopRepository
import com.aniblaze.desktop.NewEpisodeNotifier
import com.aniblaze.desktop.player.ChatIntensity
import com.aniblaze.desktop.player.ChatOverlayPosition
import com.aniblaze.desktop.player.DanmakuRate
import com.aniblaze.desktop.player.VideoEnhance
import com.aniblaze.desktop.restartApplication
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private val ALL_SOURCES = listOf("Anixart", "AniLibria", "AnimeVost", "Kodik", "Shikimori", "YummyAnime", "Animedia", "SameBand")

/**
 * Экран настроек.
 *
 * Собран группами, а не сплошной лентой: настроек здесь под сорок, и в плоском списке
 * они находились только перебором сверху вниз. Правило группы одно — «когда я об этом
 * вспоминаю»: про картинку думают, когда она рябит, про чат — когда он мешает, и
 * искать их надо в разных местах.
 *
 * Второе правило — вложенность. Настройка, которая ничего не делает при выключенном
 * родителе, не показывается вовсе: выключенный, но видимый переключатель читается как
 * сломанный, а не как недоступный.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    repository: DesktopRepository,
    notifier: NewEpisodeNotifier? = null,
) {
    val state by settings.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Настройки", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // --- Каталог ---
        Group("Каталог") {
            Text("Источники аниме", color = TextSecondary, fontSize = 12.sp)
            SourceChecklist(state.enabledSources, settings::setSourceEnabled)
            SourceHealthBlock(repository)
            ChipRow(
                label = "Постеров в ряду",
                options = listOf(0 to "Авто", 3 to "3", 4 to "4", 5 to "5", 6 to "6"),
                isSelected = { it == state.gridColumns },
                onSelect = settings::setGridColumns,
            )
        }

        // --- Внешний вид ---
        Group("Внешний вид") {
            ThemePicker(
                themeKey = state.theme,
                accentKey = state.accent,
                onTheme = settings::setTheme,
                onAccent = settings::setAccent,
            )
            ChipRow(
                label = "Размер шрифта интерфейса",
                options = listOf(0.9f to "90%", 1.0f to "100%", 1.1f to "110%", 1.25f to "125%", 1.4f to "140%"),
                // Сравнение с допуском: значения дробные, и точное равенство здесь
                // ловило бы не каждое нажатие.
                isSelected = { kotlin.math.abs(state.fontScale - it) < 0.01f },
                onSelect = settings::setFontScale,
            )
            ChipRow(
                label = "Размер шрифта комментариев",
                options = listOf(12, 13, 14, 16, 18).map { it to "$it" },
                isSelected = { it == state.commentFontSize },
                onSelect = settings::setCommentFontSize,
            )
            Toggle(
                title = "Затемнять просмотренные",
                note = "Обложки досмотренных до конца тайтлов обесцвечиваются во всех каталогах — " +
                    "видно, что уже пройдено. Названия и оценки остаются в полную силу, а брошенные " +
                    "на середине не гаснут никогда: к ним как раз возвращаются.",
                checked = state.dimWatched,
                onChange = settings::setDimWatched,
            )
        }

        // --- Воспроизведение ---
        Group("Воспроизведение") {
            Toggle(
                title = "Приоритет озвучки",
                note = "Дубляж → Studio Band (Студийная Банда) → AniLibria. Ручной выбор для тайтла сохраняется. Применяется при следующей загрузке серии.",
                checked = state.voicePriority,
                onChange = settings::setVoicePriority,
            )
            Toggle(
                title = "Следующая серия сама",
                note = "Досмотрел — включается следующая, без возврата к списку.",
                checked = state.autoplayNext,
                onChange = settings::setAutoplayNext,
            )
            Toggle(
                title = "Пропускать опенинг",
                note = "По точным таймингам AniLibria/AniSkip или уверенно распознанному интервалу. Выключено — только кнопка.",
                checked = state.autoSkipOpening,
                onChange = settings::setAutoSkipOpening,
            )
            Toggle(
                title = "Пропускать эндинг",
                note = "Титры перематываются сами. Вместе со «Следующая серия сама» серия просто " +
                    "переходит в следующую; сцену после титров прыжок не съедает.",
                checked = state.autoSkipEnding,
                onChange = settings::setAutoSkipEnding,
            )
            Toggle(
                title = "Определять OP/ED, если таймингов нет",
                note = "Кеш работает сразу; анализ музыки и изображения через FFmpeg — только на паузе, " +
                    "чтобы не мешать загрузке видео. Читает начало и конец потока, " +
                    "учится на нескольких сериях одного релиза. Сомнительные совпадения — только кнопка ≈; " +
                    "первая серия без кеша не пропускается. Без FFmpeg остаются обычные тайминги.",
                checked = state.autoDetectTimings,
                onChange = settings::setAutoDetectTimings,
            )
            Toggle(
                title = "Случайное аниме в конце",
                note = "Серии кончились — прыжок на случайный тайтл. Переключатель есть и в плеере.",
                checked = state.autoSwitchRandom,
                onChange = settings::setAutoSwitchRandom,
            )
            // «Прямой» — то, что приложение делало всегда: играет поток самого каталога
            // (обычно HLS, и каждая перемотка заново открывает сегменты). «Балансер»
            // играет копию с его CDN — отсюда и быстрая перемотка.
            ChipRow(
                label = "Движок",
                options = listOf("direct" to "Прямой поток", "balancer" to "Балансер · Kodik CDN"),
                isSelected = { it == state.playbackEngine },
                onSelect = settings::setPlaybackEngine,
                note = "Балансер перематывается заметно быстрее. Если тайтла у него нет — играет прямой поток.",
            )
            HotkeysBlock(settings, state.hotkeys)
        }

        // --- Картинка ---
        Group("Картинка") {
            Toggle(
                title = "Панель управления поверх видео",
                note = "Кадры рисует приложение: видео на всё окно, при перемотке остаётся последний кадр. " +
                    "Выключи, если картинка идёт рывками — тогда рисует libVLC, но снизу остаётся полоса.",
                checked = state.playerComposeVideo,
                onChange = settings::setPlayerComposeVideo,
            )
            ChipRow(
                label = "Резкость картинки",
                options = VideoEnhance.Level.entries.map { it.key to it.label },
                isSelected = { it == state.videoEnhance },
                onSelect = settings::setVideoEnhance,
                note = "Применяется сразу к самому видео: GPU-фильтр при панели поверх кадра, " +
                    "штатный фильтр libVLC — в нативном режиме. Интерфейс не фильтруется.",
            )
            Nested(state.playerComposeVideo) {
                Toggle(
                    title = "Картинка в картинке",
                    note = "Свернул окно — видео идёт дальше в окошке поверх остальных. " +
                        "Тянется за картинку, растягивается за уголок, двойной клик разворачивает обратно.",
                    checked = state.pictureInPicture,
                    onChange = settings::setPictureInPicture,
                )
            }
            if (!state.playerComposeVideo) {
                Text(
                    "«Картинка в картинке» доступна только с панелью поверх видео; резкость работает и в этом режиме.",
                    color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // --- Реплики поверх кадра ---
        Group("Реплики поверх кадра") {
            Toggle(
                title = "Комментарии поверх видео",
                note = "Изредка всплывают настоящие реплики из обсуждения тайтла. Возможный спойлер " +
                    "показывается как «Новое сообщение»; при открытом чате отдельные плашки не дублируются.",
                checked = state.commentsOverlay,
                onChange = settings::setCommentsOverlay,
            )
            Nested(state.commentsOverlay) {
                ChipRow(
                    label = "Как часто",
                    options = DanmakuRate.entries.map { it.key to it.label },
                    isSelected = { it == state.commentsRate },
                    onSelect = settings::setCommentsRate,
                )
                ChipRow(
                    label = "Движение",
                    options = listOf(true to "Движущиеся", false to "Статичные"),
                    isSelected = { it == state.commentsMoving },
                    onSelect = settings::setCommentsMoving,
                )
                Toggle(
                    title = "Сначала новые",
                    note = "Выключено — сначала самые заплюсованные, их обычно интереснее читать.",
                    checked = state.commentsFreshOnly,
                    onChange = settings::setCommentsFreshOnly,
                )
                SliderRow(
                    label = "Прозрачность: ${(state.commentsOpacity * 100).toInt()}%",
                    value = state.commentsOpacity,
                    range = 0.2f..1.0f,
                    onChange = settings::setCommentsOpacity,
                )
                SliderRow(
                    label = "Размер текста: ${state.commentsFontSize} sp",
                    value = state.commentsFontSize.toFloat(),
                    range = 11f..22f,
                    steps = 10,
                    onChange = { settings.setCommentsFontSize(it.toInt()) },
                )
            }
        }

        // --- Живой чат ---
        Group("Живой чат") {
            Toggle(
                title = "Живой чат во время просмотра",
                note = "Панель справа от плеера. Часть реплик настоящие, остальное пишет виртуальный зал, " +
                    "реагируя на опенинг, драки и тихие сцены. Спрятать на время можно прямо в плеере.",
                checked = state.chatEnabled,
                onChange = settings::setChatEnabled,
            )
            Nested(state.chatEnabled) {
                Toggle(
                    title = "Только реальные зрители",
                    note = "Выдуманного зала не будет вовсе — ни в панели, ни поверх видео. " +
                        "Настоящих реплик на серию десятки, поэтому лента станет заметно реже.",
                    checked = state.chatRealOnly,
                    onChange = settings::setChatRealOnly,
                )
                Toggle(
                    title = "Реплики с YummyAnime",
                    note = "К обсуждению Anixart добавляются комментарии того же аниме с yummyani.me — " +
                        "настоящих реплик в чате становится больше. Только аниме; кино не касается.",
                    checked = state.chatYummyComments,
                    onChange = settings::setChatYummyComments,
                )
                Toggle(
                    title = "Сначала популярные",
                    note = "Настоящие реплики идут от самых заплюсованных к менее — а не равномерной " +
                        "выборкой из всего обсуждения. Первые сообщения чата — то, что оценили больше всего.",
                    checked = state.chatPopularFirst,
                    onChange = settings::setChatPopularFirst,
                )
                Toggle(
                    title = "Только комментарии к текущей серии",
                    note = "Показывать лишь реплики, у которых указана именно эта серия. Реплики без номера серии " +
                        "(YummyAnime, старые обсуждения) скрываются — у части тайтлов чат станет тише.",
                    checked = state.chatEpisodeOnly,
                    onChange = settings::setChatEpisodeOnly,
                )
                Toggle(
                    title = "Ветки ответов",
                    note = "Под самыми обсуждаемыми записями подгружаются ответы; в чате они идут с «@ник» — " +
                        "видно, кому адресована реплика. Немного лишних запросов при открытии серии.",
                    checked = state.chatReplies,
                    onChange = settings::setChatReplies,
                )
                Toggle(
                    title = "Всегда тихо",
                    note = "Ровный темп без ускорения на драке и всплесков на повороте. " +
                        "Что пишут — по-прежнему зависит от сцены, меняется только частота.",
                    checked = state.chatAlwaysQuiet,
                    onChange = settings::setChatAlwaysQuiet,
                )
                ChipRow(
                    label = "Плотность",
                    options = ChatIntensity.entries.map { it.key to it.label },
                    isSelected = { it == state.chatIntensity },
                    onSelect = settings::setChatIntensity,
                )
                ChipRow(
                    label = "Скорость чата",
                    options = com.aniblaze.desktop.player.CHAT_SPEED_PRESETS.map { (speed, label) -> speed.toString() to label },
                    isSelected = { it.toFloat() == com.aniblaze.desktop.player.normalizeChatSpeed(state.chatSpeed) },
                    onSelect = { settings.setChatSpeed(it.toFloat()) },
                )
                Text("Умножает частоту сообщений выбранной плотности. Скорость видео не меняется.",
                    color = TextSecondary, fontSize = 12.sp)
                SliderRow(
                    label = "Зрителей в зале: ${state.chatViewers}",
                    value = state.chatViewers.toFloat(),
                    range = 5f..2000f,
                    onChange = { settings.setChatViewers(it.toInt()) },
                    note = "Счётчик в шапке чата и общий темп ленты.",
                )
                Toggle(
                    title = "Прозрачный чат поверх видео",
                    note = "Вместо боковой панели строки идут прямо по кадру и ничего не перехватывают. " +
                        "Возможный спойлер скрывается как «Новое сообщение». Требует режима «Панель управления поверх видео».",
                    checked = state.chatOverlayMode,
                    onChange = settings::setChatOverlayMode,
                )
                // Оформление у двух видов чата разное, и настраивать имеет смысл только
                // тот, который сейчас показан.
                Nested(state.chatOverlayMode) {
                    ChipRow(
                        label = "Где показывать",
                        options = ChatOverlayPosition.entries.map { it.key to it.label },
                        isSelected = { it == state.chatOverlayPosition },
                        onSelect = settings::setChatOverlayPosition,
                    )
                    SliderRow(
                        label = "Сообщений на экране: ${state.chatOverlayLines}",
                        value = state.chatOverlayLines.toFloat(),
                        range = 3f..20f,
                        steps = 16,
                        onChange = { settings.setChatOverlayLines(it.toInt()) },
                    )
                    SliderRow(
                        label = "Прозрачность: ${(state.chatOverlayOpacity * 100).toInt()}%",
                        value = state.chatOverlayOpacity,
                        range = 0.2f..1.0f,
                        onChange = settings::setChatOverlayOpacity,
                    )
                    SliderRow(
                        label = "Размер текста: ${state.chatOverlayFontSize} sp",
                        value = state.chatOverlayFontSize.toFloat(),
                        range = 10f..22f,
                        steps = 11,
                        onChange = { settings.setChatOverlayFontSize(it.toInt()) },
                    )
                }
                Nested(!state.chatOverlayMode) {
                    ChipRow(
                        label = "Сторона чата",
                        options = listOf("right" to "Chat Right", "left" to "Chat Left"),
                        isSelected = { it == state.chatSide },
                        onSelect = settings::setChatSide,
                    )
                    SliderRow(
                        label = "Ширина панели: ${state.chatPanelWidth} dp",
                        value = state.chatPanelWidth.toFloat(),
                        range = 240f..560f,
                        onChange = { settings.setChatPanelWidth(it.toInt()) },
                    )
                    SliderRow(
                        label = "Размер текста: ${state.chatFontSize} sp",
                        value = state.chatFontSize.toFloat(),
                        range = 10f..20f,
                        steps = 9,
                        onChange = { settings.setChatFontSize(it.toInt()) },
                    )
                }
                Text(
                    "Скорость, плотность, «всегда тихо», «только реальные» и прозрачный режим переключаются " +
                        "прямо в шапке чата — уходить сюда посреди серии не нужно.",
                    color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // --- Кино ---
        Group("Кино", note = "После смены источника приложение перезапустится.") {
            repository.cinemaSources().forEach { src ->
                val selected = src.key == state.cinemaSource
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(
                        selected = selected,
                        onClick = { if (!selected) { settings.setCinemaSource(src.key); restartApplication(settings) } },
                    )
                    Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
                        Text(
                            src.displayName + if (src.paginates) "" else "  ·  без бесконечной прокрутки",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(src.drawbacks, color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
            Toggle(
                title = "Балансеры плагина Lampa",
                note = "Кино играет через студийные озвучки плагина, а не через плеер сайта. " +
                    "Резолвит по KinoPoisk-id; первое открытие грузит плагин (1–2 сек).",
                checked = state.lampaEnabled,
                onChange = settings::setLampaEnabled,
            )
            Nested(state.lampaEnabled) {
                ChipRow(
                    label = "Балансер",
                    options = listOf(
                        "rezka2" to "rezka2 · HDrezka ★",
                        "cdnvideohub" to "cdnvideohub",
                        "kodik" to "kodik",
                        "filmix" to "filmix",
                    ),
                    isSelected = { it == state.lampaBalancer },
                    onSelect = settings::setLampaBalancer,
                    note = "rezka2 — много озвучек, но вне СНГ бывает гео-блок. cdnvideohub — стабильный 1080p. " +
                        "kodik — большая база, обычно до 720p. filmix — HD часто по подписке. " +
                        "Если выбранный ничего не нашёл, идёт авто-фолбэк на остальные живые.",
                )
                // Поле помнит своё значение локально: настройки сохраняются на каждый
                // символ, и перечитывать их обратно в поле означало бы драться с курсором.
                var url by remember { mutableStateOf(state.lampaPluginUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; settings.setLampaPluginUrl(it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("URL плагина") },
                    singleLine = true,
                )
            }
        }

        // --- Уведомления (всплывашки в трее Windows) ---
        // --- Питомец ---
        Group("Питомец") {
            Toggle(
                title = "Питомец-помощник",
                note = "Маленький анимированный помощник в углу: статистика по тайтлу, расписание выхода серий " +
                    "и следующая рекомендация. Графика перенесена из TimePet, автор указан ниже.",
                checked = state.petEnabled,
                onChange = settings::setPetEnabled,
            )
            Nested(state.petEnabled) {
                ChipRow(
                    label = "Персонаж",
                    options = com.aniblaze.desktop.pet.PetDef.ALL.map { it.id to it.displayName },
                    isSelected = { it == state.petCharacter },
                    onSelect = settings::setPetCharacter,
                )
                // Живой предпросмотр выбранного персонажа — тем же спрайтом, что в плеере.
                com.aniblaze.desktop.pet.PetSprite(
                    pet = com.aniblaze.desktop.pet.PetDef.of(state.petCharacter),
                    action = com.aniblaze.desktop.pet.PetAction.HAPPY,
                    modifier = Modifier.padding(top = 6.dp).size(width = (88 * state.petScale).dp, height = (96 * state.petScale).dp),
                )
                ChipRow(
                    label = "Как часто говорит (необязательные реплики)",
                    options = listOf(2 to "2 мин", 5 to "5 мин", 10 to "10 мин", 20 to "20 мин"),
                    isSelected = { it == state.petChatterMinutes },
                    onSelect = settings::setPetChatterMinutes,
                )
                Toggle(
                    title = "Реплики и предложения питомца",
                    note = "Выключено — остаются анимации и карточка по нажатию.",
                    checked = state.petSpeechEnabled,
                    onChange = settings::setPetSpeechEnabled,
                )
                Toggle(
                    title = "Звуки питомца",
                    note = "Короткий тихий сигнал только на события: предложение вернуться или промотать эндинг, " +
                        "просьба оценить сезон, новая серия в избранном. Обычные реплики беззвучны. " +
                        "Звуки — Kenney «Interface Sounds», CC0.",
                    checked = state.petSoundsEnabled,
                    onChange = settings::setPetSoundsEnabled,
                )
                if (state.petSoundsEnabled) {
                    ChipRow(
                        label = "Громкость звуков",
                        options = listOf(0.25f to "Тихо", 0.5f to "Обычно", 0.8f to "Громче"),
                        isSelected = { kotlin.math.abs(state.petSoundVolume - it) < 0.05f },
                        onSelect = { volume -> settings.setPetSoundVolume(volume); com.aniblaze.desktop.pet.PetSounds.play(com.aniblaze.desktop.pet.PetSounds.Sound.SAY) },
                    )
                }
                Toggle(
                    title = "Спокойнее во время просмотра",
                    note = "Убирает фоновые комментарии при просмотре. Drizz сохраняет тихие движения и отвечает на ваши действия. Реакции на паузу, ошибки и завершение остаются.",
                    checked = state.petQuietWatching,
                    onChange = settings::setPetQuietWatching,
                )
                if (state.petCharacter == "drizz") {
                    Text("Активность Drizz", color = TextPrimary, modifier = Modifier.padding(top = 10.dp))
                    listOf("calm" to "Спокойный · движения раз в 1,5–3,5 минуты",
                        "normal" to "Обычный · раз в 30–70 секунд",
                        "active" to "Активный · раз в 15–35 секунд").forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = state.drizzActivity == value, onClick = { settings.setDrizzActivity(value) })
                            Text(label, color = TextSecondary, fontSize = 13.sp,
                                modifier = Modifier.clickable { settings.setDrizzActivity(value) })
                        }
                    }
                    Text("Реплики на действия: спокойный — не чаще 15 секунд, обычный — 8, активный — 4. Фоновые комментарии настраиваются отдельно; общий выключатель реплик действует везде.",
                        color = TextSecondary, fontSize = 12.sp)
                }
                Text(
                    "Графика: " + com.aniblaze.desktop.pet.PetDef.of(state.petCharacter).credit,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                SliderRow(
                    label = "Размер: ${(state.petScale * 100).toInt()}%",
                    value = state.petScale,
                    range = 0.7f..1.6f,
                    onChange = settings::setPetScale,
                )
                Toggle(
                    title = "Показывать в плеере",
                    note = "В свободном углу кадра, с перетаскиванием. Выключено — только в обычном интерфейсе.",
                    checked = state.petInPlayer,
                    onChange = settings::setPetInPlayer,
                )
            }
        }

        Group("Уведомления") {
            val tracked = settings.trackedTitles().size
            Toggle(
                title = "Новые серии в избранном и истории",
                note = "Проверка каждые 30 минут. Считаются серии у источника, поэтому уведомление приходит, " +
                    "когда озвучка реально вышла. Отслеживается: $tracked ${ruPlural(tracked, "тайтл", "тайтла", "тайтлов")}.",
                checked = state.notifyNewEpisodes,
                onChange = settings::setNotifyNewEpisodes,
            )
            Toggle(
                title = "Крестик сворачивает в трей",
                note = "Приложение остаётся в фоне, и уведомления продолжают приходить.",
                checked = state.closeToTray,
                onChange = settings::setCloseToTray,
            )
            // Автозапуск живёт в реестре (HKCU\...\Run), не в state.json — реестр и
            // есть источник истины. Доступен только из установленной копии (не gradlew run).
            val autostartAvailable = remember { com.aniblaze.desktop.Autostart.exePath() != null }
            var autostart by remember { mutableStateOf(com.aniblaze.desktop.Autostart.isEnabled()) }
            Toggle(
                title = "Запускать с Windows",
                note = if (autostartAvailable) {
                    "Приложение поднимается в трее вместе с системой."
                } else {
                    "Недоступно: приложение запущено не из установленной копии."
                },
                checked = autostart,
                enabled = autostartAvailable,
                onChange = { enabled -> if (com.aniblaze.desktop.Autostart.set(enabled)) autostart = enabled },
            )
            if (notifier != null) {
                // Кнопки отвечают ТЕКСТОМ. Раньше «Проверить» молчала одинаково и когда
                // всё сработало, и когда Windows проглотила всплывашку, — понять, на чьей
                // стороне обрыв, было нельзя.
                var notifierReport by remember { mutableStateOf<String?>(null) }
                var sweeping by remember { mutableStateOf(false) }
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { notifierReport = notifier.notifyTest() },
                        enabled = notifier.isAvailable(),
                    ) {
                        Text("Проверить уведомление")
                    }
                    OutlinedButton(
                        onClick = {
                            sweeping = true
                            val count = settings.trackedTitles().size
                            notifierReport = "Проверяю $count ${ruPlural(count, "тайтл", "тайтла", "тайтлов")}…"
                            notifier.sweepNow { report ->
                                notifierReport = report
                                sweeping = false
                            }
                        },
                        enabled = !sweeping,
                    ) {
                        Text("Проверить новые серии сейчас")
                    }
                }
                if (!notifier.isAvailable()) {
                    Text(
                        "Системный трей недоступен — Windows не покажет всплывающие уведомления.",
                        color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                notifierReport?.let { report ->
                    Text(report, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // --- Данные ---
        Group("Данные") {
            Text(
                "Избранное: ${state.favorites.size}  ·  История: ${state.history.size}  ·  Продолжить: ${state.progress.size}",
                color = TextSecondary, fontSize = 13.sp,
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { settings.clearFavorites() }, enabled = state.favorites.isNotEmpty()) { Text("Очистить избранное") }
                OutlinedButton(onClick = { settings.clearHistory() }, enabled = state.history.isNotEmpty()) { Text("Очистить историю") }
                OutlinedButton(onClick = { settings.clearProgress() }, enabled = state.progress.isNotEmpty()) { Text("Сбросить прогресс") }
            }
            BackupsBlock(settings)
            // Дисковый кэш ответов Shikimori/Jikan (см. HostPolicy): показать объём, дать почистить.
            var cacheStats by remember { mutableStateOf(com.aniblaze.network.HostPolicy.stats()) }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Кэш ответов Shikimori/Jikan: ${cacheStats.first} файлов, ${cacheStats.second / 1024} КБ — сутки, чтобы не ловить 429.",
                    color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { com.aniblaze.network.HostPolicy.clear(); cacheStats = com.aniblaze.network.HostPolicy.stats() }) { Text("Очистить") }
            }
            LogViewerBlock()
        }

        Text(
            "AniBlaze для ПК — аниме (Anixart/AniLibria/Kodik/Shikimori) и кино (Lordfilm/Zetflix/Kinozapas). " +
                "Видео проигрывается через встроенный VLC; для кино доступен «Плеер в браузере» с фирменными озвучками.",
            color = TextSecondary,
            modifier = Modifier.padding(top = 28.dp),
        )
    }
}

/**
 * Резервные копии состояния: одна в день, семь последних. Восстановление — в два
 * нажатия (второе подтверждает), текущее состояние перед этим само откладывается.
 */
@Composable
private fun BackupsBlock(settings: AppSettings) {
    var backups by remember { mutableStateOf(settings.backups()) }
    var confirm by remember { mutableStateOf<java.io.File?>(null) }
    var note by remember { mutableStateOf("") }
    Text("Резервные копии", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
    Text(
        "Копия состояния (избранное, история, прогресс, оценки, настройки) делается раз в день при запуске; хранятся последние семь.",
        color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
    )
    // Перенос на другой ПК и таблицы: JSON — полное состояние (импорт = восстановление
    // с откатом через state-before-restore.json), CSV — библиотека для Excel.
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            pickFile("Экспорт состояния", save = true, suggested = "aniblaze-state.json")?.let { file ->
                note = if (settings.exportState(file)) "Сохранено: ${file.name}" else "Не удалось сохранить ${file.name}"
            }
        }) { Text("Экспорт JSON") }
        OutlinedButton(onClick = {
            pickFile("Импорт состояния", save = false)?.let { file ->
                note = if (settings.restoreFrom(file)) "Импортировано из ${file.name}" else "Файл ${file.name} не похож на состояние AniBlaze"
                backups = settings.backups()
            }
        }) { Text("Импорт JSON") }
        OutlinedButton(onClick = {
            pickFile("Экспорт библиотеки", save = true, suggested = "aniblaze-library.csv")?.let { file ->
                note = if (settings.exportCsv(file)) "Сохранено: ${file.name}" else "Не удалось сохранить ${file.name}"
            }
        }) { Text("Экспорт CSV") }
    }
    if (backups.isEmpty()) {
        Text("Копий пока нет — первая появится при следующем запуске.", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    backups.forEach { file ->
        val date = file.name.removePrefix("state-").removeSuffix(".json")
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(date, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
            Text("${file.length() / 1024} КБ", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(70.dp))
            if (confirm == file) {
                OutlinedButton(onClick = {
                    note = if (settings.restoreFrom(file)) "Восстановлено из $date" else "Не удалось прочитать копию $date"
                    confirm = null
                    backups = settings.backups()
                }) { Text("Да, восстановить") }
                OutlinedButton(onClick = { confirm = null }, modifier = Modifier.padding(start = 8.dp)) { Text("Отмена") }
            } else {
                OutlinedButton(onClick = { confirm = file }) { Text("Восстановить") }
            }
        }
    }
    if (note.isNotBlank()) Text(note, color = AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

/** Системный диалог файла (AWT): null — отменили. Модальный, зовётся с потока UI. */
private fun pickFile(title: String, save: Boolean, suggested: String = ""): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
    if (suggested.isNotBlank()) dialog.file = suggested
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return java.io.File(dialog.directory, name)
}

/**
 * Русское склонение существительного при числе: 1 тайтл, 2 тайтла, 5 тайтлов.
 *
 * Раньше на этом месте стояло «тайтл(ов)» — форма, которой в живом языке нет, и
 * читается она как заглушка, забытая в готовом экране.
 */
internal fun ruPlural(count: Int, one: String, few: String, many: String): String {
    val abs = kotlin.math.abs(count)
    // Одиннадцать-четырнадцать — исключение: они кончаются на 1..4, но требуют
    // «тайтлов». Без этой проверки получилось бы «11 тайтл».
    if (abs % 100 in 11..14) return many
    return when (abs % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}

/** Группа настроек: заголовок, необязательная сноска и содержимое. */
/**
 * Темы — как в Telegram: карточки-превью (две полоски и кружок в цветах темы) и ряд
 * кружков акцента. Выбор применяется сразу и на всё окно.
 */
@Composable
private fun ThemePicker(themeKey: String, accentKey: String, onTheme: (String) -> Unit, onAccent: (String) -> Unit) {
    Text("Темы", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTheme.ALL.forEach { theme ->
            val selected = theme.key == themeKey
            Column(
                Modifier.width(108.dp).clip(RoundedCornerShape(12.dp)).clickable { onTheme(theme.key) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Превью: фон темы, два «сообщения» и кружок — по нему видно и поверхность,
                // и текст, и акцент, не переключая тему.
                Box(
                    Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(12.dp))
                        .background(theme.surface1)
                        .border(
                            BorderStroke(if (selected) 2.dp else 1.dp, if (selected) AccentOrange else theme.glassBorder),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.width(48.dp).height(9.dp).clip(RoundedCornerShape(4.dp)).background(theme.surface3))
                        Box(
                            Modifier.padding(top = 6.dp).align(Alignment.End).width(40.dp).height(9.dp)
                                .clip(RoundedCornerShape(4.dp)).background(AccentOrange.copy(alpha = 0.9f)),
                        )
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter).size(20.dp).clip(RoundedCornerShape(10.dp))
                            .border(BorderStroke(2.dp, if (selected) AccentOrange else theme.textSecondary), RoundedCornerShape(10.dp)),
                    )
                }
                Text(
                    theme.label,
                    color = if (selected) AccentOrange else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
            }
        }
    }
    Text("Цвет акцента", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppAccent.ALL.forEach { accent ->
            val selected = accent.key == accentKey
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(15.dp))
                    .border(BorderStroke(2.dp, if (selected) TextPrimary else androidx.compose.ui.graphics.Color.Transparent), RoundedCornerShape(15.dp))
                    .padding(4.dp)
                    .clip(RoundedCornerShape(11.dp)).background(accent.color)
                    .clickable { onAccent(accent.key) },
            )
        }
        // Свой цвет: «#RRGGBB». Применяется, как только строка стала цветом.
        val customSelected = accentKey.startsWith("#")
        var hex by remember(accentKey) { mutableStateOf(if (customSelected) accentKey else "") }
        val preview = AppAccent.parseHex(hex)
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(15.dp))
                .border(BorderStroke(2.dp, if (customSelected) TextPrimary else androidx.compose.ui.graphics.Color.Transparent), RoundedCornerShape(15.dp))
                .padding(4.dp).clip(RoundedCornerShape(11.dp)).background(preview ?: Surface2),
            contentAlignment = Alignment.Center,
        ) { if (preview == null) Text("#", color = TextSecondary, fontSize = 12.sp) }
        OutlinedTextField(
            value = hex,
            onValueChange = { value ->
                hex = value.take(7)
                AppAccent.parseHex(value)?.let { onAccent("#" + value.trim().removePrefix("#").uppercase()) }
            },
            singleLine = true,
            placeholder = { Text("#FF6A3D", fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            modifier = Modifier.width(120.dp).height(44.dp),
        )
    }
}

@Composable
private fun Group(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentOrange)
        if (note != null) {
            Text(note, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp).height(1.dp).background(Surface2))
        content()
    }
}

/**
 * Вложенный блок: рисуется, только когда включён родитель.
 *
 * Отступ слева — единственный признак подчинения, который переживает любую тему:
 * рамки и заливки на чёрном фоне читаются хуже, чем сдвиг.
 */
@Composable
private fun Nested(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (!visible) return
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 2.dp, bottom = 4.dp)) { content() }
}

/**
 * Переключатель: название слева, тумблер справа.
 *
 * Тумблер, а не галочка: состояние «включено» у него видно с расстояния, и вопрос
 * «это отметка о факте или переключатель режима» не возникает.
 */
@Composable
private fun Toggle(
    title: String,
    note: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) TextPrimary else TextSecondary)
            if (note != null) {
                Text(note, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Выбор одного варианта из нескольких — «чипами».
 *
 * Чипы, а не столбик из радиокнопок: варианты здесь короткие, и в один ряд их видно
 * целиком, тогда как столбик из четырёх строк съедал экран на каждой такой настройке.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    note: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, text) ->
                val active = isSelected(value)
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (active) AccentOrange else Surface2)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text,
                        color = if (active) OledBlack else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        if (note != null) {
            Text(note, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Ползунок с подписью, в которой уже стоит текущее значение. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    note: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(0.5f),
        )
        if (note != null) {
            Text(note, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * Журнал диагностики прямо в настройках: хвост файла, фильтр по подстроке, кнопки
 * «обновить», «открыть папку», «скопировать». Чтобы отвечать «что случилось» без
 * похода в %APPDATA%. Читается порциями по [LOG_TAIL_LINES] строк с конца.
 */
@Composable
private fun LogViewerBlock() {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val tail = runCatching { readTail(com.aniblaze.desktop.player.PlayerDiagnostics.file, LOG_TAIL_LINES) }.getOrDefault(emptyList())
            withContext(kotlinx.coroutines.Dispatchers.Main) { lines = tail }
        }
    }
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Журнал диагностики", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { open = !open; if (!open) lines = emptyList() else reload() }) { Text(if (open) "Скрыть" else "Показать") }
    }
    if (!open) return
    val shown = remember(lines, filter) {
        val needle = filter.trim()
        if (needle.isEmpty()) lines else lines.filter { it.contains(needle, ignoreCase = true) }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text("Фильтр: stream, notifier, pet…", fontSize = 12.sp) },
            modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        )
        OutlinedButton(onClick = { reload() }) { Text("Обновить") }
        OutlinedButton(onClick = {
            runCatching { java.awt.Desktop.getDesktop().open(com.aniblaze.desktop.player.PlayerDiagnostics.file.parentFile) }
        }) { Text("Папка") }
        OutlinedButton(enabled = shown.isNotEmpty(), onClick = {
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(shown.joinToString(System.lineSeparator())), null)
            }
        }) { Text("Копировать") }
    }
    Text(
        "${shown.size} из ${lines.size} последних строк · ${com.aniblaze.desktop.player.PlayerDiagnostics.file.path}",
        color = TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
    )
    val logState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) logState.scrollToItem(shown.size - 1) }
    androidx.compose.foundation.lazy.LazyColumn(
        state = logState,
        modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp)).background(Surface2).padding(8.dp),
    ) {
        items(shown.size) { index ->
            val line = shown[index]
            val tone = when {
                "fail" in line || "error" in line || "crash" in line -> ErrorRed
                "warn" in line || "timeout" in line || "abandon" in line -> RatingGold
                else -> TextSecondary
            }
            Text(line, color = tone, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Сколько строк журнала показываем. */
private const val LOG_TAIL_LINES = 400

/** Последние [count] строк файла без чтения его целиком в память (файл до 2 МБ, но всё же). */
internal fun readTail(file: java.io.File, count: Int): List<String> {
    if (!file.isFile) return emptyList()
    val out = ArrayDeque<String>(count + 1)
    file.bufferedReader(Charsets.UTF_8).useLines { seq ->
        seq.forEach { line ->
            if (line.isBlank()) return@forEach
            out.addLast(line)
            if (out.size > count) out.removeFirst()
        }
    }
    return out.toList()
}

/**
 * Горячие клавиши плеера: список действий с клавишами, каждую можно переназначить.
 * Нажали на клавишу — блок ждёт следующего нажатия (Esc — отмена, «↺» — умолчание).
 * Сами привязки хранятся в настройках и попадают в плеер через [Hotkeys.custom].
 */
@Composable
private fun HotkeysBlock(settings: AppSettings, hotkeys: Map<String, Long>) {
    var listening by remember { mutableStateOf<com.aniblaze.desktop.player.PlayerAction?>(null) }
    val custom = remember(hotkeys) {
        hotkeys.mapNotNull { (k, v) -> com.aniblaze.desktop.player.PlayerAction.byKey(k)?.let { it to v } }.toMap()
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Text("Горячие клавиши плеера", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
    Text(
        "Нажмите на клавишу, чтобы назначить другую. Esc не переназначается — это всегда выход из полного экрана.",
        color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
    )
    Column(
        Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            val target = listening ?: return@onPreviewKeyEvent false
            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent true
            when {
                event.key == androidx.compose.ui.input.key.Key.Escape -> listening = null
                com.aniblaze.desktop.player.Hotkeys.assignable(event.key) -> {
                    settings.setHotkey(target.key, event.key.keyCode)
                    listening = null
                }
            }
            true
        },
    ) {
        com.aniblaze.desktop.player.PlayerAction.entries.forEach { action ->
            val waiting = listening == action
            val overridden = action in custom
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (waiting) AccentOrange.copy(alpha = 0.28f) else Surface2)
                        .clickable { listening = if (waiting) null else action; focus.requestFocus() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (waiting) "нажмите клавишу…" else com.aniblaze.desktop.player.Hotkeys.label(action, custom),
                        color = if (waiting) AccentOrange else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(action.label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                if (overridden) {
                    Text(
                        "↺ умолчание", color = TextTertiary, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { settings.setHotkey(action.key, null) }.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Здоровье источников: ответы за час, последний успешный ответ, кнопка «проверить».
 * Данные копятся из настоящих запросов (см. SourceHealth), проверка — validateSource.
 */
@Composable
private fun SourceHealthBlock(repository: com.aniblaze.desktop.DesktopRepository) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf<String?>(null) }
    Text("Здоровье источников", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
    ALL_SOURCES.forEach { name ->
        val snap = com.aniblaze.desktop.SourceHealth.snapshots[name]
        val status = when {
            snap == null -> "нет данных за этот сеанс"
            snap.failureShare >= 0.5 && snap.total >= 3 -> "сбоит: ${snap.failed} из ${snap.total} за час"
            snap.total > 0 -> "ответов за час: ${snap.ok} из ${snap.total}"
            else -> "нет данных"
        }
        val tone = when {
            snap == null || snap.total == 0 -> TextSecondary
            snap.failureShare >= 0.5 && snap.total >= 3 -> ErrorRed
            snap.failed > 0 -> RatingGold
            else -> WatchedGreen
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(tone))
            Text(name, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).width(120.dp))
            Text(
                status + (snap?.let { "  ·  последний ответ ${com.aniblaze.desktop.SourceHealth.ago(it.lastOkAt)}" } ?: ""),
                color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1,
            )
            OutlinedButton(
                enabled = checking == null,
                onClick = { checking = name; scope.launch { repository.checkSource(name); checking = null } },
            ) { Text(if (checking == name) "Проверяю…" else "Проверить") }
        }
        snap?.lastError?.takeIf { it.isNotBlank() && snap.failed > 0 }?.let { err ->
            Text("последняя ошибка: $err", color = TextTertiary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** Источники каталога: множественный выбор, поэтому галочки, а не чипы. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceChecklist(enabled: Collection<String>, onChange: (String, Boolean) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ALL_SOURCES.forEach { name ->
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = name in enabled, onCheckedChange = { onChange(name, it) })
                Text(name)
            }
        }
    }
}
