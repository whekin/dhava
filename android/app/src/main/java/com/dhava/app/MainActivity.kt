package com.dhava.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.dhava.core.ui.DhavaTheme

/** Single activity hosting the whole Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DhavaRoot()
        }
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
}

@Composable
private fun DhavaRoot() {
    // Dark is the primary look for now; follows the system setting.
    DhavaTheme {
        DhavaApp()
    }
}
