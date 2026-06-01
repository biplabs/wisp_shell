package com.biplabs.wisp.ui

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.biplabs.wisp.data.BoundDaemon
import com.biplabs.wisp.data.TerminalInputMode
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
    var inputMode by remember { mutableStateOf(repository.terminalInputMode) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow((context as? Activity)?.window?.decorView?.windowToken, 0)
        delay(150)
        imm?.hideSoftInputFromWindow((context as? Activity)?.window?.decorView?.windowToken, 0)
    }

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
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close settings",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(
                title = "Terminal Input",
                description = "Choose how typing is delivered to the remote terminal.",
            ) {
                TerminalInputModePicker(
                    selected = inputMode,
                    onSelected = { selected ->
                        inputMode = selected
                        repository.terminalInputMode = selected
                    },
                )
            }

            SettingsSection(
                title = "Connections",
                description = "Manage the registry and paired agents.",
            ) {
                Text("Registry", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The cloud registry coordinates pairing and rendezvous.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RegistryUrlFormContent(
                    repository = repository,
                    actionLabel = "Save",
                    requireChanged = true,
                    onSaved = { refreshNonce += 1 },
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Agents", style = MaterialTheme.typography.titleSmall)
                        Text(
                            error ?: if (agents.isEmpty()) {
                                "No paired agents"
                            } else {
                                "${agents.size} paired"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onPair) {
                        Text("Pair")
                    }
                }
                if (agents.isEmpty()) {
                    Text(
                        text = "Pair an agent to open terminal sessions from this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                agents.forEachIndexed { index, agent ->
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
                    if (index != agents.lastIndex) {
                        HorizontalDivider()
                    }
                }
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

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun TerminalInputModePicker(
    selected: TerminalInputMode,
    onSelected: (TerminalInputMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalInputModeOption(
            mode = TerminalInputMode.Sync,
            selected = selected == TerminalInputMode.Sync,
            title = "Sync",
            description = "Send every key immediately, like SSH. Most accurate for editors and interactive tools, but high latency is visible.",
            onSelected = onSelected,
        )
        TerminalInputModeOption(
            mode = TerminalInputMode.Line,
            selected = selected == TerminalInputMode.Line,
            title = "Line",
            description = "Type in a command box and send only when you press Enter. Best for high-latency command entry.",
            onSelected = onSelected,
        )
        TerminalInputModeOption(
            mode = TerminalInputMode.Predictive,
            selected = selected == TerminalInputMode.Predictive,
            title = "Predictive",
            description = "Show local input immediately and sync it in short batches. Fast for shell prompts, but can fall back poorly in some apps.",
            onSelected = onSelected,
        )
    }
}

@Composable
private fun TerminalInputModeOption(
    mode: TerminalInputMode,
    selected: Boolean,
    title: String,
    description: String,
    onSelected: (TerminalInputMode) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(mode) },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surface,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary else colors.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = selected,
                onClick = { onSelected(mode) },
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        colors.onPrimaryContainer
                    } else {
                        colors.onSurfaceVariant
                    },
                )
            }
        }
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
    requireChanged: Boolean = false,
    onSaved: () -> Unit,
) {
    var registryUrl by remember { mutableStateOf(repository.cloudUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    val normalizedRegistryUrl = registryUrl.trim().trimEnd('/')
    val canSave = normalizedRegistryUrl.isNotBlank() &&
        (!requireChanged || normalizedRegistryUrl != repository.cloudUrl)

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
                if (!normalizedRegistryUrl.startsWith("https://") &&
                    !normalizedRegistryUrl.startsWith("http://")
                ) {
                    error = "Enter a full URL"
                    return@Button
                }
                repository.updateRegistryUrl(normalizedRegistryUrl)
                onSaved()
            },
            enabled = canSave,
        ) {
            Text(actionLabel)
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
