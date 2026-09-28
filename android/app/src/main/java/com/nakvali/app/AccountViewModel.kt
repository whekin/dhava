package com.nakvali.app

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nakvali.feature.profile.ProfileAccount
import com.nakvali.feature.profile.ProfileServerState
import com.nakvali.feature.profile.ProfileUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway: AuthGateway = FirebaseAuthGateway()

    /**
     * Public builds ship without the private-alpha perimeter key, so the
     * Nakvali server would refuse every request. They stay local-only instead
     * of reporting a sync failure the rider can do nothing about.
     */
    private val cloudAvailable = BuildConfig.API_ACCESS_KEY.isNotBlank()
    private val _state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    private var operation: Job? = null

    init {
        operation = viewModelScope.launch { loadCurrentAccount() }
    }

    fun signIn(activity: Activity) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _state.value = ProfileUiState.SignedOut(signingIn = true, cloudAvailable = cloudAvailable)
            try {
                val account = gateway.signIn(activity)
                sync(account)
            } catch (_: SignInCancelled) {
                _state.value = ProfileUiState.SignedOut(cloudAvailable = cloudAvailable)
            } catch (_: Throwable) {
                _state.value = ProfileUiState.SignedOut(
                    cloudAvailable = cloudAvailable,
                    error = "Couldn’t sign in. Check your connection and try again.",
                )
            }
        }
    }

    fun retrySync() {
        if (operation?.isActive == true) return
        val account = gateway.currentAccount() ?: run {
            _state.value = ProfileUiState.SignedOut(cloudAvailable = cloudAvailable)
            return
        }
        operation = viewModelScope.launch { sync(account, forceTokenRefresh = true) }
    }

    fun signOut() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            gateway.signOut(getApplication())
            _state.value = ProfileUiState.SignedOut(cloudAvailable = cloudAvailable)
        }
    }

    private suspend fun loadCurrentAccount() {
        val account = gateway.currentAccount()
        if (account == null) {
            _state.value = ProfileUiState.SignedOut(cloudAvailable = cloudAvailable)
        } else {
            sync(account)
        }
    }

    private suspend fun sync(account: FirebaseAccount, forceTokenRefresh: Boolean = false) {
        val profile = account.toProfileAccount()
        if (!cloudAvailable) {
            _state.value = ProfileUiState.SignedIn(profile, ProfileServerState.LocalOnly)
            return
        }
        _state.value = ProfileUiState.SignedIn(profile, ProfileServerState.Syncing)
        try {
            gateway.syncProfile(forceTokenRefresh)
            _state.value = ProfileUiState.SignedIn(profile, ProfileServerState.Synced)
        } catch (error: ProfileSyncException) {
            if (error.statusCode == 401 && !forceTokenRefresh) {
                sync(account, forceTokenRefresh = true)
                return
            }
            _state.value = ProfileUiState.SignedIn(
                profile,
                ProfileServerState.Unavailable(
                    when (error.statusCode) {
                        401, 403 -> "Nakvali hasn’t accepted this account yet."
                        in 500..599 -> "Nakvali server is down (HTTP ${error.statusCode}). Rides stay on this phone."
                        else -> "Nakvali server refused sync (HTTP ${error.statusCode})."
                    },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            _state.value = ProfileUiState.SignedIn(
                profile,
                ProfileServerState.Unavailable("Can’t reach Nakvali right now. Rides stay on this phone."),
            )
        }
    }
}

private fun FirebaseAccount.toProfileAccount(): ProfileAccount = ProfileAccount(
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
    emailVerified = emailVerified,
)
