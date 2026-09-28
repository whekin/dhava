package com.nakvali.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.nakvali.core.ui.NakvaliLoading
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliPrimaryButton
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSecondaryButton
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliStatusTone
import com.nakvali.core.ui.NakvaliTextField
import com.nakvali.core.ui.NakvaliTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.recording.Bike
import com.nakvali.core.recording.BikeType

data class ProfileAccount(
    val displayName: String,
    val email: String,
    val avatarUrl: String,
    val emailVerified: Boolean,
)

sealed interface ProfileServerState {
    data object Syncing : ProfileServerState
    data object Synced : ProfileServerState
    data class Unavailable(val message: String) : ProfileServerState
    /** This build has no Nakvali server access; the account is identity only. */
    data object LocalOnly : ProfileServerState
}

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class SignedOut(
        val signingIn: Boolean = false,
        val error: String? = null,
        /** False in public builds, which cannot reach the Nakvali server. */
        val cloudAvailable: Boolean = true,
    ) : ProfileUiState

    data class SignedIn(
        val account: ProfileAccount,
        val server: ProfileServerState,
    ) : ProfileUiState
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetrySync: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel(),
) {
    val bikes by viewModel.bikes.collectAsState()
    val activeBikeId by viewModel.activeBikeId.collectAsState()

    ProfileContent(
        state = state,
        bikes = bikes,
        activeBikeId = activeBikeId,
        onSignIn = onSignIn,
        onSignOut = onSignOut,
        onRetrySync = onRetrySync,
        onOpenSettings = onOpenSettings,
        onAddBike = viewModel::addBike,
        onSelectBike = viewModel::selectBike,
        modifier = modifier,
        bikeyard = { BikeyardSection(viewModel) },
    )
}

@Composable
private fun ProfileContent(
    state: ProfileUiState,
    bikes: List<Bike>,
    activeBikeId: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetrySync: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddBike: (String, BikeType) -> Unit,
    onSelectBike: (String) -> Unit,
    modifier: Modifier = Modifier,
    bikeyard: @Composable () -> Unit = {},
) {
    var showAddBike by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.xLarge),
    ) {
        NakvaliScreenHeader(eyebrow = "Rider", title = "Profile")

        // Local-only public builds have nothing to sign in to; the card would
        // only advertise a server the app cannot reach.
        if (!(state is ProfileUiState.SignedOut && !state.cloudAvailable)) {
            Spacer(Modifier.height(NakvaliSpacing.xLarge))
            when (state) {
                ProfileUiState.Loading -> LoadingAccount()
                is ProfileUiState.SignedOut -> SignedOutAccount(state, onSignIn)
                is ProfileUiState.SignedIn -> SignedInAccount(state, onRetrySync, onSignOut)
            }
        }

        ProfileSection(title = "Connections") {
            bikeyard()
        }

        ProfileSection(
            title = "Bikes",
            action = {
                if (bikes.isNotEmpty()) {
                    TextButton(onClick = { showAddBike = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(NakvaliSpacing.small))
                        Text("Add")
                    }
                }
            },
        ) {
            Garage(
                bikes = bikes,
                activeBikeId = activeBikeId,
                onSelectBike = onSelectBike,
                onAddBike = { showAddBike = true },
            )
        }

        ProfileSection(title = "App") {
            SettingsCard(onOpenSettings)
        }

        Spacer(Modifier.height(NakvaliSpacing.large))
        Text(
            text = "Rides, bikes and raw sensor data stay on this phone.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(NakvaliSpacing.xLarge))
    }

    if (showAddBike) {
        AddBikeDialog(
            onDismiss = { showAddBike = false },
            onAdd = { name, type ->
                onAddBike(name, type)
                showAddBike = false
            },
        )
    }
}

@Composable
private fun ProfileSection(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(NakvaliSpacing.xLarge))
    // The label belongs to the card below it, so it sits close to it; the
    // row only grows when it carries an action.
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NakvaliSectionLabel(title)
        action?.invoke()
    }
    Spacer(Modifier.height(NakvaliSpacing.small))
    content()
}

@Composable
private fun LoadingAccount() {
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(NakvaliSpacing.xLarge),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NakvaliLoading(Modifier.size(24.dp))
            Column {
                Text("Restoring your session", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(NakvaliSpacing.xSmall))
                Text(
                    "Local rides are already available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SignedOutAccount(state: ProfileUiState.SignedOut, onSignIn: () -> Unit) {
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(NakvaliSpacing.xLarge)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RiderMark("N")
                Column(Modifier.weight(1f)) {
                    Text("Local rider", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(NakvaliSpacing.xSmall))
                    Text(
                        "Sign in to sync future PRs and segment results.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.error != null) {
                Spacer(Modifier.height(NakvaliSpacing.large))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = state.error,
                        modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.medium),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(NakvaliSpacing.xLarge))
            // Signing in is optional — the app records, analyses and stores
            // everything without an account. As a full-width filled button it
            // was the loudest thing in the app, shouting louder than adding the
            // bike a new rider actually needs. Secondary weight, sized to its
            // label rather than to the card.
            if (state.signingIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NakvaliLoading(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(NakvaliSpacing.medium))
                    Text(
                        "Opening Google…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                NakvaliSecondaryButton(text = "Continue with Google", onClick = onSignIn)
            }
        }
    }
}

@Composable
private fun SignedInAccount(
    state: ProfileUiState.SignedIn,
    onRetrySync: () -> Unit,
    onSignOut: () -> Unit,
) {
    val account = state.account
    val title = account.displayName.ifBlank {
        account.email.substringBefore('@').ifBlank { "Rider" }
    }
    var menu by remember { mutableStateOf(false) }

    NakvaliPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(NakvaliSpacing.xLarge)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RiderMark(title.firstOrNull()?.uppercase() ?: "N")
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (account.email.isNotBlank()) {
                        Spacer(Modifier.height(NakvaliSpacing.xSmall))
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Account options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (state.server is ProfileServerState.Unavailable) {
                            DropdownMenuItem(
                                text = { Text("Try sync again") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onRetrySync()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            },
                            onClick = {
                                menu = false
                                onSignOut()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(NakvaliSpacing.large))
            ServerStatus(state.server, onRetrySync)
        }
    }
}

/** One quiet line: the account is a convenience, never the app's headline. */
@Composable
private fun ServerStatus(server: ProfileServerState, onRetrySync: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (server) {
            ProfileServerState.Syncing -> NakvaliLoading(Modifier.size(18.dp))
            else -> Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = when (server) {
                    ProfileServerState.Synced -> MaterialTheme.colorScheme.primary
                    is ProfileServerState.Unavailable -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                },
            ) {}
        }
        Text(
            text = when (server) {
                ProfileServerState.Syncing -> "Connecting to Nakvali…"
                ProfileServerState.Synced -> "Google account · synced with Nakvali"
                ProfileServerState.LocalOnly -> "Google account · this build stays local"
                is ProfileServerState.Unavailable -> server.message
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (server is ProfileServerState.Unavailable) {
            TextButton(onClick = onRetrySync) { Text("Retry") }
        }
    }
}

@Composable
private fun Garage(
    bikes: List<Bike>,
    activeBikeId: String?,
    onSelectBike: (String) -> Unit,
    onAddBike: () -> Unit,
) {
    if (bikes.isEmpty()) {
        NakvaliPanel(Modifier.fillMaxWidth()) {
            // Same internal layout as the account card above — mark on the
            // left, text beside it. The two cards used to run on different
            // rules for no reason a reader could see.
            Column(modifier = Modifier.padding(NakvaliSpacing.xLarge)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PedalBike,
                                contentDescription = null,
                                Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Build your garage", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(NakvaliSpacing.xSmall))
                        Text(
                            "Add a bike once and it will be ready when you save a ride.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(NakvaliSpacing.xLarge))
                // The real task on this screen, so it gets the primary weight
                // that the optional sign-in used to take.
                NakvaliPrimaryButton(
                    text = "Add bike",
                    onClick = onAddBike,
                    icon = Icons.Filled.Add,
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small)) {
        bikes.forEach { bike ->
            BikeRow(
                bike = bike,
                active = bike.id == activeBikeId,
                onClick = { onSelectBike(bike.id) },
            )
        }
    }
}

@Composable
private fun BikeRow(bike: Bike, active: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.large),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PedalBike, contentDescription = null, Modifier.size(23.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = bike.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(NakvaliSpacing.xSmall))
                Text(
                    text = bike.type.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                NakvaliStatusPill(text = "Active", tone = NakvaliStatusTone.Live)
            }
        }
    }
}

@Composable
private fun SettingsCard(onOpenSettings: () -> Unit) {
    NakvaliPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
    ) {
        Row(
            modifier = Modifier.padding(NakvaliSpacing.large),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, contentDescription = null, Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(NakvaliSpacing.xSmall))
                Text(
                    "Recording, storage and backups",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBikeDialog(
    onDismiss: () -> Unit,
    onAdd: (String, BikeType) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(BikeType.FULL_SUS) }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Add bike") },
        text = {
            Column {
                NakvaliTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(NakvaliSpacing.medium))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small)) {
                    BikeType.entries.forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), type) },
                enabled = name.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RiderMark(initial: String) {
    Surface(
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initial, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Preview(name = "Signed out", showBackground = true, backgroundColor = 0xFF11100F)
@Composable
private fun SignedOutPreview() {
    NakvaliTheme(darkTheme = true) {
        ProfileContent(
            state = ProfileUiState.SignedOut(),
            bikes = emptyList(),
            activeBikeId = null,
            onSignIn = {},
            onSignOut = {},
            onRetrySync = {},
            onOpenSettings = {},
            onAddBike = { _, _ -> },
            onSelectBike = {},
        )
    }
}

@Preview(name = "Rider garage", showBackground = true, backgroundColor = 0xFF11100F, heightDp = 1400)
@Composable
private fun SignedInPreview() {
    NakvaliTheme(darkTheme = true) {
        ProfileContent(
            state = ProfileUiState.SignedIn(
                account = ProfileAccount(
                    "Stanislav Kalishin",
                    "stanislavkalishin@gmail.com",
                    "",
                    true,
                ),
                server = ProfileServerState.Synced,
            ),
            bikes = listOf(
                Bike("capra", "Capra", BikeType.FULL_SUS),
                Bike("hardtail", "Street bike", BikeType.HARDTAIL),
            ),
            activeBikeId = "capra",
            onSignIn = {},
            onSignOut = {},
            onRetrySync = {},
            onOpenSettings = {},
            onAddBike = { _, _ -> },
            onSelectBike = {},
            bikeyard = {
                BikeyardCard(
                    state = com.nakvali.core.recording.bikeyard.BikeyardUiState(
                        loading = false,
                        connected = true,
                        riderName = "Stanislav K.",
                        profile = previewBikeyardProfile(),
                    ),
                    actions = BikeyardActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                )
            },
        )
    }
}
