package com.biplabs.wisp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.biplabs.wisp.bridge.WispNative
import com.biplabs.wisp.data.WispRepository
import com.biplabs.wisp.ui.PairScreen
import com.biplabs.wisp.ui.RegistrySetupScreen
import com.biplabs.wisp.ui.SettingsScreen
import com.biplabs.wisp.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WispNative.initializeAndroidContext(this)
        val repository = WispRepository(this)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                var screen by rememberSaveable {
                    mutableStateOf(if (repository.hasRegistryUrl) Screen.Workspace else Screen.RegistrySetup)
                }
                when (screen) {
                    Screen.RegistrySetup -> RegistrySetupScreen(
                        repository = repository,
                        onDone = { screen = Screen.Workspace },
                    )
                    Screen.Pair -> PairScreen(
                        repository = repository,
                        onDone = { screen = Screen.Workspace },
                    )
                    Screen.Settings -> SettingsScreen(
                        repository = repository,
                        onBack = { screen = Screen.Workspace },
                        onPair = { screen = Screen.Pair },
                    )
                    Screen.Workspace -> TerminalScreen(
                        repository = repository,
                        onPair = { screen = Screen.Pair },
                    )
                }
            }
        }
    }
}

enum class Screen {
    RegistrySetup,
    Workspace,
    Pair,
    Settings,
}
