package dev.wispshell.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.wispshell.app.data.BoundDaemon
import dev.wispshell.app.data.WispRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    repository: WispRepository,
    onPair: () -> Unit,
    onSettings: () -> Unit,
    onOpenTerminal: (BoundDaemon) -> Unit,
) {
    var devices by remember { mutableStateOf<List<BoundDaemon>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BoundDaemon?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshDevices(clearOnFailure: Boolean = false) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) { repository.boundDaemons() }
        }.onSuccess {
            devices = it
            error = null
        }.onFailure {
            if (clearOnFailure) {
                devices = emptyList()
            }
            error = it.message
        }
        loading = false
    }

    LaunchedEffect(repository.cloudUrl, repository.clientDeviceId, refreshNonce) {
        refreshDevices(clearOnFailure = devices.isEmpty())
    }

    LaunchedEffect(repository.cloudUrl, repository.clientDeviceId) {
        while (true) {
            delay(5_000)
            refreshDevices(clearOnFailure = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WispShell") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onPair) { Text("+") } },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (error != null && devices.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text("Registry unavailable") },
                    supportingContent = { Text(error.orEmpty()) },
                )
                HorizontalDivider()
            }
            devices.forEach { daemon ->
                ListItem(
                    headlineContent = { Text(daemon.displayName) },
                    supportingContent = { Text(daemon.status) },
                    trailingContent = {
                        IconButton(onClick = { pendingDelete = daemon }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete pairing",
                            )
                        }
                    },
                    modifier = Modifier.clickable(enabled = daemon.status == "online") {
                        onOpenTerminal(daemon)
                    },
                )
                HorizontalDivider()
            }
            if (devices.isEmpty()) {
                ListItem(
                    headlineContent = {
                        Text(
                            when {
                                loading -> "Refreshing daemons"
                                error != null -> "Registry unavailable"
                                else -> "No paired daemons"
                            },
                        )
                    },
                    supportingContent = {
                        Text(error ?: "Tap + to pair a Linux daemon")
                    },
                )
            }
        }
    }

    pendingDelete?.let { daemon ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete pairing?") },
            text = { Text("Remove ${daemon.displayName} from this tablet.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.revokeBinding(daemon.bindingId)
                                }
                            }.onSuccess {
                                devices = devices.filterNot { it.bindingId == daemon.bindingId }
                                error = null
                                refreshNonce += 1
                            }.onFailure {
                                error = it.message
                            }
                        }
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
