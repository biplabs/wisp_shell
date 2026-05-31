package com.biplabs.wisp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.biplabs.wisp.data.BoundDaemon
import com.biplabs.wisp.data.RendezvousInfo
import com.biplabs.wisp.data.SavedTerminalTab
import com.biplabs.wisp.data.WispRepository
import com.biplabs.wisp.terminal.ConnectionState
import com.biplabs.wisp.terminal.TransportPathStatus
import com.biplabs.wisp.terminal.WispTermuxTerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TerminalScreen(
    repository: WispRepository,
    onPair: () -> Unit,
) {
    var tabs by remember { mutableStateOf(repository.savedTerminalTabs()) }
    var selectedTabId by remember { mutableStateOf(tabs.firstOrNull()?.id) }
    var agents by remember { mutableStateOf<List<BoundDaemon>>(emptyList()) }
    var agentError by remember { mutableStateOf<String?>(null) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var transportPaths by remember { mutableStateOf<Map<String, TransportPathStatus>>(emptyMap()) }
    val wifiStatus = rememberWifiStatus()
    val selectedTransportPath = selectedTabId?.let { transportPaths[it] }
    val activity = LocalContext.current.findActivity()

    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())

        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    LaunchedEffect(tabs) {
        repository.saveTerminalTabs(tabs)
        if (selectedTabId == null || tabs.none { it.id == selectedTabId }) {
            selectedTabId = tabs.firstOrNull()?.id
        }
    }

    LaunchedEffect(repository.cloudUrl, repository.clientDeviceId) {
        while (true) {
            runCatching {
                withContext(Dispatchers.IO) { repository.boundDaemons() }
            }.onSuccess { refreshed ->
                agents = refreshed
                agentError = null
                tabs = tabs.map { tab ->
                    refreshed.firstOrNull { it.bindingId == tab.daemon.bindingId }
                        ?.let { tab.copy(daemon = it) }
                        ?: tab
                }
            }.onFailure {
                agentError = it.message
            }
            delay(5_000)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tabs.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tabs.forEach { tab ->
                                TabChip(
                                    title = "${tab.daemon.displayName}: ${tab.title ?: tabTitle(tab.sessionName)}",
                                    selected = tab.id == selectedTabId,
                                    onSelect = { selectedTabId = tab.id },
                                    onClose = {
                                        val index = tabs.indexOfFirst { it.id == tab.id }
                                        val nextTabs = tabs.filterNot { it.id == tab.id }
                                        tabs = nextTabs
                                        if (selectedTabId == tab.id) {
                                            selectedTabId = nextTabs
                                                .getOrNull((index - 1).coerceAtLeast(0))
                                                ?.id
                                        }
                                    },
                                )
                            }
                            TextButton(onClick = {
                                val candidates = tabCandidates(agents)
                                when (candidates.size) {
                                    0 -> showAgentPicker = true
                                    1 -> {
                                        val tab = newTab(candidates.first(), tabs)
                                        tabs = tabs + tab
                                        selectedTabId = tab.id
                                    }
                                    else -> showAgentPicker = true
                                }
                            }) {
                                Text("+")
                            }
                        }
                    }
                    WifiStatusIcon(status = wifiStatus, transportPath = selectedTransportPath)
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            if (tabs.isEmpty()) {
                AgentPickerContent(
                    agents = agents,
                    error = agentError,
                    onPair = onPair,
                    onOpen = { agent ->
                        val tab = newTab(agent, tabs)
                        tabs = tabs + tab
                        selectedTabId = tab.id
                    },
                )
            } else {
                tabs.forEach { tab ->
                    val active = tab.id == selectedTabId
                    key(tab.id) {
                        TerminalTabPane(
                            repository = repository,
                            tab = tab,
                            active = active,
                            modifier = Modifier
                                .matchParentSize()
                                .zIndex(if (active) 1f else 0f),
                            onTitleChanged = { title ->
                                tabs = tabs.map {
                                    if (it.id == tab.id) it.copy(title = title) else it
                                }
                            },
                            onTransportPathChanged = { path ->
                                transportPaths = if (path == null) {
                                    transportPaths - tab.id
                                } else {
                                    transportPaths + (tab.id to path)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAgentPicker) {
        AgentPickerDialog(
            agents = agents,
            error = agentError,
            onDismiss = { showAgentPicker = false },
            onPair = {
                showAgentPicker = false
                onPair()
            },
            onOpen = { agent ->
                val tab = newTab(agent, tabs)
                tabs = tabs + tab
                selectedTabId = tab.id
                showAgentPicker = false
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            repository = repository,
            onDismiss = { showSettings = false },
            onPair = {
                showSettings = false
                onPair()
            },
        )
    }
}

@Composable
private fun TerminalTabPane(
    repository: WispRepository,
    tab: SavedTerminalTab,
    active: Boolean,
    modifier: Modifier,
    onTitleChanged: (String) -> Unit,
    onTransportPathChanged: (TransportPathStatus?) -> Unit,
) {
    var rendezvous by remember(tab.daemon.bindingId) { mutableStateOf<RendezvousInfo?>(null) }
    var error by remember(tab.daemon.bindingId) { mutableStateOf<String?>(null) }
    var retryNonce by remember(tab.daemon.bindingId) { mutableIntStateOf(0) }
    var reconnectNonce by remember(tab.id) { mutableIntStateOf(0) }
    var connectionState by remember(tab.id) { mutableStateOf<ConnectionState?>(null) }
    var connectionError by remember(tab.id) { mutableStateOf<String?>(null) }
    var showConnectionDialog by remember(tab.id) { mutableStateOf(false) }
    var sendTerminalInput by remember(tab.id) { mutableStateOf<((String) -> Unit)?>(null) }

    LaunchedEffect(tab.daemon.bindingId, retryNonce) {
        rendezvous = null
        error = null
        connectionError = null
        connectionState = ConnectionState.Connecting
        showConnectionDialog = active
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            runCatching {
                withContext(Dispatchers.IO) { repository.rendezvous(tab.daemon) }
            }.onSuccess {
                rendezvous = it
                return@LaunchedEffect
            }.onFailure {
                lastError = it
            }
            if (attempt < 2) {
                delay((attempt + 1) * 750L)
            }
        }
        val message = lastError?.message ?: "Could not fetch rendezvous"
        error = message
        connectionError = message
        connectionState = ConnectionState.Disconnected
        showConnectionDialog = active
    }

    LaunchedEffect(active, connectionState, connectionError) {
        if (!active) return@LaunchedEffect
        if (connectionState == ConnectionState.Attached && connectionError == null) {
            delay(1_500)
            showConnectionDialog = false
        }
    }

    LaunchedEffect(active, connectionState, reconnectNonce) {
        if (!active || connectionState != ConnectionState.Disconnected) return@LaunchedEffect
        if (connectionError != null) return@LaunchedEffect
        if (rendezvous == null) return@LaunchedEffect
        delay(2_000)
        connectionError = null
        reconnectNonce++
    }

    LaunchedEffect(active, error, retryNonce) {
        if (!active || error == null || rendezvous != null) return@LaunchedEffect
        delay(5_000)
        retryNonce++
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                rendezvous != null -> WispTermuxTerminalView(
                    sessionName = tab.sessionName,
                    active = active,
                    reconnectNonce = reconnectNonce,
                    rendezvous = rendezvous,
                    clientPrivateKey = repository.clientPrivateKey,
                    clientDeviceId = repository.clientDeviceId,
                    bindingId = tab.daemon.bindingId,
                    modifier = Modifier.fillMaxSize(),
                    onConnectionState = { state ->
                        if (state == ConnectionState.Connecting || state == ConnectionState.Attached) {
                            connectionError = null
                        }
                        connectionState = state
                        if (active) {
                            showConnectionDialog = true
                        }
                    },
                    onConnectionError = { message ->
                        connectionError = message
                        if (active) {
                            showConnectionDialog = true
                        }
                    },
                    onTransportPathChanged = onTransportPathChanged,
                    onTitleChanged = onTitleChanged,
                    onSendInputReady = { sender ->
                        sendTerminalInput = sender
                    },
                )

                error != null -> Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { retryNonce++ }) {
                        Text("Retry")
                    }
                }
            }
        }
        TerminalShortcutBar(
            enabled = active && sendTerminalInput != null,
            onSend = { input -> sendTerminalInput?.invoke(input) },
        )
    }

    if (active && showConnectionDialog) {
        ConnectionStatusDialog(
            daemon = tab.daemon,
            rendezvous = rendezvous,
            bindingId = tab.daemon.bindingId,
            state = connectionState,
            error = connectionError,
            onDismiss = {
                showConnectionDialog = false
            },
            onRetry = {
                val needsRendezvous = rendezvous == null || error != null
                error = null
                connectionError = null
                connectionState = ConnectionState.Connecting
                showConnectionDialog = true
                if (needsRendezvous) {
                    retryNonce++
                } else {
                    reconnectNonce++
                }
            },
        )
    }
}

@Composable
private fun TerminalShortcutBar(
    enabled: Boolean,
    onSend: (String) -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalShortcutButton(
                label = "Ctrl-C",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSend("\u0003") },
            )
            TerminalShortcutButton(
                label = "Right",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSend("\u001b[C") },
            )
            TerminalShortcutButton(
                label = "Up",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSend("\u001b[A") },
            )
            TerminalShortcutButton(
                label = "Down",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSend("\u001b[B") },
            )
        }
    }
}

@Composable
private fun TerminalShortcutButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class WifiStatus(
    val connected: Boolean,
    val validated: Boolean,
)

@Composable
private fun rememberWifiStatus(): WifiStatus {
    val context = LocalContext.current
    var status by remember(context) { mutableStateOf(context.currentWifiStatus()) }
    DisposableEffect(context) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@DisposableEffect onDispose {}
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() {
                mainHandler.post {
                    status = context.currentWifiStatus()
                }
            }

            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = refresh()
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.onFailure {
            status = context.currentWifiStatus()
        }
        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
    return status
}

private fun Context.currentWifiStatus(): WifiStatus {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiStatus(connected = false, validated = false)
    val capabilities = connectivityManager
        .getNetworkCapabilities(connectivityManager.activeNetwork)
    return WifiStatus(
        connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
    )
}

@Composable
private fun WifiStatusIcon(status: WifiStatus, transportPath: TransportPathStatus?) {
    val color = when {
        status.connected && status.validated -> MaterialTheme.colorScheme.onSurface
        status.connected -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    val label = when {
        status.connected && status.validated -> "Wi-Fi connected"
        status.connected -> "Wi-Fi connected, internet not validated"
        else -> "Wi-Fi disconnected"
    }
    val transportLabel = when (transportPath?.path) {
        "direct" -> "Direct"
        "relay" -> "Relay"
        "tcp" -> "TCP"
        "unknown" -> "Path"
        else -> null
    }
    val latencyLabel = transportPath?.latencyMs?.let { "${it} ms" }
    val transportTextStyle = MaterialTheme.typography.labelSmall.copy(lineHeight = 11.sp)
    Row(
        modifier = Modifier.height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = label },
            ) {
                val wifiPath = Path().apply {
                    moveTo(size.width * 0.08f, size.height * 0.26f)
                    quadraticTo(
                        size.width * 0.5f,
                        size.height * -0.03f,
                        size.width * 0.92f,
                        size.height * 0.26f,
                    )
                    lineTo(size.width * 0.5f, size.height * 0.78f)
                    close()
                }
                if (status.connected) {
                    drawPath(path = wifiPath, color = color)
                } else {
                    drawPath(
                        path = wifiPath,
                        color = color,
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        if (transportLabel != null) {
            Column(
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = transportLabel,
                    style = transportTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (latencyLabel != null) {
                    Text(
                        text = latencyLabel,
                        style = transportTextStyle,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentPickerContent(
    agents: List<BoundDaemon>,
    error: String?,
    onPair: () -> Unit,
    onOpen: (BoundDaemon) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pick a Shell",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Choose an online agent to open a terminal tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )
        if (error != null && agents.isNotEmpty()) {
            ListItem(
                headlineContent = { Text("Registry unavailable") },
                supportingContent = { Text(error) },
            )
            HorizontalDivider()
        }
        agents.forEach { daemon ->
            OutlinedButton(
                onClick = { onOpen(daemon) },
                enabled = daemon.status == "online",
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(76.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = daemon.displayName,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = daemon.status,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (agents.isEmpty()) {
            ListItem(
                headlineContent = { Text(if (error == null) "No open tabs" else "Registry unavailable") },
                supportingContent = { Text(error ?: "Open a tab from a paired agent, or pair a new agent.") },
            )
            Button(
                onClick = onPair,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("Pair agent")
            }
        }
    }
}

@Composable
private fun AgentPickerDialog(
    agents: List<BoundDaemon>,
    error: String?,
    onDismiss: () -> Unit,
    onPair: () -> Unit,
    onOpen: (BoundDaemon) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onPair) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("New tab") },
        text = {
            Column {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                tabCandidates(agents).forEach { daemon ->
                    AgentRow(daemon = daemon, onOpen = onOpen)
                }
            }
        },
    )
}

@Composable
private fun AgentRow(daemon: BoundDaemon, onOpen: (BoundDaemon) -> Unit) {
    ListItem(
        headlineContent = { Text(daemon.displayName) },
        supportingContent = { Text(daemon.status) },
        modifier = Modifier.clickable(enabled = daemon.status == "online") {
            onOpen(daemon)
        },
    )
    HorizontalDivider()
}

@Composable
private fun TabChip(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = if (selected) {
        ButtonDefaults.filledTonalButtonColors()
    } else {
        ButtonDefaults.textButtonColors()
    }
    TextButton(
        onClick = onSelect,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "x",
            modifier = Modifier.clickable(onClick = onClose),
        )
    }
}

private fun newTab(daemon: BoundDaemon, existing: List<SavedTerminalTab>): SavedTerminalTab {
    val sessionName = nextSessionName(existing)
    return SavedTerminalTab(
        id = "${daemon.bindingId}-$sessionName-${System.currentTimeMillis()}",
        sessionName = sessionName,
        title = null,
        daemon = daemon,
    )
}

private fun tabCandidates(agents: List<BoundDaemon>): List<BoundDaemon> {
    return agents.filter { it.status == "online" }.ifEmpty { agents }
}

private fun tabTitle(name: String): String {
    return if (name == "main") "main" else name.removePrefix("tab-")
}

private fun nextSessionName(existing: List<SavedTerminalTab>): String {
    if (existing.none { it.sessionName == "main" }) {
        return "main"
    }
    var index = 2
    while (existing.any { it.sessionName == "tab-$index" }) {
        index += 1
    }
    return "tab-$index"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ConnectionStatusDialog(
    daemon: BoundDaemon,
    rendezvous: RendezvousInfo?,
    bindingId: String,
    state: ConnectionState?,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val appVersion = remember(context) { context.appVersionName() }
    val agentVersion = rendezvous?.agentVersion
    val registryVersion = rendezvous?.registryVersion
    val versionWarnings = versionWarnings(agentVersion, registryVersion, appVersion)
    val details = remember(rendezvous?.irohNodeAddrJson) {
        P2pDetails.from(rendezvous?.irohNodeAddrJson)
    }
    val title = when {
        error != null -> "Connection issue"
        state == ConnectionState.Attached -> "Connected"
        state == ConnectionState.Disconnected -> "Disconnected"
        else -> "Connecting"
    }
    val canRetryOrCancel = error != null || state == ConnectionState.Disconnected
    AlertDialog(
        onDismissRequest = {
            if (canRetryOrCancel) {
                onDismiss()
            }
        },
        confirmButton = {
            if (canRetryOrCancel) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        },
        dismissButton = {
            if (canRetryOrCancel) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                versionWarnings.forEach { warning ->
                    Text(warning, color = MaterialTheme.colorScheme.error)
                }
                DetailLine("Daemon", daemon.displayName)
                DetailLine("App version", appVersion)
                DetailLine("Agent version", agentVersion ?: "unknown")
                DetailLine("Registry version", registryVersion ?: "unknown")
                DetailLine("Transport", "Iroh P2P")
                DetailLine("Registry status", rendezvous?.status ?: "fetching")
                DetailLine("Binding", bindingId.take(8))
                details.nodeId?.let { DetailLine("Node", it.take(16)) }
                if (details.directAddrs.isNotEmpty()) {
                    DetailLine("Direct", details.directAddrs.joinToString())
                }
                if (details.relayAddrs.isNotEmpty()) {
                    DetailLine("Relay", details.relayAddrs.joinToString())
                }
                if (details.directAddrs.isEmpty() && details.relayAddrs.isEmpty()) {
                    DetailLine("Address", "waiting for rendezvous")
                }
            }
        },
    )
}

private fun Context.appVersionName(): String {
    return runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}

private fun versionWarnings(
    agentVersion: String?,
    registryVersion: String?,
    appVersion: String,
): List<String> {
    return buildList {
        val normalizedAgent = agentVersion?.takeIf { it.isNotBlank() }
        if (normalizedAgent == null) {
            add("Agent version unavailable. Update the agent and registry.")
        } else {
            when (compareDottedVersions(normalizedAgent, appVersion)) {
                1 -> add("App is older than the agent. Update the app.")
                -1 -> add("Agent is older than the app. Update the agent.")
            }
        }

        val normalizedRegistry = registryVersion?.takeIf { it.isNotBlank() }
        if (normalizedRegistry == null) {
            add("Registry version unavailable. Update the registry.")
        } else {
            when (compareDottedVersions(normalizedRegistry, appVersion)) {
                1 -> add("App is older than the registry. Update the app.")
                -1 -> add("Registry is older than the app. Update the registry.")
            }
        }
    }
}

private fun compareDottedVersions(left: String, right: String): Int {
    val leftParts = left.versionParts()
    val rightParts = right.versionParts()
    val count = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until count) {
        val l = leftParts.getOrElse(index) { 0 }
        val r = rightParts.getOrElse(index) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

private fun String.versionParts(): List<Int> {
    return substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { part -> part.toIntOrNull() ?: 0 }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}

private data class P2pDetails(
    val nodeId: String?,
    val directAddrs: List<String>,
    val relayAddrs: List<String>,
) {
    companion object {
        fun from(nodeAddrJson: String?): P2pDetails {
            if (nodeAddrJson.isNullOrBlank()) {
                return P2pDetails(null, emptyList(), emptyList())
            }
            return runCatching {
                val json = JSONObject(nodeAddrJson)
                val direct = mutableListOf<String>()
                val relay = mutableListOf<String>()
                val addrs = json.optJSONArray("addrs")
                if (addrs != null) {
                    for (index in 0 until addrs.length()) {
                        val item = addrs.optJSONObject(index) ?: continue
                        val keys = item.keys()
                        while (keys.hasNext()) {
                            when (val key = keys.next()) {
                                "Ip" -> direct += item.optString(key)
                                "Relay" -> relay += item.optString(key)
                            }
                        }
                    }
                }
                P2pDetails(
                    nodeId = json.optString("id").takeIf { it.isNotBlank() },
                    directAddrs = direct,
                    relayAddrs = relay,
                )
            }.getOrElse {
                P2pDetails(null, emptyList(), emptyList())
            }
        }
    }
}
