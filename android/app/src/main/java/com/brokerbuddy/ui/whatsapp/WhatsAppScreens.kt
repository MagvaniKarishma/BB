@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.whatsapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.caller.CallerCards
import com.brokerbuddy.core.form.FormField
import com.brokerbuddy.core.form.RequirementForm
import com.brokerbuddy.core.form.applyDraft
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.ApplyVoiceNoteRequest
import com.brokerbuddy.core.model.Client
import com.brokerbuddy.core.model.CreateClientFromMessage
import com.brokerbuddy.core.model.DraftReview
import com.brokerbuddy.core.model.ImportChatRequest
import com.brokerbuddy.core.model.ImportMessageRequest
import com.brokerbuddy.core.model.LinkClientRequest
import com.brokerbuddy.core.model.MessageDirection
import com.brokerbuddy.core.model.SendMessageRequest
import com.brokerbuddy.core.model.WaMessage
import com.brokerbuddy.core.model.WaMessageDetail
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.core.whatsapp.SharedText
import com.brokerbuddy.data.ApiException
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.inquiries.RequirementEditor
import com.brokerbuddy.ui.inquiries.RequirementFormSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private fun rel(iso: String) = CallerCards.relative(iso, Instant.now(), ZoneId.systemDefault())

private fun WaMessage.who(): String =
    leadName ?: client?.name ?: senderName ?: contact?.profileName ?: leadPhone?.let(PhoneNumbers::display) ?: "Unknown"

// ---------------- Inbox ----------------

@Composable
fun WhatsAppInboxScreen(onBack: () -> Unit, onMessage: (String) -> Unit, onImport: () -> Unit) {
    val api = appContainer().api
    var filter by rememberSaveable { mutableStateOf("attention") }
    val loader = rememberLoad(filter) { api.call { whatsappInbox(filter).messages } }
    Scaffold(topBar = {
        BackTopBar("WhatsApp leads", onBack) { TextButton(onClick = onImport) { Text("Paste / import") } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("attention" to "Needs attention", "needs_client" to "New leads", "needs_review" to "To review", "all" to "All")
                    .forEach { (key, label) -> FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) }) }
            }
            LoadContent(loader) { messages ->
                if (messages.isEmpty()) EmptyMessage("Nothing waiting. New WhatsApp leads and requirement messages appear here.")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(messages, key = { it.id }) { m ->
                        ListItem(
                            modifier = Modifier.clickable { onMessage(m.id) },
                            overlineContent = {
                                Text(listOfNotNull(m.portal?.label, if (m.channel.name == "MANUAL") "Shared" else "WhatsApp", rel(m.sentAt)).joinToString(" · "))
                            },
                            headlineContent = { Text(m.who()) },
                            supportingContent = { Text((m.text ?: "[${m.type}]").take(120), maxLines = 2) },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    if (m.clientId == null && (m.leadPhone != null || m.portal != null)) Text("New lead", color = MaterialTheme.colorScheme.primary)
                                    if (m.review == DraftReview.PENDING) Text("Review", color = MaterialTheme.colorScheme.tertiary)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ---------------- Message detail ----------------

@Composable
fun WhatsAppMessageScreen(
    messageId: String,
    onBack: () -> Unit,
    onClient: (String) -> Unit,
    onInquiry: (String) -> Unit,
) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<WaMessageDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }

    LaunchedEffect(messageId) {
        api.call { whatsappMessage(messageId) }.onSuccess { detail = it }.onFailure { error = it.message }
    }
    fun update(result: Result<WaMessageDetail>, done: String? = null) {
        result.onSuccess { detail = it; done?.let { toast(context, it) } }.onFailure { toast(context, it.message ?: "Failed") }
    }

    Scaffold(topBar = { BackTopBar("Message", onBack) }) { padding ->
        val d = detail
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (d == null) return@Column
            val m = d.message
            Text("${m.senderName ?: m.contact?.profileName ?: "Unknown"} · ${rel(m.sentAt)}", style = MaterialTheme.typography.labelLarge)
            Card(Modifier.fillMaxWidth()) { Text(m.text ?: "[${m.type} — no text]", Modifier.padding(12.dp)) }

            m.portalLead?.let { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${p.portal.label} lead", style = MaterialTheme.typography.titleSmall)
                        p.leadName?.let { Text("Name: $it") }
                        p.leadPhone?.let { Text("Phone: ${PhoneNumbers.display(it)}") }
                        p.leadEmail?.let { Text("Email: $it") }
                        p.listingRef?.let { Text("Listing: $it") }
                        p.listingPrice?.let {
                            Text("Advertised price: ${Money.full(it.value)} — the listing's price, not the client's budget",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            SectionTitle("Client")
            val linked = m.client
            if (linked != null) {
                OutlinedButton(onClick = { onClient(linked.id) }) { Text("Open ${linked.name}") }
            } else {
                d.existingClient?.let { ex ->
                    Text("This number already belongs to ${ex.name}. Link instead of creating a duplicate.", color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { creating = true }) { Text("Create client") }
                    OutlinedButton(onClick = { linking = true }) { Text("Link to existing") }
                }
            }

            val extraction = m.extraction
            if (extraction != null && extraction.advertisedPrices.isNotEmpty() && m.portalLead == null) {
                Text(
                    "Property prices mentioned (not budget): " + extraction.advertisedPrices.joinToString { Money.full(it.value) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                m.review == DraftReview.APPLIED -> m.inquiryId?.let { id -> TextButton(onClick = { onInquiry(id) }) { Text("Saved to requirement — open") } }
                m.review == DraftReview.PENDING && extraction != null && m.clientId != null ->
                    DraftReviewPanel(d, onSaved = { onInquiry(it) }, onDismissed = { update(Result.success(it), "Dismissed") })
                m.review == DraftReview.PENDING && m.clientId == null ->
                    Text("Create or link the client to review the requirement found in this message.", style = MaterialTheme.typography.bodySmall)
                else -> TextButton(onClick = { scope.launch { update(api.call { whatsappExtract(m.id) }) } }) { Text("Look for requirements") }
            }
        }
    }

    val d = detail
    if (creating && d != null) {
        CreateClientDialog(d, onDismiss = { creating = false }) { name, phone ->
            creating = false
            scope.launch {
                api.call { whatsappCreateClient(d.message.id, CreateClientFromMessage(name, phone)) }
                    .onSuccess { detail = it; toast(context, if (it.linkedExisting) "Already a client — linked to the existing profile" else "Client created") }
                    .onFailure { toast(context, it.message ?: "Failed") }
            }
        }
    }
    if (linking && d != null) {
        ClientPickerDialog(showAddNumber = d.message.leadPhone != null, onDismiss = { linking = false }) { client, addNumber ->
            linking = false
            scope.launch { update(api.call { whatsappLinkClient(d.message.id, LinkClientRequest(client.id, addNumber)) }, "Linked to ${client.name}") }
        }
    }
}

@Composable
private fun CreateClientDialog(d: WaMessageDetail, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by rememberText(d.message.leadName)
    var phone by rememberText(d.message.leadPhone)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New client") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                Text("If this number already belongs to a client, the message is linked to them instead.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && PhoneNumbers.normalize(phone) != null, onClick = { onCreate(name.trim(), phone.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Search-and-pick a client. */
@Composable
fun ClientPickerDialog(showAddNumber: Boolean, onDismiss: () -> Unit, onPick: (Client, addNumber: Boolean) -> Unit) {
    val api = appContainer().api
    var query by rememberText()
    var addNumber by remember { mutableStateOf(true) }
    val results = rememberLoad(query) {
        if (query.isNotBlank()) delay(300)
        api.call { clients(q = query.ifBlank { null }, pageSize = 20).clients }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose client") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search name or phone") }, singleLine = true)
                if (showAddNumber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(addNumber, { addNumber = it })
                        Text("Add this WhatsApp number to the client", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ((results.state as? Load.Ready)?.value ?: emptyList()).take(8).forEach { c ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(c, addNumber) },
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text(PhoneNumbers.display(c.primaryPhone)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Review the requirement found in a message and save it to a new or existing inquiry. */
@Composable
private fun DraftReviewPanel(d: WaMessageDetail, onSaved: (String) -> Unit, onDismissed: (WaMessageDetail) -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = d.message
    val extraction = m.extraction ?: return

    if (extraction.warnings.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Please check", style = MaterialTheme.typography.titleSmall)
                extraction.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    var target by rememberSaveable(m.id) { mutableStateOf(m.inquiryId ?: d.suggestedInquiryId) }
    SectionTitle("Save to")
    (listOf<Pair<String?, String>>(null to "New requirement") + d.inquiries.map { it.id to "${it.transactionType.label} · ${it.category.label} (${it.status.label})" })
        .forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().selectable(target == id, onClick = { target = id }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = target == id, onClick = null)
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
    val base = d.inquiries.firstOrNull { it.id == target }?.let(RequirementForm::fromInquiry) ?: RequirementForm()
    val merge = remember(m.id, target) { base.applyDraft(extraction.draft) }
    var form by rememberSaveable(m.id, target, stateSaver = RequirementFormSaver) { mutableStateOf(merge.form) }
    var attempted by rememberSaveable(m.id) { mutableStateOf(false) }
    val validation = form.validate()
    val errors = if (attempted) validation.errors else validation.errors.filterKeys { it != FormField.TRANSACTION && it != FormField.CATEGORY }
    RequirementEditor(form, { form = it }, errors, showStatus = target != null, evidence = merge.evidence, previous = merge.previous)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            attempted = true
            val body = validation.request ?: return@Button
            scope.launch {
                api.call { whatsappApply(m.id, ApplyVoiceNoteRequest(target, body)) }
                    .onSuccess { onSaved(it.inquiry.id) }
                    .onFailure { toast(context, it.message ?: "Could not save") }
            }
        }) { Text(if (target == null) "Save as new requirement" else "Update requirement") }
        OutlinedButton(onClick = { scope.launch { api.call { whatsappDismiss(m.id) }.onSuccess(onDismissed) } }) { Text("Dismiss") }
    }
}

// ---------------- Conversation ----------------

@Composable
fun WhatsAppConversationScreen(clientId: String, onBack: () -> Unit, onMessage: (String) -> Unit, onImportChat: () -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = rememberLoad(clientId) { api.call { client(clientId).client } }
    val convo = rememberLoad(clientId) { api.call { whatsappConversation(clientId) } }
    var draft by rememberText()
    val name = (client.state as? Load.Ready)?.value?.name ?: "Client"
    Scaffold(topBar = { BackTopBar("WhatsApp · $name", onBack) { TextButton(onClick = onImportChat) { Text("Import chat") } } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                LoadContent(convo) { c ->
                    if (c.messages.isEmpty()) EmptyMessage("No WhatsApp messages yet. Share a chat or import an export to keep history here.")
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), reverseLayout = false) {
                        items(c.messages, key = { it.id }) { m -> Bubble(m) { onMessage(m.id) } }
                    }
                }
            }
            val c = (convo.state as? Load.Ready)?.value
            val phone = (client.state as? Load.Ready)?.value?.primaryPhone
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4)
                if (c?.serviceWindowOpen == true) {
                    TextButton(enabled = draft.isNotBlank(), onClick = {
                        scope.launch {
                            api.call { whatsappSend(SendMessageRequest(clientId, draft.trim())) }
                                .onSuccess { draft = ""; convo.reload() }
                                .onFailure { e ->
                                    // Fallback: open WhatsApp with the text ready to send.
                                    toast(context, e.message ?: "Couldn't send")
                                    if ((e as? ApiException)?.code in setOf<String?>("WHATSAPP_NOT_CONNECTED", "OUTSIDE_SERVICE_WINDOW") && phone != null) openWhatsApp(context, phone, draft)
                                }
                        }
                    }) { Text("Send") }
                } else {
                    // No API window: hand off to the WhatsApp app (no template needed there).
                    TextButton(enabled = phone != null, onClick = { phone?.let { openWhatsApp(context, it, draft) } }) { Text("Open in WhatsApp") }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: WaMessage, onClick: () -> Unit) {
    val inbound = m.direction == MessageDirection.INBOUND
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (inbound) Arrangement.Start else Arrangement.End) {
        Card(
            onClick = onClick,
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (inbound) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(m.text ?: "[${m.type}]")
                Text(
                    listOfNotNull(rel(m.sentAt), if (!inbound) m.status.lowercase() else null, if (m.review == DraftReview.PENDING) "requirement to review" else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ---------------- Manual import (share sheet) ----------------

/** Text shared into BrokerBuddy from WhatsApp (single message or an exported chat). */
@Composable
fun WhatsAppImportScreen(initialText: String, presetClientId: String?, onBack: () -> Unit, onMessage: (String) -> Unit, onClientHistory: (String) -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberText(initialText)
    var client by remember { mutableStateOf<Pair<String, String>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val isExport = SharedText.looksLikeChatExport(text)
    val participants = remember(text) { if (isExport) SharedText.participants(text) else emptyList() }
    var clientSender by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(presetClientId) {
        if (presetClientId != null) api.call { client(presetClientId).client }.onSuccess { client = it.id to it.name }
    }

    Scaffold(topBar = { BackTopBar(if (isExport) "Import WhatsApp chat" else "Add WhatsApp message", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Works without the WhatsApp Business API: share a message (or Export chat → Without media) from WhatsApp to BrokerBuddy, or paste it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(text, { text = it }, minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth(), label = { Text(if (isExport) "Chat export" else "Message") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(client?.second?.let { "Client: $it" } ?: if (isExport) "Choose the client" else "Client: detect from portal lead / choose", Modifier.weight(1f))
                TextButton(onClick = { picking = true }) { Text(if (client == null) "Choose" else "Change") }
            }
            if (isExport) {
                Text("Which participant is the client?", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    participants.forEach { p -> FilterChip(selected = clientSender == p, onClick = { clientSender = p }, label = { Text(p) }) }
                }
            }
            Button(
                enabled = !busy && text.isNotBlank() && (!isExport || (client != null && clientSender != null)),
                onClick = {
                    busy = true
                    scope.launch {
                        if (isExport) {
                            api.call { whatsappImportChat(ImportChatRequest(client!!.first, clientSender!!, text)) }
                                .onSuccess { toast(context, "Imported ${it.imported} messages (${it.skippedDuplicates} already saved)"); onClientHistory(client!!.first) }
                                .onFailure { toast(context, it.message ?: "Import failed") }
                        } else {
                            api.call { whatsappImport(ImportMessageRequest(text.trim(), client?.first)) }
                                .onSuccess { onMessage(it.message.id) }
                                .onFailure { toast(context, it.message ?: "Import failed") }
                        }
                        busy = false
                    }
                },
            ) { Text(if (isExport) "Import chat history" else "Read message") }
        }
    }
    if (picking) {
        ClientPickerDialog(showAddNumber = false, onDismiss = { picking = false }) { c, _ -> client = c.id to c.name; picking = false }
    }
}
