package com.nakvali.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nakvali.feature.activity.ActivityDetailScreen
import com.nakvali.feature.profile.ProfileScreen
import com.nakvali.feature.profile.ProfileUiState
import com.nakvali.feature.record.RecordScreen
import com.nakvali.feature.record.ActivitiesScreen
import com.nakvali.feature.record.SaveRecordingScreen
import com.nakvali.feature.segments.SegmentDetailScreen
import com.nakvali.feature.segments.SegmentCandidatesScreen
import com.nakvali.feature.segments.SegmentEditorScreen
import com.nakvali.feature.segments.SegmentEditorSource
import com.nakvali.feature.segments.SegmentsScreen

/** Top-level bottom-navigation destinations. */
private enum class NakvaliDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Record("record", "Record", Icons.Filled.PlayArrow),
    Activities("activities", "Activities", Icons.AutoMirrored.Filled.List),
    Segments("segments", "Segments", Icons.Filled.Timer),
    Profile("profile", "Profile", Icons.Filled.Person),
}

private const val SETTINGS_ROUTE = "settings"
private const val SEGMENT_CANDIDATES_ROUTE = "segment-candidates"
private const val SEGMENT_EDITOR_ROUTE = "segment-editor/{id}?start={start}&end={end}"

/** App scaffold: bottom navigation bar plus the navigation host. */
@Composable
fun NakvaliApp(
    openRecorderRequest: Long = 0L,
    profileState: ProfileUiState = ProfileUiState.Loading,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onRetryProfileSync: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    var recordImmersive by remember { mutableStateOf(false) }
    val isTopLevelDestination = NakvaliDestination.entries.any { destination ->
        currentDestination?.hierarchy?.any { it.route == destination.route } == true
    }
    val showBottomBar = isTopLevelDestination && !recordImmersive

    LaunchedEffect(openRecorderRequest) {
        if (openRecorderRequest > 0L) {
            navController.navigate(NakvaliDestination.Record.route) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
          if (showBottomBar) {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                NakvaliDestination.entries.forEach { destination ->
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
            startDestination = NakvaliDestination.Record.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NakvaliDestination.Record.route) {
                RecordScreen(
                    onImmersiveChanged = { recordImmersive = it },
                    onSaveRecovered = { id -> navController.navigate("save/$id") },
                )
            }
            composable(NakvaliDestination.Activities.route) {
                ActivitiesScreen(
                    onOpenActivity = { id -> navController.navigate("activity/$id") },
                    onFinishSaving = { id -> navController.navigate("save/$id") },
                    onStartRecording = {
                        navController.navigate(NakvaliDestination.Record.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(NakvaliDestination.Segments.route) {
                SegmentsScreen(
                    onOpenSegment = { id -> navController.navigate("segment/$id") },
                    onEditImportedTrace = { id ->
                        navController.navigate("segment-import-editor/$id")
                    },
                    onCreateSegment = { navController.navigate(SEGMENT_CANDIDATES_ROUTE) },
                )
            }
            composable(NakvaliDestination.Profile.route) {
                ProfileScreen(
                    state = profileState,
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
                    onRetrySync = onRetryProfileSync,
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                )
            }
            composable(SETTINGS_ROUTE) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            // Detail screen for one recorded activity; pushed on top of the
            // Record tab, so system/app back both return to the list.
            composable(
                route = "activity/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val recordingId = entry.arguments?.getString("id").orEmpty()
                ActivityDetailScreen(
                    recordingId = recordingId,
                    onBack = { navController.popBackStack() },
                    onCreateSegment = { navController.navigate("segment-editor/$recordingId") },
                    onOpenSegment = { id -> navController.navigate("segment/$id") },
                )
            }
            composable(
                route = "segment/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                SegmentDetailScreen(
                    segmentId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            // Authoring a segment from one ride. On success the editor is
            // replaced by the new segment so Back returns to the ride.
            composable(
                route = SEGMENT_EDITOR_ROUTE,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("start") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("end") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SegmentEditorScreen(
                    source = SegmentEditorSource.Ride(
                        id = entry.arguments?.getString("id").orEmpty(),
                        initialStartPosition = entry.arguments?.getString("start")?.toDoubleOrNull(),
                        initialEndPosition = entry.arguments?.getString("end")?.toDoubleOrNull(),
                    ),
                    onBack = { navController.popBackStack() },
                    onCreated = { segmentId ->
                        navController.navigate("segment/$segmentId") {
                            popUpTo(SEGMENT_EDITOR_ROUTE) { inclusive = true }
                        }
                    },
                )
            }
            composable(SEGMENT_CANDIDATES_ROUTE) {
                SegmentCandidatesScreen(
                    onBack = { navController.popBackStack() },
                    onReviewCandidate = { recordingId, start, end ->
                        navController.navigate(
                            "segment-editor/$recordingId?start=$start&end=$end",
                        )
                    },
                )
            }
            composable(
                route = "segment-import-editor/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                SegmentEditorScreen(
                    source = SegmentEditorSource.ImportedGpx(
                        entry.arguments?.getString("id").orEmpty(),
                    ),
                    onBack = { navController.popBackStack() },
                    onCreated = { segmentId ->
                        navController.navigate("segment/$segmentId") {
                            popUpTo("segment-import-editor/{id}") { inclusive = true }
                        }
                    },
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
