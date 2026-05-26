package com.biplabs.wisp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.biplabs.wisp.bridge.WispNative
import com.biplabs.wisp.data.BoundDaemon
import com.biplabs.wisp.data.WispRepository
import com.biplabs.wisp.ui.DeviceListScreen
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
            var screen by remember {
                mutableStateOf<Screen>(
                    if (repository.hasRegistryUrl) Screen.Devices else Screen.RegistrySetup,
                )
            }
            when (val current = screen) {
                Screen.RegistrySetup -> RegistrySetupScreen(
                    repository = repository,
                    onDone = { screen = Screen.Devices },
                )
                Screen.Pair -> PairScreen(
                    repository = repository,
                    onDone = { screen = Screen.Devices },
                )
                Screen.Devices -> DeviceListScreen(
                    repository = repository,
                    onPair = { screen = Screen.Pair },
                    onSettings = { screen = Screen.Settings },
                    onOpenTerminal = {
                        screen = Screen.Terminal(it)
                    },
                )
                Screen.Settings -> SettingsScreen(
                    repository = repository,
                    onBack = { screen = Screen.Devices },
                )
                is Screen.Terminal -> TerminalScreen(
                    repository = repository,
                    daemon = current.daemon,
                    onBack = { screen = Screen.Devices },
                )
            }
        }
    }
}

sealed interface Screen {
    data object RegistrySetup : Screen
    data object Pair : Screen
    data object Devices : Screen
    data object Settings : Screen
    data class Terminal(val daemon: BoundDaemon) : Screen
}
