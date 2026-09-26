@file:OptIn(ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.clients

import com.brokerbuddy.core.model.ClientPortalLead
import com.brokerbuddy.core.model.Portal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.brokerbuddy.core.model.NoteSource
import com.brokerbuddy.ui.caller.AddNoteDialog
import com.brokerbuddy.ui.caller.ClientNotesSection
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
import com.brokerbuddy.ui.design.Avatar
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.design.RoundIconButton
import com.brokerbuddy.ui.design.whatsAppIcon
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.Tint
import com.brokerbuddy.ui.theme.brand
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.design.FilterTabs
import com.brokerbuddy.ui.design.TabItem

@Composable
fun ClientDetailScreen(
    clientId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddInquiry: () -> Unit,
    onInquiry: (String) -> Unit,
    /** null = record a new note; otherwise resume that note. */
    onVoiceNote: (noteId: String?) -> Unit,
    onWhatsAppHistory: () -> Unit,
    /** Matching properties for a requirement (to share with the client). */
    onMatches: (inquiryId: String) -> Unit,
    /** A portal listing this client enquired about, filtered to the day of the enquiry. */
    onPortalListing: (portal: Portal, listingId: String, day: LocalDate) -> Unit = { _, _, _ -> },
) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(clientId) { container.api.call { client(clientId).client } }
    var addingReminder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addingNote by remember { mutableStateOf(false) }
    var notesVersion by remember { mutableIntStateOf(0) }
    var canDelete by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("overview") }
    LaunchedEffect(Unit) {
        canDelete = container.sessionStore.current().user?.role.let { it == Role.OWNER || it == Role.ADMIN }
    }

    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = {
            BackTopBar("", onBack) {
                IconButton(onClick = { onVoiceNote(null) }) { Icon(Icons.Filled.Mic, contentDescription = "Record voice note") }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                if (canDelete) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                }
            }
        },
        bottomBar = {
            val client = (loader.state as? Load.Ready)?.value
            if (client != null) {
                val b = MaterialTheme.brand
                Row(
                    Modifier.fillMaxWidth().background(b.card).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip(Icons.Filled.EditNote, "Add Note", Modifier.weight(1f)) { addingNote = true }
                    ActionChip(Icons.Filled.AddAlarm, "Add Follow Up", Modifier.weight(1f)) { addingReminder = true }
                    ActionChip(Icons.Filled.Share, "Share Properties", Modifier.weight(1f)) {
                        val active = client.inquiries.firstOrNull { it.status == InquiryStatus.ACTIVE }
                        if (active != null) onMatches(active.id) else toast(context, "Add a requirement first to find properties to share")
                    }
                }
            }
        },
    ) { padding ->
        LoadContent(loader) { client ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Header(
                    client,
                    onCall = { dial(context, client.primaryPhone) },
                    onWhatsApp = { openWhatsApp(context, client.primaryPhone) },
                    onSms = { sendSms(context, client.primaryPhone) },
                    onAddRequirement = onAddInquiry,
                )
                Spacer(Modifier.height(12.dp))
                FilterTabs(
                    listOf(
                        TabItem("overview", "Overview"),
                        TabItem("requirements", "Requirements", client.inquiries.size),
                        TabItem("activity", "Activity"),
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                )
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    "overview" -> {
                        BasicDetails(client)
                        if (client.inquiries.isNotEmpty()) {
                            SectionTitle("Requirements")
                            client.inquiries.filter { it.status == InquiryStatus.ACTIVE }.ifEmpty { client.inquiries }.take(2)
                                .forEach { InquiryCard(it) { onInquiry(it.id) } }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Follow-ups", Modifier.weight(1f))
                            TextButton(onClick = { addingReminder = true }) { Icon(Icons.Filled.AddAlarm, null); Text(" Add") }
                        }
                        if (client.reminders.isEmpty()) EmptyMessage("No pending follow-ups")
                        client.reminders.forEach { r -> LabeledValue(formatDateTime(r.dueAt), r.title) }
                    }
                    "requirements" -> {
                        if (client.inquiries.isEmpty()) EmptyMessage("No requirements recorded yet")
                        client.inquiries.forEach { InquiryCard(it) { onInquiry(it.id) } }
                        OutlinedButton(onClick = onAddInquiry, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Icon(Icons.Filled.Add, null); Text(" Add Requirement")
                        }
                    }
                    else -> {
                        PortalEnquiriesSection(client.portalLeads, onPortalListing)
                        VoiceNotesSection(clientId, onVoiceNote)
                        ClientNotesSection(clientId, refreshKey = notesVersion, onAdd = { addingNote = true })
                        TextButton(onClick = onWhatsAppHistory) { Text("WhatsApp conversation history") }
                    }
                }
                Spacer(Modifier.height(16.dp))
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
    if (addingNote) {
        AddNoteDialog(
            clientId = clientId,
            clientName = (loader.state as? Load.Ready)?.value?.name ?: "client",
            source = NoteSource.MANUAL,
            onDismiss = { addingNote = false },
            onSaved = { addingNote = false; notesVersion++ },
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
private fun Header(client: Client, onCall: () -> Unit, onWhatsApp: () -> Unit, onSms: () -> Unit, onAddRequirement: () -> Unit) {
    val b = MaterialTheme.brand
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(client.name, size = 84.dp)
        Spacer(Modifier.height(8.dp))
        Text(client.name, style = MaterialTheme.typography.headlineSmall, color = b.navy)
        Spacer(Modifier.height(4.dp))
        Pill(if (client.status == ClientStatus.NEW) "New Lead" else client.status.label, statusTint(client.status))
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onAddRequirement) { Icon(Icons.Filled.Add, null); Text(" Add Requirement") }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Filled.Call, "Call", b.info, onCall)
            RoundIconButton(whatsAppIcon(), "WhatsApp", Tint(b.success.container, Brand.WhatsApp), onWhatsApp)
            RoundIconButton(Icons.AutoMirrored.Filled.Message, "SMS", b.purple, onSms)
        }
    }
}

@Composable
private fun BasicDetails(client: Client) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    SectionTitle("Basic Details")
    BrandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Phone", style = MaterialTheme.typography.bodySmall, color = b.muted, modifier = Modifier.width(110.dp))
            Text(PhoneNumbers.display(client.primaryPhone), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Filled.Call, "Call", b.info, { dial(context, client.primaryPhone) }, size = 32.dp)
            Spacer(Modifier.width(6.dp))
            RoundIconButton(whatsAppIcon(), "WhatsApp", Tint(b.success.container, Brand.WhatsApp), { openWhatsApp(context, client.primaryPhone) }, size = 32.dp)
        }
        client.phones.filter { it.e164 != client.primaryPhone }.forEach { DetailLine("Alt. phone", PhoneNumbers.display(it.e164)) }
        DetailLine("Email", client.email)
        DetailLine("Source", client.leadSource.label)
        DetailLine("Assigned to", client.assignedTo?.name)
        DetailLine("Added", formatDate(client.createdAt))
        DetailLine("Notes", client.notes)
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val b = MaterialTheme.brand
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(b.info.container).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = b.link, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = b.link, maxLines = 1)
    }
}

/** A requirement on the client profile: type, area, budget, details and its status. */
@Composable
fun InquiryCard(inquiry: Inquiry, onClick: () -> Unit) {
    val b = MaterialTheme.brand
    val tint = when (inquiry.status) {
        InquiryStatus.ACTIVE -> b.success
        InquiryStatus.PAUSED -> b.amber
        else -> b.neutral
    }
    BrandCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = onClick, contentPadding = 0.dp) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tint.content))
            Column(Modifier.weight(1f).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${inquiry.category.label} • ${inquiry.transactionType.label}",
                        style = MaterialTheme.typography.titleSmall, color = b.navy, modifier = Modifier.weight(1f),
                    )
                    Pill(inquiry.status.label, tint)
                }
                if (inquiry.locations.isNotEmpty()) Text(inquiry.locations.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = b.muted)
                Money.range(inquiry.budgetMin, inquiry.budgetMax)?.let {
                    Text(it + if (inquiry.transactionType == TransactionType.RENT) " / month" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                val details = listOfNotNull(
                    inquiry.furnishing.takeIf { it.isNotEmpty() }?.joinToString("/") { it.label },
                    inquiry.minParking?.let { if (it > 0) "Parking" else "No parking" },
                    inquiry.possession?.label,
                ).joinToString(" • ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = b.muted)
            }
        }
    }
}

/** 99acres / Housing.com enquiries: which listing, when, and where each stands. */
@Composable
private fun PortalEnquiriesSection(leads: List<ClientPortalLead>, onListing: (Portal, String, LocalDate) -> Unit) {
    if (leads.isEmpty()) return
    SectionTitle("Portal enquiries")
    val zone = ZoneId.systemDefault()
    leads.forEach { l ->
        val listing = l.listing
        val day = runCatching { Instant.parse(l.enquiredAt).atZone(zone).toLocalDate() }.getOrNull()
        Card(
            onClick = { if (listing != null && day != null) onListing(l.portal, listing.id, day) },
            enabled = listing != null && day != null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("${l.portal.label} · ${l.status.label} · ${formatDateTime(l.enquiredAt)}", style = MaterialTheme.typography.labelLarge)
                Text(
                    listing?.let { listOfNotNull(it.title, it.locality).joinToString(", ").ifEmpty { "Listing" } } ?: "Listing not identified",
                    style = MaterialTheme.typography.bodyMedium,
                )
                l.message?.let { Text("“${it.take(140)}”", style = MaterialTheme.typography.bodySmall) }
            }
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
