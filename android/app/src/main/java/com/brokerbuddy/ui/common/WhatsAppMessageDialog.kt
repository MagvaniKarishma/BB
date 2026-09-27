package com.brokerbuddy.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.message.MessageTemplates
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.theme.brand

/**
 * WhatsApp from a client's profile: open the chat, or open it with a ready-made message typed in.
 * Nothing is sent from BrokerBuddy — the broker reads it and taps Send in WhatsApp.
 */
@Composable
fun WhatsAppMessageDialog(clientName: String, phone: String, requirement: Inquiry?, brokerName: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val templates = MessageTemplates.forClient(clientName, brokerName, requirement)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WhatsApp ${PhoneNumbers.display(phone)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The message is typed into the chat for you — check it and tap Send in WhatsApp. Nothing is sent automatically.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted,
                )
                OutlinedButton(onClick = { onDismiss(); openWhatsApp(context, phone) }, modifier = Modifier.fillMaxWidth()) { Text("Open chat (no message)") }
                templates.forEach { t ->
                    OutlinedButton(onClick = { onDismiss(); openWhatsApp(context, phone, t.text) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(t.label, style = MaterialTheme.typography.labelLarge)
                            Text(t.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
