package com.macroandroid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.app.ui.MacroApp
import com.macroandroid.app.ui.MainViewModel
import com.macroandroid.app.ui.rememberMacroAppState
import com.macroandroid.automation.android.gate.ForegroundGate
import com.macroandroid.core.datastore.ThemeMode
import com.macroandroid.core.ui.theme.MacroAndroidTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The single activity (doc 05 §3). Intents are routed by [MainViewModel]; navigation lives in [MacroApp]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var foregroundGate: ForegroundGate

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { viewModel.uiState.value == null }
        viewModel.onIntent(intent, fromSavedState = savedInstanceState != null)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val s = state ?: return@setContent
            val dark = when (s.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MacroAndroidTheme(darkTheme = dark, dynamicColor = s.dynamicColor) {
                val appState = rememberMacroAppState()
                MacroApp(appState = appState, mainState = s, viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Notification taps count as a user interaction for the background-activity-launch gate.
        if (intent.dataString?.startsWith("macroandroid://execution") == true) foregroundGate.recordNotificationTap()
        viewModel.onIntent(intent, fromSavedState = false)
    }
}
