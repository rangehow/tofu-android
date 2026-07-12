package com.tofu.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tofu.client.data.Profile
import com.tofu.client.session.SupervisorClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Start/Stop controls for the Tofu server behind [profile], driving the
 * host-side supervisor via [client]. Rendered only when the profile has a
 * projectPath.
 *
 * Token handling: the supervisor requires a Bearer token (defence in depth on
 * top of the code-server cookie). If none is stored, the first action prompts
 * for it and persists it via [saveToken]. [tokenFor] reads it back.
 *
 * All network calls run on [Dispatchers.IO]; after a start we poll /status (the
 * server binds asynchronously — /start returns immediately by design).
 */
@Composable
fun SupervisorControls(
    profile: Profile,
    scope: CoroutineScope,
    tokenFor: () -> String?,
    saveToken: (String) -> Unit,
    client: SupervisorClient = SupervisorClient(),
    modifier: Modifier = Modifier,
) {
    var running by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askToken by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }  // "start" | "stop" | "status"

    fun run(action: String) {
        val token = tokenFor()
        if (token.isNullOrBlank()) { pendingAction = action; askToken = true; return }
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                when (action) {
                    "start" -> client.start(profile, token)
                    "stop" -> client.stop(profile, token)
                    else -> client.status(profile, token)
                }
            }
            when (res) {
                is SupervisorClient.Result.Ok -> {
                    running = res.running
                    message = null
                    // /start returns before the port binds → poll a few times,
                    // stopping as soon as the server reports running.
                    if (action == "start" && !res.running) {
                        for (i in 0 until 6) {
                            delay(2000)
                            val s = withContext(Dispatchers.IO) { client.status(profile, token) }
                            if (s is SupervisorClient.Result.Ok && s.running) {
                                running = true
                                break
                            }
                        }
                    }
                }
                is SupervisorClient.Result.Failed -> {
                    message = res.message
                    if (res.code == 401 || res.code == 503) {
                        // Bad/missing token → re-prompt.
                        pendingAction = action; askToken = true
                    }
                }
            }
            busy = false
        }
    }

    Column(modifier) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when (running) {
                true -> "running"
                false -> "stopped"
                null -> "—"
            }
            Text("Server: $label", Modifier.padding(end = 8.dp))
            Button(onClick = { run("start") }, enabled = !busy) { Text("Start") }
            TextButton(onClick = { run("stop") }, enabled = !busy) { Text("Stop") }
            TextButton(onClick = { run("status") }, enabled = !busy) { Text("Refresh") }
        }
        message?.let { Text(it, Modifier.padding(horizontal = 8.dp)) }
    }

    if (askToken) {
        var entry by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askToken = false; pendingAction = null },
            title = { Text("Supervisor token") },
            text = {
                OutlinedTextField(
                    value = entry, onValueChange = { entry = it },
                    label = { Text("Bearer token (TOFU_SUPERVISOR_TOKEN)") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (entry.isNotBlank()) {
                        saveToken(entry.trim())
                        askToken = false
                        pendingAction?.let { run(it) }
                        pendingAction = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { askToken = false; pendingAction = null }) { Text("Cancel") }
            },
        )
    }
}
