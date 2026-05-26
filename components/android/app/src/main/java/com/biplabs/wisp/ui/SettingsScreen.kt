package com.biplabs.wisp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biplabs.wisp.data.BoundDaemon
import com.biplabs.wisp.data.WispRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrySetupScreen(repository: WispRepository, onDone: () -> Unit) {
    RegistryUrlForm(
        title = "Set Registry URL",
        actionLabel = "Continue",
        repository = repository,
        onBack = null,
        onSaved = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: WispRepository,
    onBack: () -> Unit,
    onPair: () -> Unit,
) {
    var agents by remember { mutableStateOf<List<BoundDaemon>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<BoundDaemon?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshAgents() {
        runCatching {
            withContext(Dispatchers.IO) { repository.boundDaemons() }
        }.onSuccess {
            agents = it
            error = null
        }.onFailure {
            error = it.message
        }
    }

    LaunchedEffect(repository.cloudUrl, repository.clientDeviceId, refreshNonce) {
        refreshAgents()
    }

    LaunchedEffect(repository.cloudUrl, repository.clientDeviceId) {
        while (true) {
            delay(5_000)
            refreshAgents()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            RegistryUrlFormContent(
                repository = repository,
                actionLabel = "Save",
                onSaved = { refreshNonce += 1 },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Agents") },
                supportingContent = {
                    Text(error ?: if (agents.isEmpty()) "No paired agents" else "${agents.size} paired")
                },
                trailingContent = {
                    TextButton(onClick = onPair) {
                        Text("Pair")
                    }
                },
            )
            agents.forEach { agent ->
                ListItem(
                    headlineContent = { Text(agent.displayName) },
                    supportingContent = { Text(agent.status) },
                    trailingContent = {
                        IconButton(onClick = { pendingDelete = agent }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete pairing",
                            )
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    pendingDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete pairing?") },
            text = { Text("Remove ${agent.displayName} from this tablet.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.revokeBinding(agent.bindingId)
                                }
                            }.onSuccess {
                                agents = agents.filterNot { it.bindingId == agent.bindingId }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    repository: WispRepository,
    onDismiss: () -> Unit,
    onPair: () -> Unit,
) {
    var agents by remember { mutableStateOf<List<BoundDaemon>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<BoundDaemon?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshAgents() {
        runCatching {
            withContext(Dispatchers.IO) { repository.boundDaemons() }
        }.onSuccess {
            agents = it
            error = null
        }.onFailure {
            error = it.message
        }
    }

    LaunchedEffect(repository.cloudUrl, repository.clientDeviceId, refreshNonce) {
        refreshAgents()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RegistryUrlFormContent(
                    repository = repository,
                    actionLabel = "Save",
                    onSaved = { refreshNonce += 1 },
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Agents", style = MaterialTheme.typography.titleMedium)
                        Text(
                            error ?: if (agents.isEmpty()) "No paired agents" else "${agents.size} paired",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(onClick = onPair) {
                        Text("Pair")
                    }
                }
                agents.forEach { agent ->
                    ListItem(
                        headlineContent = { Text(agent.displayName) },
                        supportingContent = { Text(agent.status) },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = agent }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete pairing",
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        },
    )

    pendingDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete pairing?") },
            text = { Text("Remove ${agent.displayName} from this tablet.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.revokeBinding(agent.bindingId)
                                }
                            }.onSuccess {
                                agents = agents.filterNot { it.bindingId == agent.bindingId }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistryUrlForm(
    title: String,
    actionLabel: String,
    repository: WispRepository,
    onBack: (() -> Unit)?,
    onSaved: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) {
                            Text("Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            RegistryUrlFormContent(
                repository = repository,
                actionLabel = actionLabel,
                onSaved = onSaved,
            )
        }
    }
}

@Composable
private fun RegistryUrlFormContent(
    repository: WispRepository,
    actionLabel: String,
    onSaved: () -> Unit,
) {
    var registryUrl by remember { mutableStateOf(repository.cloudUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = registryUrl,
            onValueChange = {
                registryUrl = it
                error = null
            },
            label = { Text("Registry URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = {
                val normalized = registryUrl.trim()
                if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
                    error = "Enter a full URL"
                    return@Button
                }
                repository.updateRegistryUrl(normalized)
                onSaved()
            },
            enabled = registryUrl.isNotBlank(),
        ) {
            Text(actionLabel)
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
