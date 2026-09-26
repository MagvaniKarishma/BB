package com.brokerbuddy.ui.inquiries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.RequirementField
import com.brokerbuddy.core.model.RequirementRequest
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.ChipSelector
import com.brokerbuddy.ui.common.DropdownField
import com.brokerbuddy.ui.common.NumberField
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberText
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A text field value parsed into an optional typed value, or an error. */
private sealed interface Parsed<out T> {
    data object Blank : Parsed<Nothing>
    data class Ok<T>(val value: T) : Parsed<T>
    data object Invalid : Parsed<Nothing>
}

private fun <T> parse(text: String, parser: (String) -> T?): Parsed<T> =
    if (text.isBlank()) Parsed.Blank else parser(text.trim())?.let { Parsed.Ok(it) } ?: Parsed.Invalid

private fun <T> Parsed<T>.orNull(): T? = (this as? Parsed.Ok)?.value

@Composable
fun InquiryFormScreen(clientId: String?, inquiryId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()

    var type by rememberSaveable { mutableStateOf(TransactionType.RENT) }
    var category by rememberSaveable { mutableStateOf<PropertyCategory?>(null) }
    var status by rememberSaveable { mutableStateOf(InquiryStatus.ACTIVE) }
    var budgetMin by rememberText()
    var budgetMax by rememberText()
    var locations by rememberText()
    var furnishing by rememberSaveable { mutableStateOf(setOf<Furnishing>()) }
    var minParking by rememberText()
    var floorMin by rememberText()
    var floorMax by rememberText()
    var possession by rememberSaveable { mutableStateOf<Possession?>(null) }
    var possessionBy by rememberText()
    var mandatory by rememberSaveable { mutableStateOf(setOf(RequirementField.BUDGET)) }
    var notes by rememberText()
    var loaded by rememberSaveable { mutableStateOf(inquiryId == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inquiryId) {
        if (inquiryId == null || loaded) return@LaunchedEffect
        api.call { inquiry(inquiryId).inquiry }.onSuccess { i: Inquiry ->
            type = i.transactionType; category = i.category; status = i.status
            budgetMin = i.budgetMin?.toString() ?: ""; budgetMax = i.budgetMax?.toString() ?: ""
            locations = i.locations.joinToString(", ")
            furnishing = i.furnishing.toSet()
            minParking = i.minParking?.toString() ?: ""
            floorMin = i.floorMin?.toString() ?: ""; floorMax = i.floorMax?.toString() ?: ""
            possession = i.possession; possessionBy = i.possessionBy?.take(10) ?: ""
            mandatory = i.mandatory.toSet(); notes = i.notes ?: ""
            loaded = true
        }.onFailure { error = it.message }
    }

    val pMin = parse(budgetMin, Money::parse)
    val pMax = parse(budgetMax, Money::parse)
    val pParking = parse(minParking) { it.toIntOrNull()?.takeIf { n -> n in 0..20 } }
    val pFloorMin = parse(floorMin) { it.toIntOrNull()?.takeIf { n -> n in -5..200 } }
    val pFloorMax = parse(floorMax) { it.toIntOrNull()?.takeIf { n -> n in -5..200 } }
    val pDate = parse(possessionBy) { runCatching { LocalDate.parse(it) }.getOrNull() }
    val budgetOrderOk = pMin.orNull()?.let { mn -> pMax.orNull()?.let { mx -> mn <= mx } } ?: true
    val floorOrderOk = pFloorMin.orNull()?.let { mn -> pFloorMax.orNull()?.let { mx -> mn <= mx } } ?: true
    val anyInvalid = listOf(pMin, pMax, pParking, pFloorMin, pFloorMax, pDate).any { it is Parsed.Invalid }
    val valid = loaded && category != null && !anyInvalid && budgetOrderOk && floorOrderOk

    fun save() {
        val cat = category ?: return
        error = null
        busy = true
        val body = RequirementRequest(
            transactionType = type,
            category = cat,
            status = status,
            budgetMin = pMin.orNull(),
            budgetMax = pMax.orNull(),
            locations = locations.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            furnishing = furnishing.toList(),
            minParking = pParking.orNull(),
            floorMin = pFloorMin.orNull(),
            floorMax = pFloorMax.orNull(),
            possession = possession,
            possessionBy = pDate.orNull()?.toString(),
            mandatory = mandatory.toList(),
            notes = notes.trim().ifEmpty { null },
        )
        scope.launch {
            val result = if (inquiryId == null) {
                api.call { createInquiry(clientId!!, body).inquiry }
            } else {
                api.call { updateInquiry(inquiryId, body).inquiry }
            }
            result.onSuccess { onSaved(it.id) }.onFailure { error = it.message }
            busy = false
        }
    }

    fun moneyHint(p: Parsed<Long>) = when (p) {
        Parsed.Blank -> "e.g. 65k, 75 L, 1.2 Cr"
        Parsed.Invalid -> "Couldn't read this amount"
        is Parsed.Ok -> Money.full(p.value)
    }

    Scaffold(topBar = { BackTopBar(if (inquiryId == null) "New requirement" else "Edit requirement", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Leave anything the client hasn't said blank — BrokerBuddy never fills in guesses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TransactionType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = t == type,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, TransactionType.entries.size),
                    ) { Text(t.label) }
                }
            }
            DropdownField("Property type *", PropertyCategory.entries, category, { it.label }, { category = it }, Modifier.fillMaxWidth())
            if (inquiryId != null) {
                DropdownField("Status", InquiryStatus.entries, status, { it.label }, { if (it != null) status = it }, Modifier.fillMaxWidth())
            }

            SectionTitle(if (type == TransactionType.RENT) "Monthly rent budget" else "Purchase budget")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Min", budgetMin, { budgetMin = it }, Modifier.weight(1f), moneyHint(pMin), pMin is Parsed.Invalid, KeyboardType.Text)
                NumberField(
                    "Max", budgetMax, { budgetMax = it }, Modifier.weight(1f),
                    if (!budgetOrderOk) "Max is below min" else moneyHint(pMax),
                    pMax is Parsed.Invalid || !budgetOrderOk, KeyboardType.Text,
                )
            }

            SectionTitle("Location")
            OutlinedTextField(
                locations, { locations = it }, label = { Text("Preferred areas") },
                supportingText = { Text("Comma separated, e.g. Andheri West, Versova, Juhu") },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Furnishing (any selected is acceptable)")
            ChipSelector(Furnishing.entries, furnishing, { it.label }) { furnishing = it }

            SectionTitle("Parking & floor")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Min parking", minParking, { minParking = it }, Modifier.weight(1f), isError = pParking is Parsed.Invalid)
                NumberField("Floor from", floorMin, { floorMin = it }, Modifier.weight(1f), isError = pFloorMin is Parsed.Invalid || !floorOrderOk)
                NumberField("Floor to", floorMax, { floorMax = it }, Modifier.weight(1f), isError = pFloorMax is Parsed.Invalid || !floorOrderOk)
            }

            SectionTitle("Possession")
            DropdownField("Possession", Possession.entries, possession, { it.label }, { possession = it }, Modifier.fillMaxWidth(), allowNone = true)
            NumberField(
                "Needed by (YYYY-MM-DD)", possessionBy, { possessionBy = it }, Modifier.fillMaxWidth(),
                supporting = if (pDate is Parsed.Invalid) "Use the format 2027-06-30" else null,
                isError = pDate is Parsed.Invalid, keyboardType = KeyboardType.Text,
            )

            SectionTitle("Must-haves")
            Text(
                "Properties that fail a must-have are never suggested.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipSelector(RequirementField.entries, mandatory, { it.label }) { mandatory = it }

            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Saving…" else "Save requirement")
            }
        }
    }
}
