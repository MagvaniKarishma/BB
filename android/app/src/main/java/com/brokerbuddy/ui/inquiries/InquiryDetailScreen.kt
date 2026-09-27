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
import com.brokerbuddy.core.match.MatchLabel
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.brokerbuddy.core.model.Property
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.FilterTabs
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.design.PropertyPhoto
import com.brokerbuddy.ui.design.TabItem
import com.brokerbuddy.ui.design.chipLabel
import com.brokerbuddy.ui.design.priceText
import com.brokerbuddy.ui.design.whatsAppIcon
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.brand

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
            val clientName = (loader.state as? Load.Ready)?.value?.client?.name
            BackTopBar(if (tab == 1 && clientName != null) "Matches for $clientName" else "Requirement", onBack) {
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
                        1 -> MatchesTab(inquiry, onProperty)
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
        LabeledValue("Floor${must(RequirementField.FLOOR)}", i.floorPreference.takeIf { it.isNotEmpty() }?.joinToString(" or ") { it.label })
        LabeledValue("Property type${must(RequirementField.PROPERTY_TYPE)}", i.propertyTypes.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label })
        LabeledValue("Possession${must(RequirementField.POSSESSION)}", i.possession?.label)
        LabeledValue("Needed by", i.possessionBy?.let(::formatDate))
        LabeledValue("Notes", i.notes)
        LabeledValue("Last updated", formatDateTime(i.updatedAt))
    }
}

private enum class MatchFilter(val label: String) { ALL("All"), EXACT("Exact"), VERIFY("To verify") }

/** Matches (screen 10): eligible listings, best first, shareable with the client. */
@Composable
private fun MatchesTab(inquiry: Inquiry, onProperty: (String) -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val loader = rememberLoad(inquiry.id) { api.call { inquiryMatches(inquiry.id).matches } }
    var filter by rememberSaveable { mutableStateOf(MatchFilter.ALL) }
    LoadContent(loader) { matches ->
        val exact = matches.filter { MatchLabel.isExact(it.checks) }
        val verify = matches.filter { it.needsVerification.isNotEmpty() }
        val shown = when (filter) {
            MatchFilter.ALL -> matches
            MatchFilter.EXACT -> exact
            MatchFilter.VERIFY -> verify
        }
        Column(Modifier.fillMaxSize()) {
            FilterTabs(
                listOf(
                    TabItem(MatchFilter.ALL, "All", matches.size),
                    TabItem(MatchFilter.EXACT, "Exact", exact.size),
                    TabItem(MatchFilter.VERIFY, "To verify", verify.size),
                ),
                selected = filter,
                onSelect = { filter = it },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (shown.isEmpty()) {
                    EmptyMessage(
                        if (matches.isEmpty()) "No available properties satisfy this requirement's must-haves yet."
                        else "Nothing in this list.",
                    )
                }
                shown.forEach { m ->
                    MatchCard(
                        m,
                        onClick = { onProperty(m.property.id) },
                        onShare = inquiry.client?.let { c -> { openWhatsApp(context, c.primaryPhone, shareText(m.property)) } },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun shareText(p: Property): String = listOfNotNull(
    p.title,
    listOfNotNull(p.building, p.locality).joinToString(", "),
    priceText(p),
    listOfNotNull(p.carpetAreaSqft?.let { "$it sq ft" }, p.furnishing?.chipLabel()).joinToString(" · ").ifEmpty { null },
).joinToString("\n")

@Composable
private fun MatchCard(m: PropertyMatch, onClick: () -> Unit, onShare: (() -> Unit)?) {
    val b = MaterialTheme.brand
    val p = m.property
    val exact = MatchLabel.isExact(m.checks)
    val scoreTint = when {
        exact -> b.success
        m.score >= 75 -> b.amber
        else -> b.neutral
    }
    BrandCard(Modifier.fillMaxWidth().padding(vertical = 5.dp), onClick = onClick, contentPadding = 10.dp) {
        Row {
            Box(Modifier.size(width = 108.dp, height = 112.dp).clip(RoundedCornerShape(14.dp))) {
                PropertyPhoto(p.id, p.photoIds.firstOrNull(), Modifier.fillMaxSize(), maxPx = 400)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Pill(MatchLabel.of(m.score, m.checks), scoreTint)
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(p.locality, style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1)
                Text(priceText(p), style = MaterialTheme.typography.titleSmall, color = b.link)
                val facts = listOfNotNull(
                    p.bedrooms?.let { if (it == 1) "1 Bed" else "$it Beds" },
                    p.carpetAreaSqft?.let { "$it sq ft" },
                ).joinToString(" • ")
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = b.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Pill(p.transactionType.label, if (p.transactionType == TransactionType.RENT) b.success else b.info)
                    p.furnishing?.let { Pill(it.chipLabel(), b.neutral) }
                }
            }
        }
        // Why it's partial: what doesn't match, what's close, and what the listing hasn't recorded.
        m.checks.filter { it.outcome == "near" || it.outcome == "unknown" || it.outcome == "mismatch" }.forEach { CheckLine(it) }
        if (m.needsVerification.isNotEmpty()) {
            Text("Verify with owner: ${m.needsVerification.joinToString { it.label }}", color = b.amber.content, style = MaterialTheme.typography.bodySmall)
        }
        if (onShare != null) {
            TextButton(onClick = onShare) {
                Icon(whatsAppIcon(), null, tint = Brand.WhatsApp, modifier = Modifier.size(18.dp))
                Text("  Send to client")
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
