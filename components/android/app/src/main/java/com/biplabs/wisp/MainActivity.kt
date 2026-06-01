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
import androidx.compose.ui.graphics.Color
import com.biplabs.wisp.bridge.WispNative
import com.biplabs.wisp.data.WispRepository
import com.biplabs.wisp.ui.PairScreen
import com.biplabs.wisp.ui.RegistrySetupScreen
import com.biplabs.wisp.ui.TerminalScreen

private val DeveloperDarkColorScheme = darkColorScheme(
    primary = Color(0x00, 0xE5, 0xFF), // Neon Cyan
    onPrimary = Color(0x10, 0x14, 0x18), // Obsidian
    primaryContainer = Color(0x00, 0x4D, 0x5A), // Deep Slate Teal
    onPrimaryContainer = Color(0xB2, 0xFA, 0xFF),
    secondary = Color(0x7C, 0x4D, 0xFF), // Neon Violet
    onSecondary = Color(0xFF, 0xFF, 0xFF),
    background = Color(0x10, 0x14, 0x18), // Obsidian
    onBackground = Color(0xFF, 0xFF, 0xFF), // Pure White for E-ink contrast
    surface = Color(0x17, 0x1C, 0x24), // Darker Surface
    onSurface = Color(0xFF, 0xFF, 0xFF), // Pure White for E-ink contrast
    surfaceVariant = Color(0x22, 0x2A, 0x36),
    onSurfaceVariant = Color(0xFF, 0xFF, 0xFF), // Pure White for E-ink contrast
    outline = Color(0x3A, 0x47, 0x58),
    error = Color(0xFF, 0x52, 0x52)
)

private val DeveloperLightColorScheme = lightColorScheme(
    primary = Color(0x00, 0x83, 0x8F),
    onPrimary = Color(0xFF, 0xFF, 0xFF),
    primaryContainer = Color(0xE0, 0xF7, 0xFA),
    onPrimaryContainer = Color(0x00, 0x60, 0x64),
    secondary = Color(0x62, 0x00, 0xEA),
    onSecondary = Color(0xFF, 0xFF, 0xFF),
    background = Color(0xF5, 0xF7, 0xFA),
    onBackground = Color(0x00, 0x00, 0x00), // Pure Black for E-ink contrast
    surface = Color(0xFF, 0xFF, 0xFF),
    onSurface = Color(0x00, 0x00, 0x00), // Pure Black for E-ink contrast
    surfaceVariant = Color(0xEE, 0xF2, 0xF6),
    onSurfaceVariant = Color(0x00, 0x00, 0x00), // Pure Black for E-ink contrast
    outline = Color(0xC4, 0xCE, 0xD8),
    error = Color(0xD3, 0x2F, 0x2F)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WispNative.initializeAndroidContext(this)
        val repository = WispRepository(this)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) DeveloperDarkColorScheme else DeveloperLightColorScheme,
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
}
