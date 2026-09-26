@file:OptIn(ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAlarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.Client
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.Role
import com.brokerbuddy.core.model.VoiceNoteStatus
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.LabeledValue
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.dial
import com.brokerbuddy.ui.common.formatDate
import com.brokerbuddy.ui.common.formatDateTime
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.sendSms
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.inquiries.requirementSummary
import com.brokerbuddy.ui.reminders.AddReminderDialog
import kotlinx.coroutines.launch

@Composable
fun ClientDetailScreen(
    clientId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddInquiry: () -> Unit,
    onInquiry: (String) -> Unit,
    /** null = record a new note; otherwise resume that note. */
    onVoiceNote: (noteId: String?) -> Unit,
) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(clientId) { container.api.call { client(clientId).client } }
    var addingReminder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var canDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        canDelete = container.sessionStore.current().user?.role.let { it == Role.OWNER || it == Role.ADMIN }
    }

    Scaffold(
        topBar = {
            BackTopBar("Client", onBack) {
                IconButton(onClick = { onVoiceNote(null) }) { Icon(Icons.Filled.Mic, contentDescription = "Record voice note") }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                if (canDelete) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                }
            }
        },
    ) { padding ->
        LoadContent(loader) { client ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Header(client)
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { dial(context, client.primaryPhone) }) {
                        Icon(Icons.Filled.Call, null); Spacer(Modifier.width(6.dp)); Text("Call")
                    }
                    FilledTonalButton(onClick = { openWhatsApp(context, client.primaryPhone) }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null); Spacer(Modifier.width(6.dp)); Text("WhatsApp")
                    }
                    IconButton(onClick = { sendSms(context, client.primaryPhone) }) {
                        Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "SMS")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Inquiries", Modifier.weight(1f))
                    TextButton(onClick = onAddInquiry) { Icon(Icons.Filled.Add, null); Text("Add") }
                }
                if (client.inquiries.isEmpty()) EmptyMessage("No requirements recorded yet")
                client.inquiries.forEach { InquiryCard(it) { onInquiry(it.id) } }

                VoiceNotesSection(clientId, onVoiceNote)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Follow-ups", Modifier.weight(1f))
                    TextButton(onClick = { addingReminder = true }) { Icon(Icons.Filled.AddAlarm, null); Text("Add") }
                }
                if (client.reminders.isEmpty()) EmptyMessage("No pending follow-ups")
                client.reminders.forEach { r ->
                    LabeledValue(formatDateTime(r.dueAt), r.title)
                }

                SectionTitle("Details")
                client.phones.filter { it.e164 != client.primaryPhone }.forEach {
                    LabeledValue("Alt. phone", PhoneNumbers.display(it.e164))
                }
                LabeledValue("Email", client.email)
                LabeledValue("Lead source", client.leadSource.label)
                LabeledValue("Assigned to", client.assignedTo?.name)
                LabeledValue("Added", formatDate(client.createdAt))
                LabeledValue("Notes", client.notes)
            }
        }
    }

    if (addingReminder) {
        AddReminderDialog(
            clientId = clientId,
            defaultTitle = (loader.state as? Load.Ready)?.value?.let { "Follow up with ${it.name}" } ?: "",
            onDismiss = { addingReminder = false },
            onCreated = { addingReminder = false; loader.reload() },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete client?") },
            text = { Text("This permanently removes the client, all their inquiries, requirement history and follow-ups.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.api.call { deleteClient(clientId) }
                            .onSuccess { onBack() }
                            .onFailure { toast(context, it.message ?: "Delete failed") }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(client: Client) {
    Text(client.name, style = MaterialTheme.typography.headlineSmall)
    Text(PhoneNumbers.display(client.primaryPhone), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = {}, label = { Text(client.status.label) })
        AssistChip(onClick = {}, label = { Text(client.leadSource.label) })
    }
}

@Composable
fun InquiryCard(inquiry: Inquiry, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(
                    "${inquiry.transactionType.label} · ${inquiry.category.label}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (inquiry.status != InquiryStatus.ACTIVE) {
                    Text(inquiry.status.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(requirementSummary(inquiry), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Voice notes for this client; ones not yet saved can be resumed. */
@Composable
private fun VoiceNotesSection(clientId: String, onVoiceNote: (String?) -> Unit) {
    val api = appContainer().api
    val notes = rememberLoad(clientId) { api.call { voiceNotes(clientId = clientId).voiceNotes } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Voice notes", Modifier.weight(1f))
        TextButton(onClick = { onVoiceNote(null) }) { Icon(Icons.Filled.Mic, null); Text("Record") }
    }
    val list = (notes.state as? Load.Ready)?.value.orEmpty()
    if (list.isEmpty()) EmptyMessage("Tap Record and describe what the client wants")
    list.take(5).forEach { n ->
        val pending = n.status == VoiceNoteStatus.READY || n.status == VoiceNoteStatus.NEEDS_TRANSCRIPT
        val status = when (n.status) {
            VoiceNoteStatus.NEEDS_TRANSCRIPT -> "Needs transcript"
            VoiceNoteStatus.READY -> "Ready to review"
            VoiceNoteStatus.APPLIED -> "Saved"
            VoiceNoteStatus.DISCARDED -> "Discarded"
        }
        Card(
            onClick = { if (pending) onVoiceNote(n.id) },
            enabled = pending,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("$status · ${formatDateTime(n.createdAt)}", style = MaterialTheme.typography.labelLarge)
                n.transcript?.let { Text(it.take(140), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
