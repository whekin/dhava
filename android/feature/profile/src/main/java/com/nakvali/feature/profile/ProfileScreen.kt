package com.nakvali.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSizes
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.xLarge),
    ) {
        NakvaliScreenHeader(
            eyebrow = "Rider identity",
            title = "Profile",
            description = when (state) {
                ProfileUiState.Loading -> "Checking your account…"
                is ProfileUiState.SignedOut -> "Sign in for shared segments, PRs and leaderboards."
                is ProfileUiState.SignedIn -> "Your identity for synced segment results."
            },
        )
        Spacer(Modifier.height(NakvaliSpacing.xxLarge))

        when (state) {
            ProfileUiState.Loading -> LoadingProfile()
            is ProfileUiState.SignedOut -> SignedOutProfile(state, onSignIn)
            is ProfileUiState.SignedIn -> SignedInProfile(
                state = state,
                onSignOut = onSignOut,
                onRetrySync = onRetrySync,
            )
        }

        Spacer(Modifier.height(NakvaliSpacing.xxLarge))
        Text(
            text = "Recording and local segments always work without an account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingProfile() {
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(NakvaliSpacing.xLarge),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(
                text = "Restoring your session",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SignedOutProfile(state: ProfileUiState.SignedOut, onSignIn: () -> Unit) {
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(NakvaliSpacing.xLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RiderMark("N")
            Spacer(Modifier.height(NakvaliSpacing.large))
            Text(
                text = "Keep your trail history yours",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(NakvaliSpacing.small))
            Text(
                text = "Google sign-in links future PRs and segment results across devices. " +
                    "Raw sensor recordings stay on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
private fun SignedInProfile(
    state: ProfileUiState.SignedIn,
    onSignOut: () -> Unit,
    onRetrySync: () -> Unit,
) {
    val account = state.account
    val title = account.displayName.ifBlank {
        account.email.substringBefore('@').ifBlank { "Rider" }
    }
    NakvaliSectionLabel("Account")
    Spacer(Modifier.height(NakvaliSpacing.medium))
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(NakvaliSpacing.xLarge),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RiderMark(title.firstOrNull()?.uppercase() ?: "N")
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = account.email.ifBlank { "Google account" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (account.emailVerified) NakvaliStatusPill("Verified")
        }
    }

    Spacer(Modifier.height(NakvaliSpacing.xxLarge))
    NakvaliSectionLabel("Nakvali server")
    Spacer(Modifier.height(NakvaliSpacing.medium))
    NakvaliPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(NakvaliSpacing.large)) {
            when (val server = state.server) {
                ProfileServerState.Syncing -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Linking your Nakvali profile…")
                    }
                }
                ProfileServerState.Synced -> {
                    NakvaliStatusPill(
                        text = "Synced",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(NakvaliSpacing.small))
                    Text(
                        text = "This account is ready for shared segment results.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ProfileServerState.Unavailable -> {
                    NakvaliStatusPill("Local only")
                    Spacer(Modifier.height(NakvaliSpacing.small))
                    Text(
                        text = server.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetrySync) { Text("Try sync again") }
                }
            }
        }
    }
    Spacer(Modifier.height(NakvaliSpacing.large))
    TextButton(onClick = onSignOut) { Text("Sign out") }
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

@Preview(showBackground = true, backgroundColor = 0xFF11100F)
@Composable
private fun SignedOutPreview() {
    NakvaliTheme(darkTheme = true) {
        ProfileScreen(ProfileUiState.SignedOut(), {}, {}, {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF11100F)
@Composable
private fun SignedInPreview() {
    NakvaliTheme(darkTheme = true) {
        ProfileScreen(
            state = ProfileUiState.SignedIn(
                account = ProfileAccount("Trail Rider", "rider@example.com", "", true),
                server = ProfileServerState.Synced,
            ),
            onSignIn = {},
            onSignOut = {},
            onRetrySync = {},
        )
    }
}
