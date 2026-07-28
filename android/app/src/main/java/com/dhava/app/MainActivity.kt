package com.dhava.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.RecordingService
import com.dhava.core.ui.DhavaTheme

/** Single activity hosting the whole Compose UI. */
class MainActivity : ComponentActivity() {
    private var openRecorderRequest by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DhavaRoot(openRecorderRequest)
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

    fun applyKeepScreenOn() {
        val enabled = getSharedPreferences("recorder_settings", MODE_PRIVATE)
            .getBoolean("keep_screen_on", false)
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
        if (data.scheme == "dhava" && data.host == "strava" && data.path == "/connected") {
            RecordingRepository.getInstance(this)
                .onStravaOAuthRedirect(data.getQueryParameter("result"))
        }
    }
}

@Composable
private fun DhavaRoot(openRecorderRequest: Long) {
    // Dark is the primary look for now; follows the system setting.
    DhavaTheme {
        DhavaApp(openRecorderRequest)
    }
}
