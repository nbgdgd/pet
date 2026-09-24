package com.aniblaze.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aniblaze.detail.DetailScreen
import com.aniblaze.favorites.FavoritesScreen
import com.aniblaze.featureplayer.PlayerScreen
import com.aniblaze.history.HistoryScreen
import com.aniblaze.home.HomeScreen
import com.aniblaze.search.CinemaScreen
import com.aniblaze.search.CinemaWebScreen
import com.aniblaze.search.SearchScreen
import com.aniblaze.app.newepisodes.NewEpisodesScreen
import com.aniblaze.app.schedule.ScheduleScreen
import com.aniblaze.app.stats.StatsScreen
import com.aniblaze.app.downloads.TorrentDownloadsScreen
import com.aniblaze.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    playInterstitial: (onContinue: () -> Unit) -> Unit = { it() },
    // Set when the app was opened by tapping a "new episode" notification: navigate
    // straight to that title instead of dropping the user on the home screen.
    openContentId: String? = null,
    onContentIdConsumed: () -> Unit = {},
) {
    LaunchedEffect(openContentId) {
        val id = openContentId ?: return@LaunchedEffect
        navController.navigate(Routes.detail(id))
        onContentIdConsumed()
    }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Панель видна и на экранах из «Ещё»: иначе с расписания или статистики
    // выбраться можно было бы только кнопкой «назад».
    val showBottomBar = currentRoute in TopLevelDestination.barVisibleRoutes
    var moreOpen by remember { mutableStateOf(false) }

    // Bumped each time the user re-taps the tab they're already on, to refresh it.
    val refreshTicks = remember { mutableStateMapOf<String, Int>() }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { dest ->
                        if (dest.route == currentRoute) {
                            refreshTicks[dest.route] = (refreshTicks[dest.route] ?: 0) + 1
                        } else {
                            navController.navigate(dest.route) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onMore = { moreOpen = true },
                )
            }
        },
    ) { padding ->
        if (moreOpen) {
            MoreSheet(
                onDismiss = { moreOpen = false },
                onSelect = { dest ->
                    moreOpen = false
                    navController.navigate(dest.route) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(
                Routes.HOME,
                enterTransition = { fadeIn(tween(250)) },
            ) {
                HomeScreen(
                    onAnimeClick = { navController.navigate(Routes.detail(it)) },
                    onResume = { contentId, segmentId ->
                        playInterstitial { navController.navigate(Routes.player(contentId, segmentId)) }
                    },
                    refreshTick = refreshTicks[Routes.HOME] ?: 0,
                )
            }
            composable(
                Routes.CINEMA,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                CinemaScreen(
                    // TMDB/Lampa ids must go through Detail -> Media3. The legacy
                    // cinema WebView only understands Kinogo page URLs and always
                    // fails for ids such as "tmdb:603" or "tmdbtv:1399".
                    onAnimeClick = { navController.navigate(Routes.detail(it)) },
                    refreshTick = refreshTicks[Routes.CINEMA] ?: 0,
                )
            }
            composable(
                Routes.SEARCH,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                SearchScreen(
                    onAnimeClick = { navController.navigate(Routes.detail(it)) },
                    refreshTick = refreshTicks[Routes.SEARCH] ?: 0,
                )
            }
            composable(
                Routes.FAVORITES,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                FavoritesScreen(
                    onAnimeClick = { navController.navigate(Routes.detail(it)) },
                    refreshTick = refreshTicks[Routes.FAVORITES] ?: 0,
                )
            }
            composable(
                Routes.HISTORY,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                HistoryScreen(
                    onAnimeClick = { navController.navigate(Routes.detail(it)) },
                    refreshTick = refreshTicks[Routes.HISTORY] ?: 0,
                )
            }
            composable(
                Routes.SETTINGS,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                SettingsScreen()
            }

            composable(
                Routes.SCHEDULE,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                ScheduleScreen(onAnimeClick = { navController.navigate(Routes.detail(it)) })
            }
            composable(
                Routes.NEW_EPISODES,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                NewEpisodesScreen(onAnimeClick = { navController.navigate(Routes.detail(it)) })
            }
            composable(
                Routes.STATS,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                StatsScreen(onAnimeClick = { navController.navigate(Routes.detail(it)) })
            }
            composable(
                Routes.DOWNLOADS,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 } },
            ) {
                TorrentDownloadsScreen()
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("contentId") { type = NavType.StringType }),
                enterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(350)) +
                            fadeIn(tween(350))
                },
                exitTransition = { fadeOut(tween(250)) },
            ) {
                DetailScreen(
                    onBack = { navController.popBackStack() },
                    onPlaySegment = { contentId, segmentId, streamMode ->
                        playInterstitial { navController.navigate(Routes.player(contentId, segmentId, streamMode)) }
                    },
                    onAnimeClick = { navController.navigate(Routes.detail(it)) },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("contentId") { type = NavType.StringType },
                    navArgument("segmentId") { type = NavType.StringType },
                    navArgument("streamMode") {
                        type = NavType.StringType
                        defaultValue = "auto"
                    },
                ),
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(200)) },
            ) {
                PlayerScreen(
                    onBack = { navController.popBackStack() },
                    // «Случайное аниме после последней серии»: заменить текущий
                    // плеер плеером выбранного тайтла ("auto" → первая серия).
                    onOpenTitle = { contentId ->
                        navController.navigate(Routes.player(contentId, "auto")) {
                            popUpTo(Routes.PLAYER) { inclusive = true }
                        }
                    },
                )
            }

            composable(
                route = Routes.CINEMA_WEB,
                arguments = listOf(navArgument("pageUrl") { type = NavType.StringType }),
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(200)) },
            ) {
                CinemaWebScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
