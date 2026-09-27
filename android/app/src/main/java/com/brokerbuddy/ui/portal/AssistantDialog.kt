package com.brokerbuddy.ui.portal

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.CreateReminderRequest
import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.ReminderKind
import com.brokerbuddy.core.model.UpdatePortalLeadRequest
import com.brokerbuddy.core.voice.ClientChoice
import com.brokerbuddy.core.voice.Commands
import com.brokerbuddy.core.voice.ListingChoice
import com.brokerbuddy.core.voice.VoiceCommand
import com.brokerbuddy.core.voice.Who
import com.brokerbuddy.data.ApiClient
import com.brokerbuddy.notifications.ReminderScheduler
import com.brokerbuddy.ui.Routes
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.properties.PropertySearch
import com.brokerbuddy.ui.theme.brand
import com.brokerbuddy.ui.voice.VoiceHandoff
import com.brokerbuddy.voice.EnglishTranslator
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/** Speech languages offered; Hinglish works with English (India). */
internal enum class SpeechLang(val label: String, val tag: String) {
    ENGLISH("English / Hinglish", "en-IN"),
    HINDI("हिंदी", "hi-IN"),
    MARATHI("मराठी", "mr-IN"),
}

/** What the command understood, and the words it was read from. */
private data class Understood(val command: VoiceCommand, val heard: String, val english: String?)

/**
 * "Ask BrokerBuddy": speak (the phone's own speech recognition) or type a command. It's understood
 * on the phone (works in the demo too), shown for review with the words it came from, and only
 * acted on when the broker taps: forms open pre-filled for review, and a follow-up or status change
 * is saved only on "Save". Nothing is guessed: an unclear client name gives choices instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantDialog(onDismiss: () -> Unit, onOpen: (route: String) -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberText()
    var lang by rememberSaveable { mutableStateOf(SpeechLang.ENGLISH) }
    var busy by remember { mutableStateOf(false) }
    var understood by remember { mutableStateOf<Understood?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(command: String) {
        if (command.isBlank()) return
        busy = true
        error = null
        understood = null
        scope.launch {
            runCatching { understand(api, command.trim(), lang) }
                .onSuccess { understood = it }
                .onFailure { error = it.message ?: "Couldn't read the command" }
            busy = false
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) {
            text = heard
            run(heard)
        }
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command")
        runCatching { speech.launch(intent) }.onFailure { error = "Speech recognition isn't available on this phone — type the command instead." }
    }

    fun open(route: String) {
        onDismiss()
        onOpen(route)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask BrokerBuddy") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpeechLang.entries.forEach { l -> FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l.label) }) }
                }
                OutlinedTextField(
                    text, { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Rahul is looking for a 2 BHK in Andheri under 1 crore") },
                    trailingIcon = { IconButton(onClick = ::listen, enabled = !busy) { Icon(Icons.Filled.Mic, "Speak") } },
                )
                if (understood == null && !busy) {
                    Text(Commands.HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted)
                }
                if (busy) Text("Working…", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                understood?.let { u ->
                    u.english?.let { Text("Read as: “$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted) }
                    Review(u.command, api, onOpen = ::open, onDone = { message -> toast(context, message); onDismiss() })
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank() && !busy, onClick = { run(text) }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Reads the command on the phone. Hindi/Marathi words the rules don't know are read again from the
 * phone's English translation (downloaded once, then offline).
 */
private suspend fun understand(api: ApiClient, text: String, lang: SpeechLang): Understood {
    val clients = loadClients(api)
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    fun parse(t: String, listings: List<ListingChoice> = emptyList()) = Commands.parse(t, clients, now, listings)
    var english: String? = null
    var command = parse(text)
    if (command is VoiceCommand.Unknown && EnglishTranslator.needsTranslation(text)) {
        val source = if (lang == SpeechLang.MARATHI) EnglishTranslator.Source.MARATHI else EnglishTranslator.Source.HINDI
        english = runCatching { EnglishTranslator.toEnglish(text, source) }.getOrNull()
        if (english != null) command = parse(english)
    }
    // "Who's interested in the Andheri flat?" needs the portal listings.
    if (command is VoiceCommand.ListingClients) {
        val listings = Portal.entries.flatMap { p ->
            api.call { portalListings(p).listings }.getOrElse { emptyList() }
                .filter { it.identified }
                .map { ListingChoice(it.id, p, it.title, it.locality, it.externalId) }
        }
        command = parse(english ?: text, listings)
    }
    return Understood(command, text, english)
}

/** Every client's name and number (for picking who the command is about). */
private suspend fun loadClients(api: ApiClient): List<ClientChoice> {
    val out = mutableListOf<ClientChoice>()
    var page = 1
    while (page <= 20) {
        val list = api.call { clients(group = "all", page = page, pageSize = 100) }.getOrThrow()
        out += list.clients.map { ClientChoice(it.id, it.name, it.primaryPhone) }
        if (out.size >= list.total || list.clients.isEmpty()) break
        page++
    }
    return out
}

@Composable
private fun Review(c: VoiceCommand, api: ApiClient, onOpen: (String) -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val b = MaterialTheme.brand

    Text(Commands.describe(c), style = MaterialTheme.typography.titleSmall, color = b.navy)

    @Composable
    fun ChooseClient(candidates: List<ClientChoice>, then: (ClientChoice) -> Unit) {
        Text("More than one client has that name — nothing was changed. Which one?", style = MaterialTheme.typography.bodySmall)
        candidates.take(8).forEach { cl -> OutlinedButton(onClick = { then(cl) }, modifier = Modifier.fillMaxWidth()) { Text("${cl.name} · ${cl.phone}") } }
    }

    when (c) {
        is VoiceCommand.AddClient -> Button(onClick = { onOpen(Routes.clientNew(name = c.name, phone = c.phone)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Review in the new client form")
        }

        is VoiceCommand.AddRequirement -> {
            val d = c.extraction.draft
            listOfNotNull(
                d.transactionType?.let { "Rent / Buy: ${it.value.label}  (“${it.evidence}”)" },
                d.category?.let { "BHK: ${it.value.label}  (“${it.evidence}”)" },
                d.locations?.let { l -> "Areas: ${l.joinToString { it.value }}" },
                (d.budgetMin ?: d.budgetMax)?.let { "Budget: ${Money.range(d.budgetMin?.value, d.budgetMax?.value)}  (“${it.evidence}”)" },
                d.furnishing?.let { "Furnishing: ${it.value.joinToString { f -> f.label }}" },
                d.floorPreference?.let { "Floor: ${it.value.joinToString { f -> f.label }}" },
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (d.transactionType == null) Text("Rent or buy wasn't said — choose it on the form.", style = MaterialTheme.typography.bodySmall, color = b.muted)
            c.extraction.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            fun review(clientId: String) = onOpen(Routes.inquiryNewFromVoice(clientId, VoiceHandoff.put(c.extraction)))
            when (val w = c.who) {
                is Who.One -> Button(onClick = { review(w.client.id) }, modifier = Modifier.fillMaxWidth()) { Text("Review requirement for ${w.client.name}") }
                is Who.Several -> ChooseClient(w.candidates) { review(it.id) }
                Who.Nobody -> {
                    Text("No saved client matches that name.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onOpen(Routes.clientNew(name = c.nameSaid, phone = null)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(c.nameSaid?.let { "Add $it as a new client" } ?: "Add a new client")
                    }
                }
            }
        }

        is VoiceCommand.FindClients -> Button(onClick = { onOpen(Routes.clientSearch(c.search)) }, modifier = Modifier.fillMaxWidth()) { Text("Show these clients") }

        is VoiceCommand.FollowUp -> {
            fun save(client: ClientChoice) {
                saving = true
                scope.launch {
                    val due = c.at.atZone(ZoneId.systemDefault()).toInstant().toString()
                    api.call { createReminder(CreateReminderRequest("Follow up with ${client.name}", due, clientId = client.id, kind = ReminderKind.FOLLOW_UP)) }
                        .onSuccess {
                            ReminderScheduler.schedule(context, it.reminder)
                            onDone("Follow-up with ${client.name} saved for ${c.whenLabel}")
                        }
                        .onFailure { toast(context, it.message ?: "Couldn't save the follow-up") }
                    saving = false
                }
            }
            if (!c.timeSaid) Text("No time was said — 10:00 is used; change it after saving if needed.", style = MaterialTheme.typography.bodySmall, color = b.muted)
            when (val w = c.who) {
                is Who.One -> Button(onClick = { save(w.client) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Save follow-up") }
                is Who.Several -> ChooseClient(w.candidates) { save(it) }
                Who.Nobody -> Text("I couldn't find that client. Say their name as it's saved in BrokerBuddy.", style = MaterialTheme.typography.bodySmall)
            }
        }

        is VoiceCommand.PortalLeads -> {
            val portals = c.portal?.let { listOf(it) } ?: Portal.entries
            if (c.portal == null) Text("Which portal?", style = MaterialTheme.typography.bodySmall)
            portals.forEach { p ->
                Button(onClick = { onOpen(Routes.portalLeads(p, PortalFilter(c.range))) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${p.label} · ${c.range.label}")
                }
            }
        }

        is VoiceCommand.FindProperties -> Button(
            onClick = {
                onOpen(Routes.propertySearch(PropertySearch(c.transactionType, c.category, c.minPrice, c.maxPrice, c.locality.orEmpty())))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show these properties") }

        is VoiceCommand.LeadStatus -> {
            fun save(client: ClientChoice) {
                saving = true
                scope.launch {
                    val latest = api.call { client(client.id).client }.getOrNull()?.portalLeads
                        ?.filter { c.portal == null || it.portal == c.portal }?.maxByOrNull { it.enquiredAt }
                    if (latest == null) {
                        toast(context, "${client.name} has no ${c.portal?.label ?: "portal"} enquiry. Open their profile to change their status.")
                        onOpen(Routes.client(client.id))
                    } else {
                        api.call { updatePortalLead(latest.id, UpdatePortalLeadRequest(c.status)) }
                            .onSuccess { onDone("${client.name}'s latest ${latest.portal.label} enquiry is now ${c.status.label}") }
                            .onFailure { toast(context, it.message ?: "Couldn't update") }
                    }
                    saving = false
                }
            }
            when (val w = c.who) {
                is Who.One -> Button(onClick = { save(w.client) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Mark as ${c.status.label}") }
                is Who.Several -> ChooseClient(w.candidates) { save(it) }
                Who.Nobody -> Text("I couldn't find that client. Say their name as it's saved in BrokerBuddy.", style = MaterialTheme.typography.bodySmall)
            }
        }

        is VoiceCommand.ListingClients -> {
            if (c.listings.isEmpty()) Text("No 99acres or Housing.com listing with enquiries matches that area or name.", style = MaterialTheme.typography.bodySmall)
            c.listings.take(8).forEach { l ->
                OutlinedButton(onClick = { onOpen(Routes.portalListing(l.portal, l.id, PortalFilter(c.range))) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${l.portal.label} · ${l.title ?: "Listing"}${l.locality?.let { ", $it" }.orEmpty()}")
                }
            }
        }

        is VoiceCommand.Unknown -> Unit
    }
}
