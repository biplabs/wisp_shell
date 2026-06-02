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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import com.biplabs.wisp.data.TerminalInputMode
import com.biplabs.wisp.data.WispRepository
import com.biplabs.wisp.terminal.ConnectionState
import com.biplabs.wisp.terminal.TransportPathStatus
import com.biplabs.wisp.terminal.WispTermuxTerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

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
    var rendezvousByTab by remember { mutableStateOf<Map<String, RendezvousInfo?>>(emptyMap()) }
    var showPathDiagnostics by remember { mutableStateOf(false) }
    val wifiStatus = rememberWifiStatus()
    val selectedTab = selectedTabId?.let { id -> tabs.firstOrNull { it.id == id } }
    val selectedTransportPath = selectedTabId?.let { transportPaths[it] }
    val selectedRendezvous = selectedTabId?.let { rendezvousByTab[it] }
    val inputMode = repository.terminalInputMode
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
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 8.dp),
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
                                IconButton(
                                    onClick = {
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
                                    },
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(32.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                ) {
                                    Text(
                                        text = "+",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        WifiStatusIcon(
                            status = wifiStatus,
                            transportPath = selectedTransportPath,
                            onPathClick = {
                                if (selectedTransportPath != null || selectedTab != null) {
                                    showPathDiagnostics = true
                                }
                            },
                        )
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
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
                    val active = tab.id == selectedTabId && !showSettings
                    val effectiveInputMode = inputMode.effectiveInputMode(transportPaths[tab.id])
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
                            onRendezvousChanged = { info ->
                                rendezvousByTab = rendezvousByTab + (tab.id to info)
                            },
                            networkChangeNonce = wifiStatus.changeNonce,
                            networkReady = wifiStatus.connected && wifiStatus.validated,
                            inputMode = effectiveInputMode,
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

    if (showPathDiagnostics && selectedTab != null) {
        PathDiagnosticsDialog(
            daemon = selectedTab.daemon,
            rendezvous = selectedRendezvous,
            transportPath = selectedTransportPath,
            wifiStatus = wifiStatus,
            onDismiss = { showPathDiagnostics = false },
        )
    }

    if (showSettings) {
        SettingsScreen(
            repository = repository,
            onBack = { showSettings = false },
            onPair = {
                showSettings = false
                onPair()
            },
        )
    }
}

private fun TerminalInputMode.effectiveInputMode(
    transportPath: TransportPathStatus?,
): TerminalInputMode {
    if (this != TerminalInputMode.Auto) return this
    val latencyMs = transportPath?.latencyMs ?: return TerminalInputMode.Predictive
    return when {
        latencyMs <= AUTO_SYNC_MAX_LATENCY_MS -> TerminalInputMode.Sync
        latencyMs <= AUTO_PREDICTIVE_MAX_LATENCY_MS -> TerminalInputMode.Predictive
        else -> TerminalInputMode.Line
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
    onRendezvousChanged: (RendezvousInfo?) -> Unit,
    networkChangeNonce: Int,
    networkReady: Boolean,
    inputMode: TerminalInputMode,
) {
    var rendezvous by remember(tab.daemon.bindingId) { mutableStateOf<RendezvousInfo?>(null) }
    var error by remember(tab.daemon.bindingId) { mutableStateOf<String?>(null) }
    var retryNonce by remember(tab.daemon.bindingId) { mutableIntStateOf(0) }
    var reconnectNonce by remember(tab.id) { mutableIntStateOf(0) }
    var connectionState by remember(tab.id) { mutableStateOf<ConnectionState?>(null) }
    var connectionError by remember(tab.id) { mutableStateOf<String?>(null) }
    var showConnectionDialog by remember(tab.id) { mutableStateOf(false) }
    var sendTerminalInput by remember(tab.id) { mutableStateOf<((String) -> Unit)?>(null) }
    var lineInput by remember(tab.id) { mutableStateOf("") }
    var lineFocusNonce by remember(tab.id) { mutableIntStateOf(0) }
    var lineKeyboardToggleNonce by remember(tab.id) { mutableIntStateOf(0) }

    LaunchedEffect(tab.daemon.bindingId, retryNonce) {
        rendezvous = null
        onRendezvousChanged(null)
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
                onRendezvousChanged(it)
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

    LaunchedEffect(active, inputMode, connectionState, connectionError, showConnectionDialog, sendTerminalInput) {
        if (
            active &&
            inputMode == TerminalInputMode.Line &&
            connectionState == ConnectionState.Attached &&
            connectionError == null &&
            !showConnectionDialog &&
            sendTerminalInput != null
        ) {
            lineFocusNonce += 1
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

    LaunchedEffect(active, networkChangeNonce, networkReady) {
        if (!active || networkChangeNonce == 0) return@LaunchedEffect
        onTransportPathChanged(null)
        if (!networkReady) {
            connectionError = null
            connectionState = ConnectionState.Connecting
            showConnectionDialog = true
            return@LaunchedEffect
        }
        delay(1_500)
        onTransportPathChanged(null)
        connectionError = null
        error = null
        connectionState = ConnectionState.Connecting
        showConnectionDialog = true
        retryNonce++
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
                    inputMode = inputMode,
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
                    onKeyboardRequest = {
                        lineKeyboardToggleNonce += 1
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
        if (!(active && showConnectionDialog)) {
            TerminalShortcutBar(
                enabled = active && sendTerminalInput != null,
                inputMode = inputMode,
                lineInput = lineInput,
                lineFocusNonce = lineFocusNonce,
                lineKeyboardToggleNonce = lineKeyboardToggleNonce,
                onLineInputChanged = { lineInput = it },
                onSend = { input -> sendTerminalInput?.invoke(input) },
                onSendLine = {
                    val text = lineInput
                    sendTerminalInput?.invoke("$text\r")
                    lineInput = ""
                },
            )
        }
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
    inputMode: TerminalInputMode,
    lineInput: String,
    lineFocusNonce: Int,
    lineKeyboardToggleNonce: Int,
    onLineInputChanged: (String) -> Unit,
    onSend: (String) -> Unit,
    onSendLine: () -> Unit,
) {
    val lineFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var lineInputFocused by remember { mutableStateOf(false) }

    LaunchedEffect(lineFocusNonce, inputMode, enabled) {
        if (lineFocusNonce > 0 && inputMode == TerminalInputMode.Line && enabled) {
            lineFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(lineKeyboardToggleNonce, inputMode, enabled) {
        if (lineKeyboardToggleNonce <= 0 || inputMode != TerminalInputMode.Line || !enabled) return@LaunchedEffect
        if (lineInputFocused) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        } else {
            lineFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (inputMode == TerminalInputMode.Line) {
                val textColor = MaterialTheme.colorScheme.onSurface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (lineInputFocused) 1.dp else 0.dp,
                            color = if (lineInputFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    BasicTextField(
                        value = lineInput,
                        onValueChange = onLineInputChanged,
                        enabled = enabled,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = textColor,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSendLine() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(lineFocusRequester)
                            .onFocusChanged { lineInputFocused = it.isFocused },
                        decorationBox = { innerTextField ->
                            if (lineInput.isEmpty()) {
                                Text(
                                    text = ">_ Enter terminal command...",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TerminalShortcutButton(
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onSend("\u0003") },
                ) {
                    Text(
                        text = "Ctrl-C",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
                TerminalShortcutButton(
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onSend("\u001b[A") },
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Up",
                        modifier = Modifier.size(20.dp)
                    )
                }
                TerminalShortcutButton(
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onSend("\u001b[B") },
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Down",
                        modifier = Modifier.size(20.dp)
                    )
                }
                TerminalShortcutButton(
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onSend("\u001b[C") },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Right",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalShortcutButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        content()
    }
}

private data class WifiStatus(
    val connected: Boolean,
    val validated: Boolean,
    val hasIpv4: Boolean,
    val hasGlobalIpv6: Boolean,
    val changeNonce: Int,
)

private fun WifiStatus.description(): String = when {
    connected && validated -> "connected, internet validated, ${addressFamilyDescription()}"
    connected -> "connected, internet not validated, ${addressFamilyDescription()}"
    else -> "disconnected"
}

private fun WifiStatus.addressFamilyDescription(): String = when {
    hasIpv4 && hasGlobalIpv6 -> "IPv4 + IPv6"
    hasGlobalIpv6 -> "IPv6 only"
    hasIpv4 -> "IPv4 only"
    else -> "no IP address"
}

@Composable
private fun rememberWifiStatus(): WifiStatus {
    val context = LocalContext.current
    var changeNonce by remember(context) { mutableIntStateOf(0) }
    var status by remember(context) { mutableStateOf(context.currentWifiStatus(changeNonce)) }
    DisposableEffect(context) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@DisposableEffect onDispose {}
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() {
                mainHandler.post {
                    val nextStatus = context.currentWifiStatus(changeNonce)
                    if (status.hasSameConnectivity(nextStatus)) {
                        status = nextStatus
                    } else {
                        changeNonce += 1
                        status = context.currentWifiStatus(changeNonce)
                    }
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
            status = context.currentWifiStatus(changeNonce)
        }
        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
    return status
}

private fun WifiStatus.hasSameConnectivity(other: WifiStatus): Boolean =
    connected == other.connected &&
        validated == other.validated &&
        hasIpv4 == other.hasIpv4 &&
        hasGlobalIpv6 == other.hasGlobalIpv6

private fun Context.currentWifiStatus(changeNonce: Int): WifiStatus {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiStatus(
                connected = false,
                validated = false,
                hasIpv4 = false,
                hasGlobalIpv6 = false,
                changeNonce = changeNonce,
            )
    val activeNetwork = connectivityManager.activeNetwork
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
    val linkAddresses = connectivityManager
        .getLinkProperties(activeNetwork)
        ?.linkAddresses
        .orEmpty()
    return WifiStatus(
        connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        hasIpv4 = linkAddresses.any { it.address is Inet4Address },
        hasGlobalIpv6 = linkAddresses.any { it.address.isGlobalIpv6() },
        changeNonce = changeNonce,
    )
}

@Composable
private fun WifiStatusIcon(
    status: WifiStatus,
    transportPath: TransportPathStatus?,
    onPathClick: () -> Unit,
) {
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
    val transportTextStyle = MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp)
    Row(
        modifier = Modifier.height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(20.dp)
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
                modifier = Modifier.clickable(onClick = onPathClick),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = transportLabel,
                    style = transportTextStyle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (latencyLabel != null) {
                    Text(
                        text = latencyLabel,
                        style = transportTextStyle,
                        color = MaterialTheme.colorScheme.onSurface,
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
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ">_",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Pick a Shell",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Choose a paired Linux daemon to open a terminal tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
        )

        if (error != null && agents.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                ListItem(
                    headlineContent = { Text("Registry issue", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(error) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(0.9f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            agents.forEach { daemon ->
                val isOnline = daemon.status == "online"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isOnline) { onOpen(daemon) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOnline) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        }
                    ),
                    border = if (isOnline) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        null
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = if (isOnline) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = if (isOnline) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = daemon.displayName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            color = if (isOnline) {
                                                Color(0x00, 0xE5, 0xFF) // Neon Cyan
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                            shape = CircleShape
                                        )
                                )
                                Text(
                                    text = daemon.status.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isOnline) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (agents.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (error == null) "No paired daemons" else "Registry unreachable",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = error ?: "Pair an agent to open interactive terminal sessions from this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onPair,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Pair Agent", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onPair) {
                    Text("+ Pair another agent", fontWeight = FontWeight.Bold)
                }
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
private fun PathDiagnosticsDialog(
    daemon: BoundDaemon,
    rendezvous: RendezvousInfo?,
    transportPath: TransportPathStatus?,
    wifiStatus: WifiStatus,
    onDismiss: () -> Unit,
) {
    val details = remember(rendezvous?.irohNodeAddrJson) {
        P2pDetails.from(rendezvous?.irohNodeAddrJson)
    }
    val path = when (transportPath?.path) {
        "direct" -> "Direct"
        "relay" -> "Relay"
        "tcp" -> "TCP"
        "unknown" -> "Unknown"
        null -> "Waiting"
        else -> transportPath.path
    }
    val explanation = relayExplanation(transportPath, details, wifiStatus)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Path diagnostics") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailLine("Daemon", daemon.displayName)
                DetailLine("Current path", path)
                DetailLine("Latency", transportPath?.latencyMs?.let { "${it} ms" } ?: "unknown")
                DetailLine("Why", explanation)
                DetailLine("Wi-Fi", wifiStatus.description())
                DetailLine("Registry status", rendezvous?.status ?: "fetching")
                DetailLine("Agent version", rendezvous?.agentVersion ?: "unknown")
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

private fun relayExplanation(
    transportPath: TransportPathStatus?,
    details: P2pDetails,
    wifiStatus: WifiStatus,
): String {
    return when (transportPath?.path) {
        "direct" -> "A direct UDP path is selected."
        "relay" -> when {
            details.directAddrs.isEmpty() ->
                "No direct address is currently advertised, so the relay is required."
            details.hasPrivateOnlyIpv4DirectAddrs() ->
                "The daemon direct address is private IPv4, so it only works on the same LAN."
            !wifiStatus.hasGlobalIpv6 && details.hasOnlyGlobalIpv6DirectAddrs() ->
                "The daemon advertises only global IPv6 direct addresses, but this phone network has no global IPv6."
            !wifiStatus.hasIpv4 && details.hasOnlyIpv4DirectAddrs() ->
                "The daemon advertises only IPv4 direct addresses, but this phone network has no IPv4."
            else ->
                "Both sides have a matching address family, so UDP is likely blocked or NAT traversal failed on one side."
        }
        "tcp" -> "Using the local TCP fallback instead of iroh P2P."
        "unknown" -> "Iroh has not reported a selected path yet."
        else -> "Waiting for iroh path selection."
    }
}

private enum class AddressFamily {
    Ipv4,
    GlobalIpv6,
    Other,
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
    val borderStroke = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = borderStroke,
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )

            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close tab",
                modifier = Modifier
                    .size(15.dp)
                    .clickable(onClick = onClose),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

private const val AUTO_SYNC_MAX_LATENCY_MS = 80
private const val AUTO_PREDICTIVE_MAX_LATENCY_MS = 220

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
        agentVersion?.takeIf { it.isNotBlank() }?.let { normalizedAgent ->
            when (compareMajorMinorVersions(normalizedAgent, appVersion)) {
                1 -> add("App is older than the agent. Update the app.")
                -1 -> add("Agent is older than the app. Update the agent.")
            }
        }

        registryVersion?.takeIf { it.isNotBlank() }?.let { normalizedRegistry ->
            when (compareMajorMinorVersions(normalizedRegistry, appVersion)) {
                1 -> add("App is older than the registry. Update the app.")
                -1 -> add("Registry is older than the app. Update the registry.")
            }
        }
    }
}

private fun compareMajorMinorVersions(left: String, right: String): Int? {
    val leftParts = left.majorMinorVersionParts() ?: return null
    val rightParts = right.majorMinorVersionParts() ?: return null
    return when {
        leftParts.first != rightParts.first -> leftParts.first.compareTo(rightParts.first)
        leftParts.second != rightParts.second -> leftParts.second.compareTo(rightParts.second)
        else -> 0
    }
}

private fun String.majorMinorVersionParts(): Pair<Int, Int>? {
    val parts = substringBefore('-')
        .substringBefore('+')
        .split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return major to minor
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private data class P2pDetails(
    val nodeId: String?,
    val directAddrs: List<String>,
    val relayAddrs: List<String>,
) { 
    fun hasOnlyGlobalIpv6DirectAddrs(): Boolean {
        return directAddressFamilies().let { families ->
            families.isNotEmpty() && families.all { it == AddressFamily.GlobalIpv6 }
        }
    }

    fun hasOnlyIpv4DirectAddrs(): Boolean {
        return directAddressFamilies().let { families ->
            families.isNotEmpty() && families.all { it == AddressFamily.Ipv4 }
        }
    }

    fun hasPrivateOnlyIpv4DirectAddrs(): Boolean {
        val addrs = directInetAddresses()
        return addrs.isNotEmpty() && addrs.all { address ->
            address is Inet4Address && address.isPrivateIpv4()
        }
    }

    private fun directAddressFamilies(): List<AddressFamily> {
        return directInetAddresses().map { address ->
            when {
                address is Inet4Address -> AddressFamily.Ipv4
                address.isGlobalIpv6() -> AddressFamily.GlobalIpv6
                else -> AddressFamily.Other
            }
        }
    }

    private fun directInetAddresses(): List<InetAddress> {
        return directAddrs.mapNotNull { addr ->
            runCatching { InetAddress.getByName(addr.socketHost()) }.getOrNull()
        }
    }

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

private fun InetAddress.isGlobalIpv6(): Boolean {
    return this is Inet6Address &&
        !isAnyLocalAddress &&
        !isLinkLocalAddress &&
        !isLoopbackAddress &&
        !isMulticastAddress &&
        !isSiteLocalAddress
}

private fun Inet4Address.isPrivateIpv4(): Boolean {
    val bytes = address.map { it.toInt() and 0xff }
    return bytes[0] == 10 ||
        bytes[0] == 127 ||
        (bytes[0] == 172 && bytes[1] in 16..31) ||
        (bytes[0] == 192 && bytes[1] == 168) ||
        (bytes[0] == 169 && bytes[1] == 254)
}

private fun String.socketHost(): String {
    val value = trim()
    if (value.startsWith("[")) {
        return value.substringAfter("[").substringBefore("]")
    }
    return value.substringBeforeLast(":", value)
}
