package com.dhava.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dhava.feature.feed.FeedScreen
import com.dhava.feature.profile.ProfileScreen
import com.dhava.feature.record.RecordScreen
import com.dhava.feature.segments.SegmentsScreen

/** Top-level bottom-navigation destinations. */
private enum class DhavaDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Record("record", "Record", Icons.Filled.PlayArrow),
    Segments("segments", "Segments", Icons.Filled.Place),
    Feed("feed", "Feed", Icons.AutoMirrored.Filled.List),
    Profile("profile", "Profile", Icons.Filled.Person),
}

/** App scaffold: bottom navigation bar plus the navigation host. */
@Composable
fun DhavaApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
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
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = DhavaDestination.Record.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(DhavaDestination.Record.route) { RecordScreen() }
            composable(DhavaDestination.Segments.route) { SegmentsScreen() }
            composable(DhavaDestination.Feed.route) { FeedScreen() }
            composable(DhavaDestination.Profile.route) { ProfileScreen() }
        }
    }
}
