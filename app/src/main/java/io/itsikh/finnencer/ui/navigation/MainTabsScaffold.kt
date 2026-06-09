package io.itsikh.finnencer.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.data.repo.QueueRepository
import io.itsikh.finnencer.ui.screens.earnings.EarningsScreen
import io.itsikh.finnencer.ui.screens.podcast.PodcastLibraryScreen
import io.itsikh.finnencer.ui.screens.queue.QueueScreen
import io.itsikh.finnencer.ui.screens.settings.SettingsScreen
import io.itsikh.finnencer.ui.screens.tasks.TasksScreen
import io.itsikh.finnencer.ui.screens.watchlist.WatchlistScreen
import io.itsikh.finnencer.ui.theme.FinnencerColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Top-level destinations rendered as bottom tabs.
 *
 * Each tab keeps its own backstack via [NavHost]; switching tabs
 * does not unwind the others. The detail screens (article, podcast
 * player, report viewer, ticker feed, etc.) are pushed at the
 * outer-graph level so they cover the tab bar — see [AppNavHost].
 */
private enum class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    WATCHLIST("tab/watchlist", "Stocks", Icons.AutoMirrored.Filled.ShowChart),
    TASKS("tab/tasks", "Tasks", Icons.AutoMirrored.Filled.Assignment),
    EARNINGS("tab/earnings", "Earnings", Icons.AutoMirrored.Filled.EventNote),
    LIBRARY("tab/library", "Library", Icons.Filled.Headphones),
    QUEUE("tab/queue", "Queue", Icons.Filled.Bookmark),
    SETTINGS("tab/settings", "Settings", Icons.Filled.Settings),
}

/** Surface the two live badges (tasks running, queue todo) for the tab bar. */
@HiltViewModel
class MainTabsViewModel @Inject constructor(
    aiJobs: AiJobsRepository,
    queue: QueueRepository,
) : ViewModel() {
    val activeJobCount: StateFlow<Int> = aiJobs.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val queueCount: StateFlow<Int> = queue.observeIncompleteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/**
 * Hosts the six primary destinations behind a glass-styled bottom
 * NavigationBar. Tabs are wired to an internal NavHost so each one
 * preserves its own backstack/scroll/lifecycle. The outer
 * [outerNavController] is used to open detail routes (article,
 * report, podcast, ticker feed, etc.) that should cover the tab bar.
 */
@Composable
fun MainTabsScaffold(
    outerNavController: NavHostController,
) {
    val tabsNavController = rememberNavController()
    val backStackEntry by tabsNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val badgesVm: MainTabsViewModel = hiltViewModel()
    val activeJobs by badgesVm.activeJobCount.collectAsState()
    val queueCount by badgesVm.queueCount.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            GlassNavigationBar(
                items = MainTab.entries,
                currentRoute = currentRoute,
                badgeFor = { tab ->
                    when (tab) {
                        MainTab.TASKS -> activeJobs.takeIf { it > 0 }
                        MainTab.QUEUE -> queueCount.takeIf { it > 0 }
                        else -> null
                    }
                },
                onTabSelected = { tab ->
                    // Standard "pop to start, single-top" recipe so
                    // repeated taps don't stack the same destination
                    // and switching tabs restores the prior backstack
                    // for that tab rather than rebuilding it.
                    tabsNavController.navigate(tab.route) {
                        popUpTo(tabsNavController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = tabsNavController,
            startDestination = MainTab.WATCHLIST.route,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(MainTab.WATCHLIST.route) {
                WatchlistScreen(
                    onOpenTickerFeed = { symbol ->
                        outerNavController.navigate("ticker/$symbol")
                    },
                )
            }
            composable(MainTab.TASKS.route) {
                TasksScreen(
                    onOpenPodcast = { id -> outerNavController.navigate("podcast/$id") },
                    onOpenReader = { outerNavController.navigate("reader") },
                    onOpenReport = { id -> outerNavController.navigate("report/$id") },
                    onOpenTaskDetail = { jobId -> outerNavController.navigate("task/$jobId") },
                )
            }
            composable(MainTab.EARNINGS.route) {
                EarningsScreen(
                    onOpenReport = { id -> outerNavController.navigate("report/$id") },
                )
            }
            composable(MainTab.LIBRARY.route) {
                PodcastLibraryScreen(
                    onOpenPodcast = { id -> outerNavController.navigate("podcast/$id?from=library") },
                )
            }
            composable(MainTab.QUEUE.route) {
                QueueScreen(
                    onOpenArticle = { id -> outerNavController.navigate("article/$id") },
                    onOpenReport = { id -> outerNavController.navigate("report/$id") },
                    // Preserve the "play-through" semantics: a launch
                    // from the Queue tab tells the player it can
                    // auto-advance through queued podcasts.
                    onOpenPodcast = { id -> outerNavController.navigate("podcast/$id?from=queue") },
                    onOpenTasks = {
                        tabsNavController.navigate(MainTab.TASKS.route) {
                            popUpTo(tabsNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(MainTab.SETTINGS.route) {
                SettingsScreen(
                    onOpenBugReport = { mode ->
                        outerNavController.navigate("bug_report/${mode.name}")
                    },
                    onOpenKeys = { outerNavController.navigate("keys") },
                    onOpenCost = { outerNavController.navigate("cost") },
                    onOpenAiPrefs = { outerNavController.navigate("ai_prefs") },
                    onOpenAiPrompts = { outerNavController.navigate("ai_prompts") },
                    onOpenReleaseNotes = { outerNavController.navigate("release_notes") },
                )
            }
        }
    }
}

@Composable
private fun GlassNavigationBar(
    items: List<MainTab>,
    currentRoute: String?,
    badgeFor: (MainTab) -> Int?,
    onTabSelected: (MainTab) -> Unit,
) {
    // A thin glass plate that floats above the radial canvas: faint
    // top hairline + the same alpha-white surface tokens that
    // GlassCard uses, so the tab strip reads as part of the Glass
    // Modern surface family.
    Box(
        modifier = Modifier
            .background(FinnencerColors.SurfaceGlass)
            .border(
                width = 1.dp,
                color = FinnencerColors.SurfaceBorder,
                shape = RoundedCornerShape(0.dp),
            ),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            items.forEach { tab ->
                val selected = currentRoute?.hierarchyContainsRoute(tab.route) == true
                val badgeCount = badgeFor(tab)
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        if (badgeCount != null) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = if (tab == MainTab.TASKS) {
                                            FinnencerColors.Coral
                                        } else {
                                            FinnencerColors.Violet
                                        },
                                        contentColor = FinnencerColors.TextOnAccent,
                                    ) {
                                        Text(
                                            text = badgeCount.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                },
                            ) {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = tab.label)
                        }
                    },
                    label = {
                        // Single line, never wrapping — with 6 tabs the
                        // per-item slot is too narrow for "Earnings"/
                        // "Settings" at the default label size, so they were
                        // wrapping to "Earning\ns" (#71). Pin to one line and
                        // shrink slightly so the full word fits the slot.
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = FinnencerColors.Violet,
                        selectedTextColor = FinnencerColors.TextPrimary,
                        unselectedIconColor = FinnencerColors.TextSecondary,
                        unselectedTextColor = FinnencerColors.TextTertiary,
                        indicatorColor = FinnencerColors.Violet.copy(alpha = 0.18f),
                    ),
                )
            }
        }
    }
}

/**
 * True when [route] is in the current destination's NavGraph
 * hierarchy. Lets a nested-graph child still highlight its parent
 * tab if we ever introduce sub-routes underneath a tab.
 */
private fun String.hierarchyContainsRoute(route: String): Boolean = this == route

@Suppress("unused")
private fun androidx.navigation.NavDestination.containsRoute(route: String): Boolean =
    hierarchy.any { it.route == route }
