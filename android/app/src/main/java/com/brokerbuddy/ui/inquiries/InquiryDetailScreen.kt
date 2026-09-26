package com.brokerbuddy.ui.inquiries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.FieldCheck
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryRevision
import com.brokerbuddy.core.model.PropertyMatch
import com.brokerbuddy.core.model.RequirementField
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.LabeledValue
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.formatDate
import com.brokerbuddy.ui.common.formatDateTime
import com.brokerbuddy.ui.common.rememberLoad
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One-line summary: "Up to ₹70K · Andheri, Jogeshwari · Semi-furnished · 1+ parking". */
fun requirementSummary(i: Inquiry): String = listOfNotNull(
    Money.range(i.budgetMin, i.budgetMax),
    i.locations.takeIf { it.isNotEmpty() }?.joinToString(", "),
    i.furnishing.takeIf { it.isNotEmpty() }?.joinToString("/") { it.label },
    i.minParking?.takeIf { it > 0 }?.let { "$it+ parking" },
    i.possession?.label,
).joinToString(" · ").ifEmpty { "No details recorded yet" }

@Composable
fun InquiryDetailScreen(
    inquiryId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onClient: (String) -> Unit,
    onProperty: (String) -> Unit,
    onVoiceNote: (clientId: String) -> Unit,
    initialTab: Int = 0,
) {
    val api = appContainer().api
    val loader = rememberLoad(inquiryId) { api.call { inquiry(inquiryId).inquiry } }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    Scaffold(
        topBar = {
            BackTopBar("Requirement", onBack) {
                val clientId = (loader.state as? Load.Ready)?.value?.clientId
                if (clientId != null) {
                    IconButton(onClick = { onVoiceNote(clientId) }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Update by voice note")
                    }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(loader) { inquiry ->
                Column(Modifier.fillMaxSize()) {
                    inquiry.client?.let { c ->
                        TextButton(onClick = { onClient(c.id) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text("${c.name} · ${PhoneNumbers.display(c.primaryPhone)}")
                        }
                    }
                    TabRow(selectedTabIndex = tab) {
                        listOf("Details", "Matches", "History").forEachIndexed { i, title ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                        }
                    }
                    when (tab) {
                        0 -> RequirementDetails(inquiry)
                        1 -> MatchesTab(inquiry.id, onProperty)
                        else -> HistoryTab(inquiry.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequirementDetails(i: Inquiry) {
    val must = { f: RequirementField -> if (f in i.mandatory) " (must)" else "" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("${i.transactionType.label} · ${i.category.label}", style = MaterialTheme.typography.titleLarge)
        Text("${i.status.label} · version ${i.version}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledValue("Budget${must(RequirementField.BUDGET)}", Money.range(i.budgetMin, i.budgetMax))
        LabeledValue("Locations${must(RequirementField.LOCATION)}", i.locations.takeIf { it.isNotEmpty() }?.joinToString(", "))
        LabeledValue("Furnishing${must(RequirementField.FURNISHING)}", i.furnishing.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label })
        LabeledValue("Parking${must(RequirementField.PARKING)}", i.minParking?.let { "At least $it" })
        LabeledValue(
            "Floor${must(RequirementField.FLOOR)}",
            when {
                i.floorMin != null && i.floorMax != null -> "${i.floorMin} – ${i.floorMax}"
                i.floorMin != null -> "${i.floorMin} or above"
                i.floorMax != null -> "Up to ${i.floorMax}"
                else -> null
            },
        )
        LabeledValue("Possession${must(RequirementField.POSSESSION)}", i.possession?.label)
        LabeledValue("Needed by", i.possessionBy?.let(::formatDate))
        LabeledValue("Notes", i.notes)
        LabeledValue("Last updated", formatDateTime(i.updatedAt))
    }
}

@Composable
private fun MatchesTab(inquiryId: String, onProperty: (String) -> Unit) {
    val api = appContainer().api
    val loader = rememberLoad(inquiryId) { api.call { inquiryMatches(inquiryId).matches } }
    LoadContent(loader) { matches ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (matches.isEmpty()) {
                EmptyMessage("No available properties satisfy this requirement's must-haves yet.")
            }
            matches.forEach { MatchCard(it) { onProperty(it.property.id) } }
        }
    }
}

@Composable
private fun MatchCard(m: PropertyMatch, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(m.property.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${m.score}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("${Money.compact(m.property.price)} · ${m.property.locality}")
            m.checks.filter { it.outcome != "n/a" }.forEach { CheckLine(it) }
            if (m.needsVerification.isNotEmpty()) {
                Text(
                    "Verify with owner: ${m.needsVerification.joinToString { it.label }}",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun CheckLine(c: FieldCheck) {
    val (mark, color) = when (c.outcome) {
        "match" -> "✓" to MaterialTheme.colorScheme.primary
        "near" -> "≈" to MaterialTheme.colorScheme.tertiary
        "unknown" -> "?" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "✗" to MaterialTheme.colorScheme.error
    }
    Text("$mark ${c.detail}", color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HistoryTab(inquiryId: String) {
    val api = appContainer().api
    val loader = rememberLoad(inquiryId) { api.call { inquiryHistory(inquiryId).revisions } }
    LoadContent(loader) { revisions ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            revisions.forEach { RevisionCard(it) }
        }
    }
}

@Composable
private fun RevisionCard(r: InquiryRevision) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "v${r.version} · ${formatDateTime(r.createdAt)}" + (r.changedBy?.let { " · ${it.name}" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            r.voiceNote?.transcript?.let {
                Text("🎙 From voice note: “${it.take(200)}”", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (r.version == 1) {
                Text("Requirement created (${r.source.name.lowercase().replace('_', ' ')})", style = MaterialTheme.typography.bodySmall)
            } else {
                r.changes.forEach { (field, change) ->
                    val c = change.jsonObject
                    Text(
                        "$field: ${show(c["from"])} → ${show(c["to"])}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun show(e: JsonElement?): String = when (e) {
    null, JsonNull -> "—"
    is JsonArray -> if (e.isEmpty()) "—" else e.joinToString(", ") { show(it) }
    is JsonPrimitive -> e.content
    is JsonObject -> e.toString()
}
