package com.tofu.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tofu.client.data.AuthType
import com.tofu.client.data.Profile
import com.tofu.client.session.ProfileForm
import com.tofu.client.session.ServerUrl

/**
 * Add / edit-server form. Field state is local; validation runs through the
 * pure [ProfileForm.validate] so the same rules the unit tests cover gate the
 * Save button. On save the host calls submitAdd / submitEdit on the ViewModel.
 */
@Composable
fun AddEditScreen(
    editing: Profile?,
    existingAliases: Set<String>,
    secretAlreadyStored: Boolean,
    onCancel: () -> Unit,
    onSubmit: (alias: String, url: String, auth: AuthType, secret: String, projectPath: String) -> Unit,
    /** Returns the host whose stored password would be reused for this URL when
     *  the field is left blank, or null. Used for a proactive hint. */
    reusableHostLookup: suspend (url: String, excludeAlias: String?) -> String? =
        { _, _ -> null },
) {
    var alias by remember { mutableStateOf(editing?.alias ?: "") }
    var url by remember { mutableStateOf(editing?.baseUrl ?: "") }
    // Auth defaults are URL-aware for NEW profiles: a `/proxy/<port>/` URL is
    // behind a code-server password gate → CODE_SERVER_PASSWORD; a bare host →
    // NONE. We track whether the user has manually overridden the picker so we
    // stop auto-following the URL once they do (and never override an edit).
    var authTouched by remember { mutableStateOf(editing != null) }
    var auth by remember {
        mutableStateOf(editing?.authType ?: ServerUrl.defaultAuthType(url))
    }
    var secret by remember { mutableStateOf("") }
    var projectPath by remember { mutableStateOf(editing?.projectPath ?: "") }
    // Until the user picks an auth type by hand, keep it in sync with the URL
    // they're typing (add-mode only).
    LaunchedEffect(url) {
        if (!authTouched) auth = ServerUrl.defaultAuthType(url)
    }
    // Host whose saved password would be reused if the field is left blank.
    var reuseHost by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url, auth, editing?.alias) {
        reuseHost = if (auth == AuthType.CODE_SERVER_PASSWORD)
            reusableHostLookup(url.trim(), editing?.alias) else null
    }

    // A blank password is acceptable when EITHER this profile already has a
    // stored secret (edit) OR a same-host password can be reused (add/edit).
    val canOmitSecret = secretAlreadyStored || reuseHost != null
    val validation = ProfileForm.validate(
        alias = alias, baseUrl = url, authType = auth, secret = secret,
        existingAliases = existingAliases,
        editingAlias = editing?.alias,
        secretAlreadyStored = canOmitSecret,
    )

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(if (editing == null) "Add server" else "Edit server")

            OutlinedTextField(
                value = alias, onValueChange = { alias = it },
                label = { Text("Name") },
                isError = validation.errors.containsKey("alias"),
                supportingText = { validation.errors["alias"]?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Server URL (…/proxy/15000/)") },
                isError = validation.errors.containsKey("baseUrl"),
                supportingText = { validation.errors["baseUrl"]?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            AuthTypePicker(auth) { authTouched = true; auth = it }

            // Optional host path of the project this server runs from. When set,
            // the WebView screen shows Start/Stop controls that drive the
            // supervisor. Left blank → the server is "open only".
            OutlinedTextField(
                value = projectPath, onValueChange = { projectPath = it },
                label = { Text("Project path (optional — enables Start/Stop)") },
                supportingText = {
                    Text("Absolute host path, e.g. /home/dev/chatui. Leave blank for open-only.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (auth == AuthType.CODE_SERVER_PASSWORD) {
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = {
                        Text(
                            when {
                                secretAlreadyStored -> "Password (leave blank to keep)"
                                reuseHost != null -> "Password (leave blank to reuse)"
                                else -> "Password"
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = validation.errors.containsKey("secret"),
                    supportingText = {
                        val err = validation.errors["secret"]
                        when {
                            err != null -> Text(err)
                            secret.isEmpty() && reuseHost != null ->
                                Text("Will reuse the saved password for $reuseHost")
                            else -> {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { onSubmit(alias.trim(), url.trim(), auth, secret, projectPath.trim()) },
                enabled = validation.ok,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Save & connect") }

            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun AuthTypePicker(current: AuthType, onPick: (AuthType) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Authentication")
        AuthType.values().forEach { at ->
            TextButton(onClick = { onPick(at) }) {
                Text((if (at == current) "● " else "○ ") + at.label())
            }
        }
    }
}

private fun AuthType.label(): String = when (this) {
    AuthType.CODE_SERVER_PASSWORD -> "code-server password"
    AuthType.INTERACTIVE_SSO -> "Interactive SSO (sign in once)"
    AuthType.NONE -> "None"
}
