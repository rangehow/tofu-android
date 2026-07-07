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
    onSubmit: (alias: String, url: String, auth: AuthType, secret: String) -> Unit,
) {
    var alias by remember { mutableStateOf(editing?.alias ?: "") }
    var url by remember { mutableStateOf(editing?.baseUrl ?: "") }
    var auth by remember { mutableStateOf(editing?.authType ?: AuthType.CODE_SERVER_PASSWORD) }
    var secret by remember { mutableStateOf("") }

    val validation = ProfileForm.validate(
        alias = alias, baseUrl = url, authType = auth, secret = secret,
        existingAliases = existingAliases,
        editingAlias = editing?.alias,
        secretAlreadyStored = secretAlreadyStored,
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

            AuthTypePicker(auth) { auth = it }

            if (auth == AuthType.CODE_SERVER_PASSWORD) {
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = {
                        Text(if (secretAlreadyStored) "Password (leave blank to keep)" else "Password")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = validation.errors.containsKey("secret"),
                    supportingText = { validation.errors["secret"]?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { onSubmit(alias.trim(), url.trim(), auth, secret) },
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
