package com.brokerbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.CreateMemberRequest
import com.brokerbuddy.core.model.Role
import com.brokerbuddy.core.model.UpdateMemberRequest
import com.brokerbuddy.core.model.User
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.DropdownField
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = rememberLoad { container.api.call { me() } }
    val team = rememberLoad { container.api.call { team().members } }
    var adding by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("Settings & team", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            LoadContent(me) { info ->
                Column {
                    Text(info.brokerage.name, style = MaterialTheme.typography.titleLarge)
                    Text("${info.user.name} · ${info.user.email} · ${info.user.role.name.lowercase()}")
                    val manager = info.user.role == Role.OWNER || info.user.role == Role.ADMIN

                    SectionTitle("Team")
                    LoadContent(team) { members ->
                        Column {
                            members.forEach { m ->
                                MemberRow(m, canManage = manager && m.role != Role.OWNER && m.id != info.user.id &&
                                    (info.user.role == Role.OWNER || m.role == Role.AGENT)) { active ->
                                    scope.launch {
                                        container.api.call { updateMember(m.id, UpdateMemberRequest(active = active)) }
                                            .onSuccess { team.reload() }
                                            .onFailure { toast(context, it.message ?: "Update failed") }
                                    }
                                }
                            }
                        }
                    }
                    if (manager) {
                        OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Add team member") }
                    }
                    if (manager) WhatsAppSetupSection()
                    if (adding) {
                        AddMemberDialog(
                            canAddAdmin = info.user.role == Role.OWNER,
                            onDismiss = { adding = false },
                            onAdded = { adding = false; team.reload() },
                        )
                    }
                }
            }
            CallerSetupSection()
            SectionTitle("Account")
            Button(onClick = { scope.launch { container.sessionStore.signOut() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun MemberRow(m: User, canManage: Boolean, onActiveChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(m.name) },
        supportingContent = { Text("${m.email} · ${m.role.name.lowercase()}${if (!m.active) " · deactivated" else ""}") },
        trailingContent = if (canManage) {
            { Switch(checked = m.active, onCheckedChange = onActiveChange) }
        } else {
            null
        },
    )
}

@Composable
private fun AddMemberDialog(canAddAdmin: Boolean, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    var name by rememberText()
    var email by rememberText()
    var password by rememberText()
    var phone by rememberText()
    var role by remember { mutableStateOf(Role.AGENT) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add team member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    email, { email = it }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("Temporary password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("At least 8 characters; share it privately") },
                )
                OutlinedTextField(
                    phone, { phone = it }, label = { Text("Mobile (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    supportingText = { Text("Recognises portal leads they forward on WhatsApp") },
                )
                if (canAddAdmin) {
                    DropdownField("Role", listOf(Role.AGENT, Role.ADMIN), role, { it.name.lowercase() }, { if (it != null) role = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && email.isNotBlank() && password.length >= 8 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        api.call { addMember(CreateMemberRequest(name.trim(), email.trim(), password, role, phone.trim().ifEmpty { null })) }
                            .onSuccess { onAdded() }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
