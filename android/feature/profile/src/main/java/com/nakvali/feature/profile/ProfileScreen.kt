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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSizes
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliTextField
import com.nakvali.core.ui.NakvaliTheme

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
}

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class SignedOut(
        val signingIn: Boolean = false,
        val error: String? = null,
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
) {
    var showAddBike by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.xLarge),
    ) {
        NakvaliScreenHeader(
            eyebrow = "Rider",
            title = "Profile",
            description = "Your bikes, identity and app setup.",
        )

        ProfileSection(title = "Account") {
            when (state) {
                ProfileUiState.Loading -> LoadingAccount()
                is ProfileUiState.SignedOut -> SignedOutAccount(state, onSignIn)
                is ProfileUiState.SignedIn -> SignedInAccount(state, onRetrySync)
            }
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

        if (state is ProfileUiState.SignedIn) {
            Spacer(Modifier.height(NakvaliSpacing.large))
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Sign out")
            }
            Text(
                text = "Your rides and bikes stay on this phone.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

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
    Spacer(Modifier.height(NakvaliSpacing.xxLarge))
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
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
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
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
            Button(
                onClick = onSignIn,
                enabled = !state.signingIn,
                modifier = Modifier.fillMaxWidth().height(NakvaliSizes.primaryActionHeight),
            ) {
                if (state.signingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(NakvaliSpacing.medium))
                    Text("Opening Google…")
                } else {
                    Text("Continue with Google")
                }
            }
        }
    }
}

@Composable
private fun SignedInAccount(
    state: ProfileUiState.SignedIn,
    onRetrySync: () -> Unit,
) {
    val account = state.account
    val title = account.displayName.ifBlank {
        account.email.substringBefore('@').ifBlank { "Rider" }
    }

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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(NakvaliSpacing.xSmall))
                    Text(
                        text = if (account.emailVerified) "Google account · Verified" else "Google account",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (account.emailVerified) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(NakvaliSpacing.large))
            NakvaliDivider()
            Spacer(Modifier.height(NakvaliSpacing.large))
            ServerStatus(state.server, onRetrySync)
        }
    }
}

@Composable
private fun ServerStatus(server: ProfileServerState, onRetrySync: () -> Unit) {
    when (server) {
        ProfileServerState.Syncing -> Row(
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column {
                Text("Connecting to Nakvali", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your local data stays available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ProfileServerState.Synced -> Row(
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NakvaliStatusPill(
                text = "Synced",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Ready for shared segment results.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ProfileServerState.Unavailable -> Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NakvaliStatusPill("Local only")
                Text(
                    server.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRetrySync, modifier = Modifier.align(Alignment.End)) {
                Text("Try sync again")
            }
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
            Column(
                modifier = Modifier.padding(NakvaliSpacing.xLarge),
                horizontalAlignment = Alignment.Start,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PedalBike, contentDescription = null, Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.height(NakvaliSpacing.large))
                Text("Build your garage", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(NakvaliSpacing.xSmall))
                Text(
                    "Add a bike once and it will be ready when you save a ride.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NakvaliSpacing.large))
                FilledTonalButton(onClick = onAddBike) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(NakvaliSpacing.small))
                    Text("Add bike")
                }
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
                NakvaliStatusPill(
                    text = "Active",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
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

@Preview(name = "Rider garage", showBackground = true, backgroundColor = 0xFF11100F)
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
        )
    }
}
