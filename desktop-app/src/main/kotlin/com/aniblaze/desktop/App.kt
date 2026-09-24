package com.aniblaze.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import com.aniblaze.desktop.ui.dragScroll
import com.aniblaze.desktop.ui.horizontalMouseWheel
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.aniblaze.desktop.player.PlayerDiagnostics
import com.aniblaze.desktop.ui.AccentOrange
import com.aniblaze.desktop.ui.AniBlazeTheme
import com.aniblaze.desktop.ui.CinemaScreen
import com.aniblaze.desktop.ui.PersonScreen
import com.aniblaze.desktop.ui.DetailScreen
import com.aniblaze.desktop.ui.FavoritesScreen
import com.aniblaze.desktop.ui.GlassFill
import com.aniblaze.desktop.ui.GlassFillStrong
import com.aniblaze.desktop.ui.HistoryScreen
import com.aniblaze.desktop.ui.HomeScreen
import com.aniblaze.desktop.ui.StudioScreen
import com.aniblaze.desktop.ui.NewEpisodesScreen
import com.aniblaze.desktop.ui.LocalAppSettings
import com.aniblaze.desktop.ui.PlayerScreen
import com.aniblaze.desktop.ui.mouseBackForward
import com.aniblaze.desktop.ui.MotionEasing
import com.aniblaze.desktop.ui.SettingsScreen
import com.aniblaze.desktop.ui.Shapes
import com.aniblaze.desktop.ui.Surface1
import com.aniblaze.desktop.ui.TabHoverFill
import com.aniblaze.desktop.ui.TabPressedFill
import com.aniblaze.desktop.ui.TabStripBackground
import com.aniblaze.desktop.ui.TabStripDivider
import com.aniblaze.desktop.ui.TextPrimary
import com.aniblaze.desktop.ui.TextSecondary
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem

@Composable
fun App(
    repository: DesktopRepository,
    settings: AppSettings,
    windowState: WindowState,
    notifier: NewEpisodeNotifier? = null,
    // false = window hidden to tray → pause playback (audio must not keep going
    // from an invisible window).
    windowShown: Boolean = true,
    // Клик по всплывашке «новая серия»: открыть страницу этого тайтла.
    openTitleRequest: com.aniblaze.aggregator.model.Anime? = null,
    onOpenTitleConsumed: () -> Unit = {},
    onRequestMainWindow: () -> Unit = {},
) {
    val nav = remember { NavController().apply { canonical = settings::canonical } }
    // Тема и акцент — из настроек, сразу и при каждой смене.
    val themeKey by remember(settings) { settings.state.map { it.theme to it.accent }.distinctUntilChanged() }
        .collectAsState(settings.state.value.theme to settings.state.value.accent)
    LaunchedEffect(themeKey) { com.aniblaze.desktop.ui.AppThemeState.apply(themeKey.first, themeKey.second) }
    // Звуки питомца — тоже из настроек.
    val petSound by remember(settings) { settings.state.map { it.petSoundsEnabled to it.petSoundVolume }.distinctUntilChanged() }
        .collectAsState(settings.state.value.petSoundsEnabled to settings.state.value.petSoundVolume)
    LaunchedEffect(petSound) {
        com.aniblaze.desktop.pet.PetSounds.enabled = petSound.first
        com.aniblaze.desktop.pet.PetSounds.volume = petSound.second
    }
    val requestMainWindow by androidx.compose.runtime.rememberUpdatedState(onRequestMainWindow)
    // Мост из отдельных окон: там карточка тайтла читательская, а смотреть можно
    // только здесь — проигрыватель в приложении один (см. DetachedTitles). Нажатие
    // «смотреть» в отдельном окне приводит серию сюда, в главное.
    androidx.compose.runtime.DisposableEffect(nav) {
        DetachedTitles.mainWindowBridge.handler = { request ->
            when (request) {
                is DetachedMainWindowRequest.Play -> {
                    // openTitlePlaying, а не openPlayer: экран тайтла остаётся под
                    // плеером, и «назад» вернёт к описанию, а не в пустоту.
                    nav.openTitlePlaying(request.anime, request.segment.number)
                }
                is DetachedMainWindowRequest.OpenStudio -> nav.openStudio(request.studio)
                is DetachedMainWindowRequest.OpenPerson -> nav.openPerson(request.person)
            }
            requestMainWindow()
        }
        onDispose { DetachedTitles.mainWindowBridge.handler = null }
    }
    LaunchedEffect(openTitleRequest) {
        openTitleRequest?.let { anime ->
            nav.openTitle(anime, isCinema = repository.isCinema(anime.id))
            onOpenTitleConsumed()
        }
    }
    val current = nav.current
    // Screen state that survives tab switches (no re-fetch on every navigation).
    val homeCache = remember { HomeCache() }
    val cinemaCache = remember { CinemaCache() }
    // Подборка рекомендаций тоже переживает навигацию: обновляется только по кнопке.
    val recommendationsCache = remember { RecommendationsCache() }
    // Cartoons reuse the cinema screen but keep their own scroll/filter state.
    val cartoonCache = remember { CinemaCache() }

    // Player "fullscreen": maximized borderless-look mode (dark OS caption via
    // DwmDark, rail and in-app panels hidden), like a real video player.
    // WindowPlacement.Fullscreen is NOT used on purpose: on Windows the exclusive
    // fullscreen → Floating transition does not resize the skia surface, leaving
    // the whole UI stuck and clipped in a corner of the window. Maximized ↔
    // Floating goes through the normal window path and resizes cleanly.
    var fullscreen by remember { mutableStateOf(false) }
    val prePlacement = remember { mutableStateOf(WindowPlacement.Floating) }
    LaunchedEffect(fullscreen) {
        PlayerDiagnostics.log("app.fullscreen", "enabled=$fullscreen; prePlacement=${prePlacement.value}; currentPlacement=${windowState.placement}")
        if (fullscreen) {
            // Save unconditionally: with Maximized as the fullscreen state, the
            // previous placement (Floating or an already-maximized window) must be
            // restored exactly on exit.
            prePlacement.value = windowState.placement
            PlayerDiagnostics.log("app.fullscreen.prePlacement", "saved=${prePlacement.value}")
            windowState.placement = WindowPlacement.Maximized
        } else if (windowState.placement == WindowPlacement.Maximized) {
            windowState.placement = prePlacement.value
        }
    }
    LaunchedEffect(current) {
        PlayerDiagnostics.log("app.navigation", "current=${current?.javaClass?.simpleName}; isPlayer=${current is Screen.Player}; fullscreen=$fullscreen")
        if (current !is Screen.Player) fullscreen = false
    }

    // Пользовательский масштаб шрифтов: множится на системный fontScale и уходит
    // в Density всего дерева — каждый sp в приложении растёт/уменьшается, при этом
    // dp-раскладка (постеры, сетки, отступы) остаётся как была.
    //
    // Берём ИМЕННО одно поле, а не весь state. Настройки сохраняются во время
    // просмотра постоянно (позиция, прогресс, счётчики серий), и подписка на весь
    // объект пересобирала всё дерево на каждую запись: замерено по журналу — 3449
    // пересборок App за сеанс, и столько же у экрана плеера. Здесь пересборка нужна
    // ровно тогда, когда поменялся масштаб шрифта.
    val fontScale by remember(settings) {
        settings.state.map { it.fontScale }.distinctUntilChanged()
    }.collectAsState(settings.state.value.fontScale)
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = remember(baseDensity, fontScale) {
        androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * fontScale)
    }
    AniBlazeTheme {
      // Снимок горячих клавиш для плеера: обработчик клавиш не ходит в настройки.
      // Подписка на одно поле — состояние настроек меняется на каждую запись прогресса.
      val hotkeys by remember(settings) { settings.state.map { it.hotkeys }.distinctUntilChanged() }
          .collectAsState(settings.state.value.hotkeys)
      LaunchedEffect(hotkeys) {
          com.aniblaze.desktop.player.Hotkeys.custom = hotkeys.mapNotNull { (k, v) ->
              com.aniblaze.desktop.player.PlayerAction.byKey(k)?.let { it to v }
          }.toMap()
      }
      CompositionLocalProvider(
          LocalAppSettings provides settings,
          androidx.compose.ui.platform.LocalDensity provides scaledDensity,
      ) {
        val saveError by settings.persistenceError.collectAsState()
        if (saveError != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text("Изменения не сохранены") },
                text = { Text(saveError.orEmpty()) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = settings::retrySave) {
                        Text("Повторить сохранение")
                    }
                },
            )
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize().mouseBackForward(onBack = { nav.backFromHome(homeCache) }, onForward = { nav.forward() })) {
                val topLevelSelected = when (current) {
                    is Screen.Home -> TopLevel.Home
                    is Screen.Recommendations -> TopLevel.Recommendations
                    is Screen.Cinema -> TopLevel.Cinema
                    is Screen.Cartoons -> TopLevel.Cartoons
                    is Screen.NewEpisodes -> TopLevel.NewEpisodes
                    is Screen.Schedule -> TopLevel.Schedule
                    is Screen.Favorites -> TopLevel.Favorites
                    is Screen.History -> TopLevel.History
                    is Screen.Stats -> TopLevel.Stats
                    is Screen.Settings -> TopLevel.Settings
                    else -> null
                }
                if (!fullscreen) {
                AppSidebar(
                    selected = topLevelSelected,
                    canGoBack = nav.canGoBack || (current == Screen.Home && homeCache.hasLocalNavigation),
                    onBack = { PlayerDiagnostics.log("app.navBack", "canGoBack=${nav.canGoBack}"); nav.backFromHome(homeCache) },
                    onSelect = { target ->
                        PlayerDiagnostics.log("app.navRail", "target=$target")
                        if (target == TopLevel.Home) homeCache.returnToLanding()
                        nav.goTopLevel(screenOf(target))
                    },
                )
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                  Column(Modifier.fillMaxSize()) {
                    if (!fullscreen && nav.tabs.isNotEmpty()) {
                        TitleTabBar(nav)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    // Smooth cross-fade + subtle rise between screens.
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            // Короче и с общей кривой приложения: 280 мс на смену
                            // экрана ощущались как задержка, а не как движение.
                            (
                                fadeIn(tween(200, easing = MotionEasing)) +
                                    slideInVertically(tween(200, easing = MotionEasing)) { it / 26 }
                                ) togetherWith fadeOut(tween(120, easing = MotionEasing))
                        },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> HomeScreen(
                                repository = repository,
                                cache = homeCache,
                                settings = settings,
                                onOpen = { anime -> nav.openTitle(anime, isCinema = repository.isCinema(anime.id)) },
                                // Кнопка «Смотреть» в витрине ведёт в плеер, а не на страницу
                                // тайтла: иначе она ничем не отличалась бы от «Подробнее».
                                onPlay = { anime -> nav.openTitlePlaying(anime, 1) },
                            )
                            Screen.Recommendations -> com.aniblaze.desktop.ui.RecommendationsScreen(repository, settings, cache = recommendationsCache) { anime ->
                                nav.openTitle(anime, isCinema = repository.isCinema(anime.id))
                            }
                            Screen.Cinema -> CinemaScreen(repository, cinemaCache) { anime -> nav.openTitle(anime, isCinema = true) }
                            Screen.Cartoons -> CinemaScreen(repository, cartoonCache, cartoonMode = true) { anime -> nav.openTitle(anime, isCinema = true) }
                            Screen.NewEpisodes -> NewEpisodesScreen(settings) { anime -> nav.openTitle(anime, isCinema = false) }
                            Screen.Schedule -> com.aniblaze.desktop.ui.ScheduleScreen(repository) { anime -> nav.openTitle(anime, isCinema = false) }
                            Screen.Favorites -> FavoritesScreen(settings, repository) { anime -> nav.openTitle(anime, isCinema = repository.isCinema(anime.id)) }
                            Screen.History -> HistoryScreen(settings) { anime -> nav.openTitle(anime, isCinema = repository.isCinema(anime.id)) }
                            Screen.Stats -> com.aniblaze.desktop.ui.StatsScreen(
                                settings = settings,
                                repository = repository,
                                // Пока что-то играет, статистика не лезет в сеть за
                                // жанрами: её запросы отбирали канал у потока.
                                playbackActive = nav.playerSession != null,
                            ) { anime -> nav.openTitle(anime, isCinema = repository.isCinema(anime.id)) }
                            Screen.Settings -> SettingsScreen(settings, repository, notifier)
                            is Screen.Detail -> DetailScreen(
                                anime = screen.anime,
                                isCinema = screen.isCinema,
                                repository = repository,
                                settings = settings,
                                onPlay = { segment -> nav.openPlayer(screen.anime, segment.number) },
                                onOpenRelated = { related -> nav.openTitle(related, isCinema = false) },
                                onOpenStudio = nav::openStudio,
                                onOpenPerson = nav::openPerson,
                            )
                            is Screen.Studio -> StudioScreen(screen.studio, repository) { anime ->
                                nav.openTitle(anime, isCinema = false)
                            }
                            is Screen.Person -> PersonScreen(screen.person, repository) { anime ->
                                nav.openTitle(anime, isCinema = false)
                            }
                            // Player is rendered once in the persistent layer below.
                            // Keeping this branch empty prevents navigation from
                            // disposing libVLC when the user opens Home/Cinema.
                            is Screen.Player -> Box(Modifier.fillMaxSize())
                        }
                    }
                    // Питомец в обычном интерфейсе: постоянный правый нижний угол над
                    // содержимым, но НЕ над плеером (там свой оверлей внутри кадра).
                    if (current !is Screen.Player) {
                        val petFocus = when (val screen = current) {
                            is Screen.Detail -> screen.anime
                            else -> nav.playerSession?.anime ?: settings.history().firstOrNull()
                        }
                        com.aniblaze.desktop.pet.PetHost(
                            settings = settings,
                            repository = repository,
                            focus = petFocus?.takeIf { !repository.isCinema(it.id) },
                            playing = nav.playerSession != null,
                            windowActive = windowShown,
                            focusIsDetail = current is Screen.Detail,
                            onOpenTitle = { anime -> nav.openTitle(anime, isCinema = repository.isCinema(anime.id)) },
                            onResume = { anime, episode -> nav.openTitlePlaying(anime, episode) },
                            // zIndex большой: AnimatedContent поднимает каждый следующий экран
                            // выше предыдущего, и «5» через десяток переходов оказывалось под ним.
                            modifier = Modifier.zIndex(1_000f).align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                        )
                    }
                    // Новая серия в избранном — окошко от питомца поверх ВСЕГО, и плеера
                    // тоже: питомца в углу на экране плеера нет, а новость терять нельзя.
                    com.aniblaze.desktop.pet.PetReleasePopup(
                        settings = settings,
                        onWatch = { anime, episode -> nav.openTitlePlaying(anime, episode) },
                        modifier = Modifier.zIndex(2_000f).align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = if (current is Screen.Player) 96.dp else 132.dp),
                    )
                    nav.playerSession?.let { session ->
                        key(session.anime.id) {
                            val playerVisible = current is Screen.Player && current.anime.id == session.anime.id
                            LaunchedEffect(playerVisible, fullscreen) {
                                PlayerDiagnostics.log(
                                    "player.layer",
                                    "visible=$playerVisible; fullscreen=$fullscreen; zIndex=${if (playerVisible) 10f else -1f}; content=${session.anime.id}",
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .zIndex(if (playerVisible) 10f else -1f),
                            ) {
                                PlayerScreen(
                                    repository = repository,
                                    settings = settings,
                                    anime = session.anime,
                                    segmentNumber = session.segmentNumber,
                                    fullscreen = fullscreen,
                                    visible = playerVisible,
                                    suspended = !windowShown,
                                    onToggleFullscreen = { fullscreen = !fullscreen },
                                    // «Случайное аниме после последней серии» lands here:
                                    // open the picked title straight into its player.
                                    onSwitchTitle = { next -> nav.openTitlePlaying(next, 1) },
                                )
                            }
                        }
                    }
                    }
                  }
                }
            }
        }
      }
    }
}

/**
 * Боковая навигация.
 *
 * Вместо NavigationRail из Material: там значок и подпись стоят друг под другом и
 * выравниваются по центру узкой полосы, а нужен обычный список строк — значок,
 * подпись, активная строка целиком в акценте. Это ровно то, что рисует макет, и
 * заодно читается быстрее: глаз идёт по левому краю, а не по центрам.
 */
@Composable
private fun AppSidebar(
    selected: TopLevel?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSelect: (TopLevel) -> Unit,
) {
    Column(
        Modifier.width(com.aniblaze.desktop.ui.scaledForFont(SIDEBAR_WIDTH)).fillMaxHeight().background(Surface1)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        if (canGoBack) {
            Row(
                Modifier.fillMaxWidth().clip(Shapes.chip).clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AccentOrange, modifier = Modifier.size(20.dp))
                Text("Назад", color = AccentOrange, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        TopLevel.entries.forEach { entry ->
            SidebarItem(entry, entry == selected) { onSelect(entry) }
        }
    }
}

@Composable
private fun SidebarItem(target: TopLevel, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (selected) AccentOrange else TextSecondary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(Shapes.chip)
            .background(
                when {
                    selected -> AccentOrange.copy(alpha = 0.16f)
                    hovered -> GlassFill
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconOf(target), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            target.label,
            color = if (selected) AccentOrange else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

private fun iconOf(target: TopLevel) = when (target) {
    TopLevel.Home -> Icons.Filled.Home
    TopLevel.Recommendations -> Icons.Filled.AutoAwesome
    TopLevel.Cinema -> Icons.Filled.Movie
    TopLevel.Cartoons -> Icons.Filled.EmojiEmotions
    TopLevel.NewEpisodes -> Icons.Filled.NewReleases
    TopLevel.Schedule -> Icons.Filled.CalendarMonth
    TopLevel.Favorites -> Icons.Filled.Favorite
    TopLevel.History -> Icons.Filled.History
    TopLevel.Stats -> Icons.Filled.Insights
    TopLevel.Settings -> Icons.Filled.Settings
}

private fun screenOf(target: TopLevel): Screen = when (target) {
    TopLevel.Home -> Screen.Home
    TopLevel.Recommendations -> Screen.Recommendations
    TopLevel.Cinema -> Screen.Cinema
    TopLevel.Cartoons -> Screen.Cartoons
    TopLevel.NewEpisodes -> Screen.NewEpisodes
    TopLevel.Schedule -> Screen.Schedule
    TopLevel.Favorites -> Screen.Favorites
    TopLevel.History -> Screen.History
    TopLevel.Stats -> Screen.Stats
    TopLevel.Settings -> Screen.Settings
}

/** Ширина боковой навигации: подписи вроде «Рекомендации» влезают в одну строку
 *  целиком — на 152 dp они обрывались на «Рекомендац». */
private val SIDEBAR_WIDTH = 184.dp

/** Высота полосы вкладок. Вкладки занимают её целиком — так они «сидят» на
 *  содержимом, а не плавают в пустой полосе. */
private val TAB_STRIP_HEIGHT = 34.dp

/**
 * Браузерная полоса вкладок.
 *
 * Приём тот же, что в настоящем браузере: полоса ТЕМНЕЕ страницы, а активная вкладка
 * окрашена в цвет страницы и потому читается как её продолжение. Раньше и полоса, и
 * страница были одного цвета [Surface1], а вкладки — светлее обеих: неактивные лезли
 * в глаза наравне с активной, и отличить их можно было только по цвету текста.
 */
@Composable
internal fun TitleTabBar(nav: NavController) {
    val listState = rememberLazyListState()
    LaunchedEffect(nav.activeTabId) {
        val index = nav.tabs.indexOfFirst { it.anime.id == nav.activeTabId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    Column(Modifier.fillMaxWidth().background(TabStripBackground)) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(TAB_STRIP_HEIGHT).padding(start = 6.dp)
                .dragScroll(listState).horizontalMouseWheel(listState),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Вкладка, в которой сейчас идёт серия: маленький эквалайзер перед
            // названием — как значок звука у вкладок браузера, только в цвете акцента.
            val playingId = nav.playerSession?.anime?.id
            items(nav.tabs, key = { it.anime.id }) { tab ->
                TitleTabItem(
                    tab = tab,
                    active = nav.activeTabId == tab.anime.id,
                    playback = when {
                        playingId != tab.anime.id -> TabPlayback.NONE
                        com.aniblaze.desktop.player.PipBridge.playing -> TabPlayback.PLAYING
                        else -> TabPlayback.PAUSED
                    },
                    onSelect = { nav.selectTab(tab.anime.id) },
                    onClose = { nav.closeTab(tab.anime.id) },
                    onCloseOthers = if (nav.tabs.size > 1) ({ nav.closeOtherTabs(tab.anime.id) }) else null,
                    onCloseRight = if (nav.tabs.lastOrNull()?.anime?.id != tab.anime.id) ({ nav.closeTabsToRight(tab.anime.id) }) else null,
                    // Появление и закрытие — плавные; соседи разъезжаются, а не
                    // прыгают. Ключ у items уже есть, без него анимация не работает.
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(160),
                        placementSpec = tween(200),
                        fadeOutSpec = tween(120),
                    ),
                )
            }
        }
        // Волосок под полосой: без него активная вкладка сливается со страницей
        // настолько, что полоса теряет край.
        Box(Modifier.fillMaxWidth().height(1.dp).background(TabStripDivider))
    }
}

/** Что происходит с серией во вкладке: ничего, играет, на паузе. */
internal enum class TabPlayback { NONE, PLAYING, PAUSED }

/**
 * Эквалайзер во вкладке: три столбика, каждый со своим ритмом, в цвете акцента.
 * На паузе столбики стоят низко и тускло — вкладка всё ещё находится, но не
 * «шумит». Размер — под высоту строки вкладки, чтобы не сдвигать название.
 */
@Composable
private fun TabEqualizer(playback: TabPlayback, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "tabEq")
    // Три разных периода — иначе столбики ходят строем и выглядят как гифка.
    val periods = intArrayOf(560, 740, 620)
    val phases = floatArrayOf(0f, 0.35f, 0.7f)
    val heights: List<androidx.compose.runtime.State<Float>> = periods.mapIndexed { i, period ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(period, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset((period * phases[i]).toInt()),
            ),
            label = "bar$i",
        )
    }
    val color = AccentOrange
    val playing = playback == TabPlayback.PLAYING
    androidx.compose.foundation.Canvas(modifier.size(width = 12.dp, height = 12.dp)) {
        val barWidth = 2.5.dp.toPx()
        val gap = 1.75.dp.toPx()
        val radius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
        heights.forEachIndexed { i, h ->
            // На паузе — ровные короткие столбики, без движения.
            val level = if (playing) h.value else 0.3f
            val barHeight = size.height * level
            drawRoundRect(
                color = if (playing) color else color.copy(alpha = 0.55f),
                topLeft = androidx.compose.ui.geometry.Offset(i * (barWidth + gap), size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun TitleTabItem(
    tab: TitleTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    playback: TabPlayback = TabPlayback.NONE,
    /** Правый клик: «закрыть остальные» / «закрыть справа»; null — пункта нет. */
    onCloseOthers: (() -> Unit)? = null,
    onCloseRight: (() -> Unit)? = null,
) {
    // Меню по правой кнопке — как у вкладок браузера. Пункты собираются из того,
    // что имеет смысл: у единственной вкладки нечего закрывать «остальные».
    val menu = buildList {
        add(ContextMenuItem("Закрыть") { onClose() })
        onCloseOthers?.let { add(ContextMenuItem("Закрыть остальные") { it() }) }
        onCloseRight?.let { add(ContextMenuItem("Закрыть справа") { it() }) }
    }
    ContextMenuArea(items = { menu }) {
    TitleTabBody(tab, active, onSelect, onClose, modifier, playback)
    }
}

@Composable
private fun TitleTabBody(
    tab: TitleTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    playback: TabPlayback = TabPlayback.NONE,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)
    // Одна кривая на всё приложение (см. MotionEasing) — движения ощущаются как
    // части одной системы, а не как набор разных анимаций.
    val fill by animateColorAsState(
        when {
            active -> Surface1
            pressed -> TabPressedFill
            hovered -> TabHoverFill
            else -> Color.Transparent
        },
        tween(140, easing = MotionEasing),
        label = "tabFill",
    )
    val label by animateColorAsState(
        when {
            active -> AccentOrange
            hovered -> TextPrimary
            else -> TextSecondary
        },
        tween(140, easing = MotionEasing),
        label = "tabLabel",
    )
    Row(
        modifier
            .padding(end = 2.dp)
            .widthIn(min = 120.dp, max = 240.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                PlayerDiagnostics.log("app.tab.select", "tabId=${tab.anime.id}; active=$active")
                onSelect()
            }
            .padding(start = 11.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playback != TabPlayback.NONE) {
            TabEqualizer(playback, Modifier.padding(end = 7.dp))
        }
        Box(Modifier.weight(1f)) {
            Text(
                tab.anime.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = label,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        // Крестик появляется только под курсором и у активной вкладки, но место под
        // него держится ВСЕГДА: иначе название дёргалось бы при каждом наведении.
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (hovered || active) {
                val closeInteraction = remember { MutableInteractionSource() }
                val closeHovered by closeInteraction.collectIsHoveredAsState()
                Box(
                    Modifier.size(18.dp).clip(Shapes.pill)
                        .background(if (closeHovered) GlassFillStrong else Color.Transparent)
                        .hoverable(closeInteraction)
                        .clickable(interactionSource = closeInteraction, indication = null) {
                            PlayerDiagnostics.log("app.tab.close", "tabId=${tab.anime.id}")
                            onClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Закрыть вкладку",
                        tint = if (closeHovered) TextPrimary else TextSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}
