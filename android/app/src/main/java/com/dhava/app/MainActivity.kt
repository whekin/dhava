package com.dhava.app

import android.os.Bundle
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
}

@Composable
private fun DhavaRoot() {
    // Dark is the primary look for now; follows the system setting.
    DhavaTheme {
        DhavaApp()
    }
}
