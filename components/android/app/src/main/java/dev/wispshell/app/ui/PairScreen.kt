package dev.wispshell.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wispshell.app.data.PairingCodeSession
import dev.wispshell.app.data.WispRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(repository: WispRepository, onDone: () -> Unit) {
    var pairing by remember { mutableStateOf<PairingCodeSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var waitingForApproval by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pairing?.expiresAt) {
        val expiresAt = pairing?.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return@LaunchedEffect
        while (true) {
            val seconds = Duration.between(Instant.now(), expiresAt).seconds.coerceAtLeast(0)
            remainingSeconds = seconds.toInt()
            if (seconds <= 0) {
                waitingForApproval = false
                loading = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.startCodePairing()
                    }
                }.onSuccess {
                    pairing = it
                    waitingForApproval = true
                }.onFailure {
                    error = it.message
                }
                loading = false
                return@LaunchedEffect
            }
            delay(1_000)
        }
    }

    LaunchedEffect(pairing) {
        val session = pairing ?: return@LaunchedEffect
        waitingForApproval = true
        while (true) {
            if (remainingSeconds <= 0) {
                delay(250)
                continue
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.boundDaemons()
                }
            }
            result.onSuccess { daemons ->
                if (daemons.isNotEmpty()) {
                    waitingForApproval = false
                    onDone()
                    return@LaunchedEffect
                }
            }
            result.onFailure {
                error = it.message
            }
            delay(1_500)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pair WispShell") }) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pairing == null) {
                Text(
                    text = "Generate a short pairing code, then approve it from your Linux agent. This tablet will watch the registry and continue automatically when the agent accepts it.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Registry: ${repository.cloudUrl}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.startCodePairing()
                                }
                            }.onSuccess {
                                pairing = it
                                waitingForApproval = true
                            }.onFailure {
                                error = it.message
                            }
                            loading = false
                        }
                    },
                    enabled = !loading,
                ) {
                    Text(if (loading) "Creating..." else "Generate Code")
                }
            }

            pairing?.let { session ->
                Text(
                    text = session.code,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = "wispshelld pair ${session.code}",
                    fontFamily = FontFamily.Monospace,
                )
                Text("Registry: ${repository.cloudUrl}")
                Text(
                    if (remainingSeconds > 0) {
                        "Expires in ${formatRemaining(remainingSeconds)}"
                    } else {
                        "Code expired"
                    },
                )
                Text(statusText(waitingForApproval, loading, remainingSeconds))
                OutlinedButton(onClick = onDone) {
                    Text("Cancel")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatRemaining(seconds: Int): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "%d:%02d".format(minutes, remainder)
}

private fun statusText(waitingForApproval: Boolean, loading: Boolean, remainingSeconds: Int): String {
    return when {
        loading -> "Refreshing code..."
        waitingForApproval && remainingSeconds > 0 -> "Waiting for approval..."
        remainingSeconds <= 0 -> "Refreshing code..."
        else -> "Approved"
    }
}
