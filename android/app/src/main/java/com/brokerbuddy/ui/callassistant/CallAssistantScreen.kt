package com.brokerbuddy.ui.callassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.AiCall
import com.brokerbuddy.core.model.AssistantVoice
import com.brokerbuddy.core.model.CallAssistantInfo
import com.brokerbuddy.core.model.CallAssistantSettings
import com.brokerbuddy.core.model.CallMode
import com.brokerbuddy.core.model.GreetingLanguage
import com.brokerbuddy.core.model.HumanTransfer
import com.brokerbuddy.core.model.Role as UserRole
import com.brokerbuddy.core.model.UnclearBehavior
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.formatDateTime
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.launch

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")
private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

/** Settings → AI Call Assistant: call mode, Voice & Greeting, hours, callbacks, hand-over. */
@Composable
fun CallAssistantScreen(onBack: () -> Unit, onTest: () -> Unit, onClient: (String) -> Unit) {
    val container = appContainer()
    val me = rememberLoad { container.api.call { me() } }
    val info = rememberLoad { container.api.call { callAssistant() } }
    val calls = rememberLoad { container.api.call { aiCalls().calls } }
    val role = (me.state as? com.brokerbuddy.ui.common.Load.Ready)?.value?.user?.role
    val manager = role == UserRole.OWNER || role == UserRole.ADMIN

    Scaffold(topBar = { BackTopBar("AI Call Assistant", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(info) { data ->
                Content(data, manager, onTest, onClient, onSaved = info.reload, calls = (calls.state as? com.brokerbuddy.ui.common.Load.Ready)?.value)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(
    data: CallAssistantInfo,
    manager: Boolean,
    onTest: () -> Unit,
    onClient: (String) -> Unit,
    onSaved: () -> Unit,
    calls: List<AiCall>?,
) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val b = MaterialTheme.brand
    var s by remember(data.settings) { mutableStateOf(data.settings) }
    var transfer by remember(data.settings) { mutableStateOf(data.settings.transferNumber?.let(PhoneNumbers::display) ?: "") }
    var numbers by remember(data.settings) { mutableStateOf(data.settings.businessNumbers.joinToString(", ") { PhoneNumbers.display(it) }) }
    var saving by remember { mutableStateOf(false) }
    val dirty = s != data.settings ||
        transfer != (data.settings.transferNumber?.let(PhoneNumbers::display) ?: "") ||
        numbers != data.settings.businessNumbers.joinToString(", ") { PhoneNumbers.display(it) }

    fun save() {
        if (!TIME.matches(s.businessHours.start) || !TIME.matches(s.businessHours.end)) {
            toast(context, "Business hours must be like 09:30")
            return
        }
        val out: CallAssistantSettings = s.copy(
            transferNumber = transfer.trim().ifEmpty { null },
            businessNumbers = numbers.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
        )
        scope.launch {
            saving = true
            container.api.call { updateCallAssistant(out) }
                .onSuccess { toast(context, "Saved"); onSaved() }
                .onFailure { toast(context, it.message ?: "Couldn't save") }
            saving = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (data.capabilities.provider == null) {
            BrandCard(Modifier.fillMaxWidth()) {
                Text("Not connected to a phone number yet", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Callers can't reach the assistant until a business phone number from a telephony provider is connected on the " +
                        "server (see the setup guide). You can already record greetings and try the conversation with “Test your assistant”.",
                    style = MaterialTheme.typography.bodySmall, color = b.muted,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!manager) {
            Text("Only the owner or an admin can change these settings.", style = MaterialTheme.typography.bodySmall, color = b.muted)
        }

        // ----- on/off and mode -----
        BrandCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI Call Assistant", style = MaterialTheme.typography.titleMedium)
                    Text(if (s.enabled) "On" else "Off — calls ring your team", style = MaterialTheme.typography.bodySmall, color = b.muted)
                }
                Switch(checked = s.enabled, enabled = manager, onCheckedChange = { s = s.copy(enabled = it) })
            }
            Spacer(Modifier.height(8.dp))
            CallMode.entries.forEach { m ->
                OptionRow(m.label, m.description, selected = s.mode == m, enabled = manager) { s = s.copy(mode = m) }
            }
        }

        // ----- Voice & Greeting -----
        SectionTitle("Voice & Greeting")
        BrandCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Play my recorded greeting", style = MaterialTheme.typography.titleSmall)
                    Text("Off: the AI voice reads the greeting script.", style = MaterialTheme.typography.bodySmall, color = b.muted)
                }
                Switch(checked = s.customGreetingEnabled, enabled = manager, onCheckedChange = { s = s.copy(customGreetingEnabled = it) })
            }
            Spacer(Modifier.height(8.dp))
            Text("Default language (when the caller's language isn't known yet)", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GreetingLanguage.entries.forEach { l ->
                    FilterChip(selected = s.defaultLanguage == l, enabled = manager, onClick = { s = s.copy(defaultLanguage = l) }, label = { Text(l.label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "After your greeting the assistant always says it's an AI assistant, so callers never think they're talking to you.",
                style = MaterialTheme.typography.bodySmall, color = b.info.content,
            )
        }
        Spacer(Modifier.height(8.dp))
        data.greetings.forEach { g ->
            GreetingCard(g, canEdit = manager, maxSeconds = data.capabilities.maxGreetingSeconds, isDefaultLanguage = g.language == s.defaultLanguage, onChanged = onSaved)
            Spacer(Modifier.height(8.dp))
        }

        // ----- voice after the greeting -----
        SectionTitle("AI voice")
        BrandCard(Modifier.fillMaxWidth()) {
            AssistantVoice.entries.forEach { v ->
                val availability = data.capabilities.voices[v]
                val available = availability?.available == true
                OptionRow(
                    v.label,
                    if (available) v.description else "${v.description} Not available: ${availability?.reason ?: "not set up"}.",
                    selected = s.voice == v,
                    enabled = manager && available,
                ) { s = s.copy(voice = v) }
            }
        }

        // ----- hours -----
        SectionTitle("Business hours")
        BrandCard(Modifier.fillMaxWidth()) {
            Text("Smart mode rings your team during these hours; the AI answers outside them.", style = MaterialTheme.typography.bodySmall, color = b.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAYS.forEachIndexed { i, label ->
                    val day = i + 1
                    FilterChip(
                        selected = day in s.businessHours.days,
                        enabled = manager,
                        onClick = {
                            val days = if (day in s.businessHours.days) s.businessHours.days - day else (s.businessHours.days + day).sorted()
                            s = s.copy(businessHours = s.businessHours.copy(days = days))
                        },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.businessHours.start, onValueChange = { s = s.copy(businessHours = s.businessHours.copy(start = it.take(5))) },
                    label = { Text("Opens") }, enabled = manager, singleLine = true, isError = !TIME.matches(s.businessHours.start), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = s.businessHours.end, onValueChange = { s = s.copy(businessHours = s.businessHours.copy(end = it.take(5))) },
                    label = { Text("Closes") }, enabled = manager, singleLine = true, isError = !TIME.matches(s.businessHours.end), modifier = Modifier.weight(1f),
                )
            }
        }

        // ----- callbacks, unclear callers, hand-over -----
        SectionTitle("Callbacks")
        BrandCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Create a follow-up to call back", modifier = Modifier.weight(1f))
                Switch(checked = s.callbackReminder, enabled = manager, onCheckedChange = { s = s.copy(callbackReminder = it) })
            }
            if (s.callbackReminder) {
                Text("Due after", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Now", 15 to "15 min", 60 to "1 hour", 240 to "4 hours").forEach { (m, label) ->
                        FilterChip(selected = s.callbackDelayMinutes == m, enabled = manager, onClick = { s = s.copy(callbackDelayMinutes = m) }, label = { Text(label) })
                    }
                }
            }
        }
        SectionTitle("When the AI can't understand the caller")
        BrandCard(Modifier.fillMaxWidth()) {
            Text("It asks again up to ${s.maxUnclearRetries} times, then:", style = MaterialTheme.typography.bodySmall, color = b.muted)
            UnclearBehavior.entries.forEach { u ->
                OptionRow(u.label, null, selected = s.unclearBehavior == u, enabled = manager) { s = s.copy(unclearBehavior = u) }
            }
        }
        SectionTitle("Hand over to a person")
        BrandCard(Modifier.fillMaxWidth()) {
            HumanTransfer.entries.forEach { h ->
                OptionRow(h.label, null, selected = s.humanTransfer == h, enabled = manager) { s = s.copy(humanTransfer = h) }
            }
            OutlinedTextField(
                value = transfer, onValueChange = { transfer = it }, enabled = manager, singleLine = true,
                label = { Text("Team number to ring / hand over to") },
                supportingText = { Text("Used by Smart mode, hand-overs and Direct mode. Hand-overs only happen during business hours.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = numbers, onValueChange = { numbers = it }, enabled = manager,
                label = { Text("Business number(s) callers dial") },
                supportingText = { Text("The number(s) from your telephony provider, separated by commas.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            )
        }

        if (manager) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { save() }, enabled = dirty && !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Saving…" else "Save settings")
            }
        }

        SectionTitle("Try it")
        OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("Test your assistant") }
        Text(
            "Type what a caller might say and see how the assistant answers. Test conversations are never saved as leads.",
            style = MaterialTheme.typography.bodySmall, color = b.muted,
        )

        SectionTitle("Recent AI calls")
        when {
            calls == null -> Text("Loading…", color = b.muted)
            calls.isEmpty() -> Text("No calls yet.", color = b.muted)
            else -> calls.take(20).forEach { c -> CallRow(c, onClient) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted)
        }
    }
}

@Composable
private fun CallRow(c: AiCall, onClient: (String) -> Unit) {
    val b = MaterialTheme.brand
    BrandCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = c.clientId?.let { id -> { onClient(id) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.fromNumber?.let(PhoneNumbers::display) ?: "Hidden number", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(formatDateTime(c.startedAt), style = MaterialTheme.typography.bodySmall, color = b.muted)
        }
        c.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (c.callbackRequested) Pill("Callback", b.amber)
            if (c.humanRequested) Pill("Asked for a person", b.purple)
            if (c.status == "INTERRUPTED") Pill("Ended early", b.danger)
        }
    }
}
