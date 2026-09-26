package com.brokerbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.ConnectWhatsAppRequest
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.formatDateTime
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import kotlinx.coroutines.launch

/**
 * Connects the brokerage's WhatsApp Business number (Meta Cloud API). Shows the webhook
 * URL and verify token to paste into the Meta app dashboard. Secrets are sent once and
 * stored encrypted on the server; they are never shown again.
 */
@Composable
fun WhatsAppSetupSection() {
    val container = appContainer()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val status = rememberLoad { container.api.call { whatsappAccount() } }
    var serverUrl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { serverUrl = container.sessionStore.current().serverUrl.trimEnd('/') }
    var editing by remember { mutableStateOf(false) }
    var phoneNumberId by rememberText()
    var wabaId by rememberText()
    var displayPhone by rememberText()
    var token by rememberText()
    var secret by rememberText()

    SectionTitle("WhatsApp Business API")
    LoadContent(status) { s ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!s.encryptionConfigured) {
                Text("The server needs DATA_ENCRYPTION_KEY before WhatsApp can be connected.", color = MaterialTheme.colorScheme.error)
            }
            val a = s.account
            if (a != null && !editing) {
                Text("Connected: ${a.displayPhone ?: a.phoneNumberId}")
                Text(
                    if (a.verifiedAt == null) "Webhook not verified yet — add it in the Meta app dashboard" else
                        "Webhook verified · last event ${a.lastEventAt?.let(::formatDateTime) ?: "none yet"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val url = serverUrl + a.webhookPath
                CopyRow("Callback URL", url) { clipboard.setText(AnnotatedString(url)); toast(context, "Copied") }
                CopyRow("Verify token", a.verifyToken) { clipboard.setText(AnnotatedString(a.verifyToken)); toast(context, "Copied") }
                Text("In Meta: WhatsApp → Configuration → Webhook, then subscribe to the \"messages\" field.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { editing = true }) { Text("Update credentials") }
            } else {
                Text(
                    "Optional. Without it, share messages or exported chats from WhatsApp into BrokerBuddy instead. See docs/WHATSAPP.md for setup.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!editing) {
                    Button(onClick = { editing = true }) { Text("Connect WhatsApp Business") }
                } else {
                    OutlinedTextField(phoneNumberId, { phoneNumberId = it }, label = { Text("Phone number ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(wabaId, { wabaId = it }, label = { Text("WhatsApp Business Account ID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(displayPhone, { displayPhone = it }, label = { Text("Business phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Permanent access token (system user)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it }, label = { Text("App secret") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = phoneNumberId.isNotBlank() && token.length >= 20 && secret.length >= 16, onClick = {
                            scope.launch {
                                container.api.call {
                                    connectWhatsapp(
                                        ConnectWhatsAppRequest(
                                            phoneNumberId = phoneNumberId.trim(),
                                            accessToken = token.trim(),
                                            appSecret = secret.trim(),
                                            wabaId = wabaId.trim().ifEmpty { null },
                                            displayPhone = displayPhone.trim().ifEmpty { null },
                                        ),
                                    )
                                }.onSuccess {
                                    token = ""; secret = ""; editing = false
                                    status.reload()
                                }.onFailure { toast(context, it.message ?: "Couldn't connect") }
                            }
                        }) { Text("Save") }
                        TextButton(onClick = { editing = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onCopy) { Text("Copy") }
    }
}
