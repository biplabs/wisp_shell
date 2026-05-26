package com.biplabs.wisp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.biplabs.wisp.data.BoundDaemon
import com.biplabs.wisp.data.RendezvousInfo
import com.biplabs.wisp.data.WispRepository
import com.biplabs.wisp.terminal.ConnectionState
import com.biplabs.wisp.terminal.WispTermuxTerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(repository: WispRepository, daemon: BoundDaemon, onBack: () -> Unit) {
    var rendezvous by remember(daemon.bindingId) { mutableStateOf<RendezvousInfo?>(null) }
    var error by remember(daemon.bindingId) { mutableStateOf<String?>(null) }
    var retryNonce by remember(daemon.bindingId) { mutableIntStateOf(0) }
    var connectionState by remember(daemon.bindingId) { mutableStateOf<ConnectionState?>(null) }
    var connectionError by remember(daemon.bindingId) { mutableStateOf<String?>(null) }
    var showConnectionDialog by remember(daemon.bindingId) { mutableStateOf(false) }
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

    LaunchedEffect(daemon.bindingId, retryNonce) {
        rendezvous = null
        error = null
        connectionState = ConnectionState.Connecting
        showConnectionDialog = true
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            runCatching {
                withContext(Dispatchers.IO) { repository.rendezvous(daemon) }
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
        error = lastError?.message ?: "Could not fetch rendezvous"
    }

    LaunchedEffect(connectionState, connectionError) {
        if (connectionState == ConnectionState.Attached && connectionError == null) {
            delay(1_500)
            showConnectionDialog = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Back",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(end = 10.dp),
                    )
                    Text(
                        text = daemon.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            when {
                rendezvous != null -> WispTermuxTerminalView(
                    rendezvous = rendezvous,
                    clientPrivateKey = repository.clientPrivateKey,
                    clientDeviceId = repository.clientDeviceId,
                    bindingId = daemon.bindingId,
                    modifier = Modifier.fillMaxSize(),
                    onConnectionState = { state ->
                        connectionState = state
                        showConnectionDialog = true
                    },
                    onConnectionError = { message ->
                        connectionError = message
                        showConnectionDialog = true
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

                else -> Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                ) {
                    Spacer(Modifier)
                }
            }
        }
    }

    if (showConnectionDialog) {
        ConnectionStatusDialog(
            daemon = daemon,
            rendezvous = rendezvous,
            bindingId = daemon.bindingId,
            state = connectionState,
            error = connectionError,
            onDismiss = {
                if (connectionState == ConnectionState.Attached && connectionError == null) {
                    showConnectionDialog = false
                }
            },
        )
    }
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
) {
    val details = remember(rendezvous?.irohNodeAddrJson) {
        P2pDetails.from(rendezvous?.irohNodeAddrJson)
    }
    val title = when {
        error != null -> "Connection issue"
        state == ConnectionState.Attached -> "Connected"
        state == ConnectionState.Disconnected -> "Disconnected"
        else -> "Connecting"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (error != null || state == ConnectionState.Disconnected) {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                DetailLine("Daemon", daemon.displayName)
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
