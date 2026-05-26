package dev.wispshell.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wispshell.app.data.WispRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrySetupScreen(repository: WispRepository, onDone: () -> Unit) {
    RegistryUrlScreen(
        title = "Set Registry URL",
        actionLabel = "Continue",
        repository = repository,
        onBack = null,
        onSaved = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: WispRepository, onBack: () -> Unit) {
    RegistryUrlScreen(
        title = "Settings",
        actionLabel = "Save",
        repository = repository,
        onBack = onBack,
        onSaved = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistryUrlScreen(
    title: String,
    actionLabel: String,
    repository: WispRepository,
    onBack: (() -> Unit)?,
    onSaved: () -> Unit,
) {
    var registryUrl by remember { mutableStateOf(repository.cloudUrl) }
    var error by remember { mutableStateOf<String?>(null) }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
}
