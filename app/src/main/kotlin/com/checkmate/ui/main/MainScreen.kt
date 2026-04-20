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
import androidx.navigation.compose.*
import com.checkmate.ui.home.HomeScreen
import com.checkmate.ui.planner.PlannerScreen
import com.checkmate.ui.stats.StatsScreen
import com.checkmate.ui.settings.SettingsScreen
import com.checkmate.ui.theme.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home     : Screen("home",     "Today",    Icons.Default.CheckCircle)
    object Planner  : Screen("planner",  "Plan",     Icons.Default.CalendarMonth)
    object Stats    : Screen("stats",    "Stats",    Icons.Default.BarChart)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val items = listOf(Screen.Home, Screen.Planner, Screen.Stats, Screen.Settings)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
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
    ) { padding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route)     { HomeScreen(navController) }
            composable(Screen.Planner.route)  { PlannerScreen(navController) }
            composable(Screen.Stats.route)    { StatsScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
