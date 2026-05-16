package io.itsikh.finnencer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.itsikh.finnencer.bugreport.ScreenshotHolder
import io.itsikh.finnencer.ui.components.DebugOverlayViewModel
import io.itsikh.finnencer.ui.components.FloatingBugButton
import io.itsikh.finnencer.ui.screens.bugreport.BugReportScreen
import io.itsikh.finnencer.ui.screens.bugreport.ReportMode
import io.itsikh.finnencer.ui.screens.home.HomeScreen
import io.itsikh.finnencer.ui.screens.keys.ApiKeysScreen
import io.itsikh.finnencer.ui.screens.keys.QrScanScreen
import io.itsikh.finnencer.ui.screens.keys.QrShareScreen
import io.itsikh.finnencer.ui.screens.article.ArticleDetailScreen
import io.itsikh.finnencer.ui.screens.cost.CostMeterScreen
import io.itsikh.finnencer.ui.screens.earnings.EarningsScreen
import io.itsikh.finnencer.ui.screens.earnings.ReportViewerScreen
import io.itsikh.finnencer.ui.screens.feed.TickerFeedScreen
import io.itsikh.finnencer.ui.screens.podcast.PodcastFromReportScreen
import io.itsikh.finnencer.ui.screens.podcast.PodcastLibraryScreen
import io.itsikh.finnencer.ui.screens.podcast.PodcastPlayerScreen
import io.itsikh.finnencer.ui.screens.watchlist.WatchlistScreen
import io.itsikh.finnencer.ui.screens.settings.SettingsScreen

/**
 * Root navigation graph for the app.
 *
 * Uses Jetpack Compose Navigation with a [NavHost] and string-based route identifiers.
 * [MainActivity] calls this composable as the sole content of [setContent].
 *
 * ## Routes
 * | Route | Screen | Notes |
 * |-------|--------|-------|
 * | `home` | [HomeScreen] | Start destination — replace with your main screen |
 * | `settings` | [SettingsScreen] | App settings, debug tools, backup/restore |
 * | `bug_report/{mode}` | [BugReportScreen] | Bug or feedback form; `mode` is a [ReportMode] name |
 *
 * ## Floating bug button
 * When admin mode is on and "Bug Report Button" is enabled in Settings → Debug, a draggable
 * [FloatingBugButton] overlays every screen. Tapping it captures the current screen, stores
 * the screenshot in [ScreenshotHolder], and navigates directly to the bug report form.
 *
 * ## Adding new screens
 * 1. Create your composable screen in `ui/screens/your_feature/YourScreen.kt`.
 * 2. Add a `composable("your_route") { YourScreen(...) }` entry below.
 * 3. Navigate to it from any other screen using `navController.navigate("your_route")`.
 *    Pass the `navController` down as a lambda parameter (e.g. `onNavigate = { navController.navigate(...) }`)
 *    rather than passing `navController` itself, to keep screens decoupled from navigation.
 *
 * ## Adding arguments
 * For routes with parameters (e.g. `"detail/{id}"`), use the
 * [NavHost] argument DSL or typed navigation with the `navigation-compose` serialization APIs.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val overlayVm: DebugOverlayViewModel = hiltViewModel()
    val showBugButton by overlayVm.showBugButton.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "watchlist") {
            composable("watchlist") {
                WatchlistScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenEarnings = { navController.navigate("earnings") },
                    onOpenPodcasts = { navController.navigate("podcasts") },
                    onOpenTickerFeed = { symbol ->
                        navController.navigate("ticker/$symbol")
                    },
                )
            }
            composable("cost") {
                CostMeterScreen(onBack = { navController.popBackStack() })
            }
            composable("earnings") {
                EarningsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReport = { id -> navController.navigate("report/$id") },
                )
            }
            composable("report/{reportId}") {
                ReportViewerScreen(
                    onBack = { navController.popBackStack() },
                    onListen = { id -> navController.navigate("podcast/from-report/$id") },
                )
            }
            composable("podcast/from-report/{reportId}") {
                PodcastFromReportScreen(
                    onReady = { podcastId ->
                        navController.navigate("podcast/$podcastId") {
                            popUpTo("podcast/from-report/{reportId}") { inclusive = true }
                        }
                    },
                    onFailed = { navController.popBackStack() },
                )
            }
            composable("podcast/{podcastId}") {
                PodcastPlayerScreen(onBack = { navController.popBackStack() })
            }
            composable("podcasts") {
                PodcastLibraryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPodcast = { id -> navController.navigate("podcast/$id") },
                )
            }
            composable("ticker/{symbol}") {
                TickerFeedScreen(
                    onBack = { navController.popBackStack() },
                    onOpenArticle = { articleId -> navController.navigate("article/$articleId") },
                    onOpenReport = { reportId -> navController.navigate("report/$reportId") },
                )
            }
            composable("article/{articleId}") {
                ArticleDetailScreen(onBack = { navController.popBackStack() })
            }
            composable("home") {
                HomeScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenKeys = { navController.navigate("keys") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBugReport = { mode ->
                        navController.navigate("bug_report/${mode.name}")
                    },
                    onOpenKeys = { navController.navigate("keys") },
                    onOpenCost = { navController.navigate("cost") },
                )
            }
            composable("keys") {
                ApiKeysScreen(
                    onBack = { navController.popBackStack() },
                    onOpenScan = { navController.navigate("keys/scan") },
                    onOpenShare = { navController.navigate("keys/share") },
                )
            }
            composable("keys/scan") {
                QrScanScreen(
                    onBack = { navController.popBackStack() },
                    onImported = { navController.popBackStack() },
                )
            }
            composable("keys/share") {
                QrShareScreen(onBack = { navController.popBackStack() })
            }
            composable("bug_report/{mode}") { backStackEntry ->
                val modeName = backStackEntry.arguments?.getString("mode")
                val mode = modeName?.let { runCatching { ReportMode.valueOf(it) }.getOrNull() }
                    ?: ReportMode.BUG_REPORT
                BugReportScreen(
                    mode = mode,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        FloatingBugButton(
            visible = showBugButton,
            onScreenshotCaptured = { bitmap ->
                ScreenshotHolder.store(bitmap)
                navController.navigate("bug_report/BUG_REPORT")
            }
        )
    }
}
