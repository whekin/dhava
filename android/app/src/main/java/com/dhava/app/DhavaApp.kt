package com.dhava.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dhava.feature.activity.ActivityDetailScreen
import com.dhava.feature.record.RecordScreen
import com.dhava.feature.record.ActivitiesScreen
import com.dhava.feature.record.SaveRecordingScreen

/** Top-level bottom-navigation destinations. */
private enum class DhavaDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Record("record", "Record", Icons.Filled.PlayArrow),
    Activities("activities", "Activities", Icons.AutoMirrored.Filled.List),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

/** App scaffold: bottom navigation bar plus the navigation host. */
@Composable
fun DhavaApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    var recordImmersive by remember { mutableStateOf(false) }
    val isTopLevelDestination = DhavaDestination.entries.any { destination ->
        currentDestination?.hierarchy?.any { it.route == destination.route } == true
    }
    val showBottomBar = isTopLevelDestination && !recordImmersive

    Scaffold(
        bottomBar = {
          if (showBottomBar) {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                DhavaDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Keep a single copy of each destination and
                                // restore its state when reselected.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
          }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = DhavaDestination.Record.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(DhavaDestination.Record.route) {
                RecordScreen(
                    onImmersiveChanged = { recordImmersive = it },
                    onSaveRecovered = { id -> navController.navigate("save/$id") },
                )
            }
            composable(DhavaDestination.Activities.route) {
                ActivitiesScreen(
                    onOpenActivity = { id -> navController.navigate("activity/$id") },
                    onFinishSaving = { id -> navController.navigate("save/$id") },
                )
            }
            composable(DhavaDestination.Settings.route) { SettingsScreen() }
            // Detail screen for one recorded activity; pushed on top of the
            // Record tab, so system/app back both return to the list.
            composable(
                route = "activity/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ActivityDetailScreen(
                    recordingId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "save/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                SaveRecordingScreen(
                    recordingId = entry.arguments?.getString("id").orEmpty(),
                    onFinished = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
