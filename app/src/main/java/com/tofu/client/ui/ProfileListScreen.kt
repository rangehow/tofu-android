package com.tofu.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tofu.client.data.Profile
import kotlinx.coroutines.CoroutineScope

/**
 * Profile list / switcher — the app's home. Tap a card to activate (log in +
 * open the WebView); edit / delete via the row actions; add via the FAB. A
 * status banner surfaces login progress / errors from the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)   // Material3 TopAppBar is still experimental
@Composable
fun ProfileListScreen(
    profiles: List<Profile>,
    status: UiStatus,
    onActivate: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onAdd: () -> Unit,
    scope: CoroutineScope,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Tofu servers") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add server") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusBanner(status)
            if (profiles.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(profiles, key = { it.id }) { p ->
                        ProfileRow(p, onActivate, onEdit, onDelete, scope)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: UiStatus) {
    val text = when (status) {
        is UiStatus.LoggingIn -> "Signing in to ${status.alias}…"
        is UiStatus.Error -> "Error: ${status.message}"
        is UiStatus.BadCredentials -> "Wrong password for ${status.profile.alias} — edit it to fix."
        is UiStatus.NeedsSso -> "This server needs interactive sign-in — opening…"
        UiStatus.Idle -> null
    } ?: return
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No servers yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            "Tap “Add server” to remember a Tofu URL and its password, " +
                "so you never copy-paste or re-type them again.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProfileRow(
    p: Profile,
    onActivate: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    scope: CoroutineScope,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).padding(end = 8.dp),
            ) {
                Text(p.alias, style = MaterialTheme.typography.titleMedium)
                Text(
                    p.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onEdit(p) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = { onDelete(p) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
        // Trailing row: Open + (when a project path is set) the supervisor
        // Start/Stop controls. These live HERE, not in the WebView, because a
        // stopped server can't be opened — you must be able to start it from
        // the list before there is any page to show.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.TextButton(onClick = { onActivate(p) }) {
                Text("Open")
            }
            if (!p.projectPath.isNullOrBlank()) {
                SupervisorControls(profile = p, scope = scope)
            }
        }
    }
}
