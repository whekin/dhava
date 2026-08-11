package com.nakvali.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.RecordingService
import com.nakvali.core.recording.RecorderSettings
import com.nakvali.core.ui.NakvaliTheme

/** Single activity hosting the whole Compose UI. */
class MainActivity : ComponentActivity() {
    private var openRecorderRequest by mutableLongStateOf(0L)
    private val accountViewModel by viewModels<AccountViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val profileState by accountViewModel.state.collectAsState()
            NakvaliRoot(
                openRecorderRequest = openRecorderRequest,
                profileState = profileState,
                onSignIn = { accountViewModel.signIn(this) },
                onSignOut = accountViewModel::signOut,
                onRetryProfileSync = accountViewModel::retrySync,
            )
        }
        handleAppIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
    }

    // The recorder scales its live work to whether anyone is looking. Tied to
    // start/stop rather than resume/pause so a transient system dialog does
    // not flap the recording service's cadence.
    override fun onStart() {
        super.onStart()
        RecordingRepository.getInstance(application).setUiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        RecordingRepository.getInstance(application).setUiVisible(false)
    }

    fun applyKeepScreenOn() {
        val preferences = RecorderSettings.preferences(this)
        val enabled = preferences.getBoolean(RecorderSettings.DEVELOPER_MODE, false) &&
            preferences.getBoolean(RecorderSettings.KEEP_SCREEN_ON, false)
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleAppIntent(intent: Intent?) {
        handleStravaRedirect(intent)
        if (intent?.action == RecordingService.ACTION_OPEN_RECORDING) {
            openRecorderRequest++
            // Do not replay the navigation request after a configuration change.
            intent.action = null
        }
    }

    private fun handleStravaRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "nakvali" && data.host == "strava" && data.path == "/connected") {
            RecordingRepository.getInstance(this)
                .onStravaOAuthRedirect(data.getQueryParameter("result"))
        }
    }
}

@Composable
private fun NakvaliRoot(
    openRecorderRequest: Long,
    profileState: com.nakvali.feature.profile.ProfileUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetryProfileSync: () -> Unit,
) {
    // Dark is the primary look for now; follows the system setting.
    NakvaliTheme {
        NakvaliApp(
            openRecorderRequest = openRecorderRequest,
            profileState = profileState,
            onSignIn = onSignIn,
            onSignOut = onSignOut,
            onRetryProfileSync = onRetryProfileSync,
        )
    }
}
