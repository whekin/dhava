package com.nakvali.app

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nakvali.feature.profile.ProfileAccount
import com.nakvali.feature.profile.ProfileServerState
import com.nakvali.feature.profile.ProfileUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway: AuthGateway = FirebaseAuthGateway()
    private val _state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    private var operation: Job? = null

    init {
        operation = viewModelScope.launch { loadCurrentAccount() }
    }

    fun signIn(activity: Activity) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _state.value = ProfileUiState.SignedOut(signingIn = true)
            try {
                val account = gateway.signIn(activity)
                sync(account)
            } catch (_: SignInCancelled) {
                _state.value = ProfileUiState.SignedOut()
            } catch (_: Throwable) {
                _state.value = ProfileUiState.SignedOut(
                    error = "Couldn’t sign in. Check your connection and try again.",
                )
            }
        }
    }

    fun retrySync() {
        if (operation?.isActive == true) return
        val account = gateway.currentAccount() ?: run {
            _state.value = ProfileUiState.SignedOut()
            return
        }
        operation = viewModelScope.launch { sync(account, forceTokenRefresh = true) }
    }

    fun signOut() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            gateway.signOut(getApplication())
            _state.value = ProfileUiState.SignedOut()
        }
    }

    private suspend fun loadCurrentAccount() {
        val account = gateway.currentAccount()
        if (account == null) {
            _state.value = ProfileUiState.SignedOut()
        } else {
            sync(account)
        }
    }

    private suspend fun sync(account: FirebaseAccount, forceTokenRefresh: Boolean = false) {
        val profile = account.toProfileAccount()
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
                    if (error.statusCode == 401) {
                        "The server hasn’t accepted this account yet."
                    } else {
                        "Profile sync is temporarily unavailable."
                    },
                ),
            )
        } catch (_: Throwable) {
            _state.value = ProfileUiState.SignedIn(
                profile,
                ProfileServerState.Unavailable(
                    "Profile sync is unavailable. Your local rides are safe.",
                ),
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
