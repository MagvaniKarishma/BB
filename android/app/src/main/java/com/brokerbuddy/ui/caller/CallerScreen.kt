@file:OptIn(ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.caller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.caller.CallerCards
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.AddNoteRequest
import com.brokerbuddy.core.model.CallerClient
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.NoteSource
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.dial
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import com.brokerbuddy.ui.design.Avatar as DesignAvatar

/** Full caller card: opened from the call notification or the floating card. */
@Composable
fun CallerScreen(
    number: String,
    startWithNote: Boolean,
    onBack: () -> Unit,
    onClient: (String) -> Unit,
    onInquiryMatches: (String) -> Unit,
    onCreateClient: (String?) -> Unit,
    onVoiceNote: (String) -> Unit,
) {
    val api = appContainer().api
    val loader = rememberLoad(number) { api.call { callerLookup(number) } }
    Scaffold(topBar = { BackTopBar("Caller", onBack) }) { padding ->
        LoadContent(loader) { lookup ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                val client = lookup.client
                when {
                    client != null -> KnownCaller(client, startWithNote, onClient, onInquiryMatches, onVoiceNote, onNoteSaved = loader.reload)
                    lookup.number != null -> UnknownCaller(lookup.number!!, onCreateClient)
                    else -> EmptyMessage("The number is hidden or not a valid phone number, so the caller can't be identified.")
                }
            }
        }
    }
}

@Composable
private fun Avatar(text: String) {
    Box(
        Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun KnownCaller(
    c: CallerClient,
    startWithNote: Boolean,
    onClient: (String) -> Unit,
    onInquiryMatches: (String) -> Unit,
    onVoiceNote: (String) -> Unit,
    onNoteSaved: () -> Unit,
) {
    val context = LocalContext.current
    var addingNote by rememberSaveable { mutableStateOf(startWithNote) }
    val now = Instant.now()
    val zone = ZoneId.systemDefault()

    // Caller card in the incoming-call style: who it is and what they're looking for.
    val looking = c.inquiries.firstOrNull()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1F4B), Color(0xFF13306E))))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DesignAvatar(c.name, size = 88.dp)
        Spacer(Modifier.height(10.dp))
        Text(c.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
        Text(PhoneNumbers.display(c.primaryPhone), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))
        Text(
            (if (c.inquiries.isNotEmpty()) "Active Client" else c.status.label) + (c.assignedTo?.name?.let { " · $it" } ?: ""),
            modifier = Modifier.background(Color(0xFF16A34A), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 4.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium,
        )
        if (looking != null) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(14.dp),
            ) {
                Text("Looking for", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                Text("${looking.category.label} • ${looking.transactionType.label}", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (looking.locations.isNotEmpty()) Text(looking.locations.joinToString(", "), color = Color.White.copy(alpha = 0.9f))
                Money.range(looking.budgetMin, looking.budgetMax)?.let { Text(it, color = Color.White, fontWeight = FontWeight.SemiBold) }
                val details = listOfNotNull(
                    looking.furnishing.takeIf { it.isNotEmpty() }?.joinToString("/") { it.label },
                    looking.minParking?.takeIf { it > 0 }?.let { "Parking" },
                    looking.possession?.label,
                ).joinToString(" • ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onClient(c.id) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("View Full Profile") }
    }
    SectionTitle(if (c.inquiries.size == 1) "Requirement" else "Requirements (${c.inquiries.size})")
    if (c.inquiries.isEmpty()) {
        EmptyMessage("No open requirements" + if (c.closedInquiries > 0) " · ${c.closedInquiries} closed" else "")
    }
    c.inquiries.forEach { RequirementCard(it) { onInquiryMatches(it.id) } }

    SectionTitle("Last conversation")
    val last = c.lastInteraction
    if (last == null) {
        EmptyMessage("No notes yet")
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    (if (last.kind == "VOICE_NOTE") "🎙 Voice note" else "Note") + " · " + CallerCards.relative(last.at, now, zone) +
                        (last.by?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(last.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    SectionTitle("Follow-ups")
    if (c.reminders.isEmpty()) EmptyMessage("No pending follow-ups")
    c.reminders.forEach { r ->
        val overdue = runCatching { Instant.parse(r.dueAt).isBefore(now) }.getOrDefault(false)
        Text(
            "${if (overdue) "Overdue" else CallerCards.relative(r.dueAt, now, zone)} · ${r.title}",
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }

    ClientNotesSection(c.id, refreshKey = c.lastInteraction?.at)

    if (addingNote) {
        AddNoteDialog(c.id, c.name, NoteSource.CALLER_SCREEN, onDismiss = { addingNote = false }) {
            addingNote = false
            onNoteSaved()
        }
    }
}

@Composable
private fun RequirementCard(i: Inquiry, onMatches: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i.transactionType.label} · ${i.category.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (i.status == InquiryStatus.PAUSED) Text("Paused", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Budget: " + (Money.range(i.budgetMin, i.budgetMax) ?: "not specified"))
            Text("Locations: " + (i.locations.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "not specified"))
            if (i.furnishing.isNotEmpty()) Text("Furnishing: " + i.furnishing.joinToString(", ") { it.label })
            if (i.mandatory.isNotEmpty()) {
                Text("Must-haves: " + i.mandatory.joinToString(", ") { it.label }, style = MaterialTheme.typography.bodySmall)
            }
            val count = i.matchCount
            TextButton(onClick = onMatches) {
                Text(
                    when (count) {
                        null -> "View matches"
                        0 -> "No matching properties yet"
                        1 -> "View 1 matching property"
                        else -> "View $count matching properties"
                    },
                )
            }
        }
    }
}

@Composable
private fun UnknownCaller(number: String, onCreateClient: (String?) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar("?")
        Text(PhoneNumbers.display(number), style = MaterialTheme.typography.headlineSmall)
        Text("Not in BrokerBuddy", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onCreateClient(number) }) {
            Icon(Icons.Filled.PersonAdd, null)
            Spacer(Modifier.width(6.dp))
            Text("Create client")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dial(context, number) }) { Text("Call back") }
            OutlinedButton(onClick = { openWhatsApp(context, number) }) { Text("WhatsApp") }
        }
        Text(
            "If this is an existing client's other number, open their profile and add it there so future calls are recognised.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Conversation notes, newest first. Used on the caller screen and client profile. */
@Composable
fun ClientNotesSection(clientId: String, refreshKey: Any? = null, onAdd: (() -> Unit)? = null) {
    val api = appContainer().api
    val notes = rememberLoad(clientId, refreshKey) { api.call { clientNotes(clientId).notes } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Notes", Modifier.weight(1f))
        if (onAdd != null) TextButton(onClick = onAdd) { Text("Add") }
    }
    LoadContent(notes) { list ->
        Column {
            if (list.isEmpty()) EmptyMessage("No notes yet")
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            list.take(10).forEach { n ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        CallerCards.relative(n.createdAt, now, zone) + (n.author?.let { " · ${it.name}" } ?: "") +
                            if (n.source != NoteSource.MANUAL) " · after a call" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(n.body)
                }
            }
        }
    }
}

@Composable
fun AddNoteDialog(clientId: String, clientName: String, source: NoteSource, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberText()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note · $clientName") },
        text = {
            OutlinedTextField(
                text, { text = it }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What was discussed? (Hindi, Marathi or English)") },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    api.call { addClientNote(clientId, AddNoteRequest(text.trim(), source)) }
                        .onSuccess { onSaved() }
                        .onFailure { toast(context, it.message ?: "Could not save note") }
                    busy = false
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
