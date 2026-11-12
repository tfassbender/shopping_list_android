package com.tfassbender.shoppinglist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.remote.displayAddress
import com.tfassbender.shoppinglist.data.repo.ServerProblem
import com.tfassbender.shoppinglist.ui.common.AppButton
import com.tfassbender.shoppinglist.ui.common.AppSheet
import com.tfassbender.shoppinglist.ui.common.AppTextField
import com.tfassbender.shoppinglist.ui.common.ButtonVariant
import com.tfassbender.shoppinglist.ui.common.LinkText
import com.tfassbender.shoppinglist.ui.common.LocalAppContainer
import com.tfassbender.shoppinglist.ui.common.SheetActions
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * The server address: a text field, checked before it's used (a shopping list server must answer there).
 * Logged in, changing it logs out - [pendingChanges] unsent changes would be lost, so the sheet says so.
 * App-only (the web app always talks to the server it came from).
 */
@Composable
fun ServerSheet(loggedIn: Boolean, pendingChanges: Int, onDismiss: () -> Unit) {
    val server = LocalAppContainer.current.server
    val current by server.url.collectAsStateWithLifecycle()
    val default = server.defaultUrl
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(current.displayAddress()) }
    var problem by remember { mutableStateOf<ServerProblem?>(null) }
    var pending by remember { mutableStateOf(false) }
    fun run(action: suspend () -> ServerProblem?) {
        if (pending) return
        pending = true
        problem = null
        scope.launch {
            val result = action()
            pending = false
            if (result == null) onDismiss() else problem = result
        }
    }
    val save = { if (address.isNotBlank()) run { server.change(address) } }

    AppSheet(stringResource(R.string.server_title), onDismiss) {
        Text(stringResource(R.string.server_hint), color = colors.muted)
        AppTextField(
            address,
            {
                address = it
                problem = null
            },
            stringResource(R.string.server_address),
            maxLength = 300,
            keyboardType = KeyboardType.Uri,
            isError = problem != null,
            onImeAction = save,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.server_default, default.displayAddress()),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (current != default) LinkText(stringResource(R.string.server_useDefault), { run { server.reset(); null } })
        }
        if (loggedIn) {
            Text(stringResource(R.string.server_logoutWarning), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            if (pendingChanges > 0) {
                Text(stringResource(R.string.server_pendingWarning, pendingChanges.toString()), style = MaterialTheme.typography.bodySmall, color = colors.danger)
            }
        }
        problem?.let { Text(problemText(it), style = MaterialTheme.typography.bodySmall, color = colors.danger) }
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.common_save), { save() }, enabled = address.isNotBlank() && !pending)
        }
    }
}

@Composable
private fun problemText(problem: ServerProblem): String = stringResource(
    when (problem) {
        ServerProblem.INVALID -> R.string.server_INVALID
        ServerProblem.NOT_HTTPS -> R.string.server_NOT_HTTPS
        ServerProblem.UNREACHABLE -> R.string.server_UNREACHABLE
        ServerProblem.NOT_A_SHOPPING_LIST_SERVER -> R.string.server_NOT_A_SHOPPING_LIST_SERVER
    },
)
