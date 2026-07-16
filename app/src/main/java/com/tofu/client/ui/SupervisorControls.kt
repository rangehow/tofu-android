package com.tofu.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
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
import com.tofu.client.session.SupervisorUrl
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
 * No auth: Tofu is a personal app and the code-server password already gates
 * the whole proxy the supervisor sits behind, so there is nothing to type here.
 *
 * All network calls run on [Dispatchers.IO]; after a start we poll /status (the
 * server binds asynchronously — /start returns immediately by design).
 */
@Composable
fun SupervisorControls(
    profile: Profile,
    scope: CoroutineScope,
    client: SupervisorClient = SupervisorClient(),
    modifier: Modifier = Modifier,
) {
    var running by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(action: String) {
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                when (action) {
                    "start" -> client.start(profile)
                    "stop" -> client.stop(profile)
                    else -> client.status(profile)
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
                            val s = withContext(Dispatchers.IO) { client.status(profile) }
                            if (s is SupervisorClient.Result.Ok && s.running) {
                                running = true
                                break
                            }
                        }
                    }
                }
                is SupervisorClient.Result.Failed -> {
                    // Map the opaque HTTP status (most often a 5xx from the
                    // code-server proxy when supervisor.py isn't running) into
                    // an actionable explanation instead of a bare "HTTP 500".
                    message = SupervisorUrl.explainFailure(res.code, res.message)
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
}
