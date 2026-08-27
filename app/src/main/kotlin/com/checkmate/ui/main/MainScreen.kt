package com.checkmate.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.checkmate.ui.home.HomeScreen
import com.checkmate.ui.home.HomeViewModel
import com.checkmate.ui.mentor.MentorScreen
import com.checkmate.ui.negotiation.NegotiationScreen
import com.checkmate.ui.planner.PlannerScreen
import com.checkmate.ui.planner.DailyCheckInScreen
import com.checkmate.ui.planner.CoachingPlannerScreen
import com.checkmate.ui.consultation.ConsultationScreen
import com.checkmate.ui.stats.StatsScreen
import com.checkmate.ui.settings.SettingsScreen
import com.checkmate.ui.testresults.TestResultsScreen
import com.checkmate.ui.testresults.TestmateWebScreen
import com.checkmate.ui.theme.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home     : Screen("home",     "Today",   Icons.Default.CheckCircle)
    object Planner  : Screen("planner",  "Plan",    Icons.Default.CalendarMonth)
    object Mentor   : Screen("mentor",   "Mentor",  Icons.Default.Psychology)
    object Stats    : Screen("stats",    "Stats",   Icons.Default.BarChart)
    object Settings : Screen("settings", "Settings",Icons.Default.Settings)
}

/**
 * Proactive Execution Engine — Step 10 (Blueprint §16 "Talk to Checkmate"). Carries the
 * three extras MainActivity read off the launching Intent (originally attached by
 * InterventionNotifier.talkPendingIntent) down into this composable as a one-shot
 * navigation event, rather than MainScreen reaching back up into Activity/Intent state
 * itself.
 */
data class PendingNegotiation(val transactionId: String, val taskId: String, val lateMinutes: Int)

@Composable
fun MainScreen(homeViewModel: HomeViewModel, pendingNegotiation: PendingNegotiation? = null) {
    val navController = rememberNavController()
    val items = listOf(Screen.Home, Screen.Planner, Screen.Mentor, Screen.Stats, Screen.Settings)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavRoutes = items.map { it.route }
    val showBottomNav   = currentRoute in bottomNavRoutes

    // Fires once per non-null pendingNegotiation value — MainActivity only supplies a
    // non-null value on the onCreate() call that actually consumed a fresh "Talk to
    // Checkmate" intent (it removeExtra()s immediately after reading), so this won't
    // re-fire on ordinary recomposition.
    LaunchedEffect(pendingNegotiation) {
        pendingNegotiation?.let { p ->
            navController.navigate("negotiation/${p.transactionId}/${p.taskId}/${p.lateMinutes}")
        }
    }

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            if (showBottomNav) {
                NavigationBar(
                    containerColor = BgCard,
                    tonalElevation = 0.dp
                ) {
                    items.forEach { screen ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick  = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            },
                            icon  = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = AccentGreen,
                                selectedTextColor   = AccentGreen,
                                unselectedIconColor = White30,
                                unselectedTextColor = White30,
                                indicatorColor      = White10
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(padding)
        ) {
            // Main tabs
            composable(Screen.Home.route)     { HomeScreen(navController, vm = homeViewModel) }
            composable(Screen.Planner.route)  { PlannerScreen(navController) }
            composable(Screen.Mentor.route)   { MentorScreen() }
            composable(Screen.Stats.route)    { StatsScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen() }

            // Blueprint 6 new routes
            composable("consultation")   { ConsultationScreen(navController) }
            composable("daily_checkin")  { DailyCheckInScreen(navController) }
            composable("coaching_plan")  { CoachingPlannerScreen(navController) }

            // Testmate integration (Phase 6)
            composable("test_results")  { TestResultsScreen(navController) }
            composable("test_web")      { TestmateWebScreen(navController) }
            // P0b: "Take repair test" from a TaskCard — opens straight at the
            // targeted-test session GapTaskManager.createTargetedTestIfNeeded()
            // already created, instead of landing on the bare Testmate homepage
            // and making the student find it themselves. sessionId is a Testmate
            // uuid (no '/' characters), so plain path-segment interpolation is
            // safe here, same reasoning as the negotiation route below.
            composable(
                route = "test_web/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { backStackEntry ->
                TestmateWebScreen(navController, backStackEntry.arguments?.getString("sessionId"))
            }

            // Proactive Execution Engine — Step 10 (Blueprint §16): the conversational
            // negotiation screen. transactionId/taskId are UUID strings (no '/' characters),
            // so plain path-segment interpolation above is safe without URL-encoding.
            composable(
                route = "negotiation/{transactionId}/{taskId}/{lateMinutes}",
                arguments = listOf(
                    navArgument("transactionId") { type = NavType.StringType },
                    navArgument("taskId") { type = NavType.StringType },
                    navArgument("lateMinutes") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                val args = backStackEntry.arguments
                NegotiationScreen(
                    navController = navController,
                    transactionId = args?.getString("transactionId").orEmpty(),
                    taskId        = args?.getString("taskId").orEmpty(),
                    lateMinutes   = args?.getInt("lateMinutes") ?: 0
                )
            }
        }
    }
}
