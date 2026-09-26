package com.brokerbuddy.ui.inquiries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.form.FormField
import com.brokerbuddy.core.form.RequirementForm
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.RequirementField
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.common.ChipSelector
import com.brokerbuddy.ui.common.DropdownField
import com.brokerbuddy.ui.common.NumberField
import com.brokerbuddy.ui.common.SectionTitle
import kotlinx.serialization.json.Json

/** Saves the whole form across rotation/process death as JSON. */
val RequirementFormSaver: Saver<RequirementForm, String> = Saver(
    save = { Json.encodeToString(RequirementForm.serializer(), it) },
    restore = { Json.decodeFromString(RequirementForm.serializer(), it) },
)

/**
 * The requirement fields. When reviewing a voice note, [evidence] shows the words each
 * value came from and [previous] shows what an existing requirement said before.
 */
@Composable
fun RequirementEditor(
    form: RequirementForm,
    onChange: (RequirementForm) -> Unit,
    errors: Map<FormField, String>,
    showStatus: Boolean,
    evidence: Map<FormField, String> = emptyMap(),
    previous: Map<FormField, String?> = emptyMap(),
) {
    fun hint(f: FormField, fallback: String? = null): String? {
        errors[f]?.let { return it }
        val parts = listOfNotNull(
            evidence[f]?.let { "🎙 “$it”" },
            if (f in previous) "was ${previous[f] ?: "not set"}" else null,
        )
        return if (parts.isNotEmpty()) parts.joinToString(" · ") else fallback
    }

    @Composable
    fun FieldNote(f: FormField) {
        hint(f)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (f in errors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }
    }

    fun moneyHint(f: FormField, text: String) =
        hint(f, if (text.isBlank()) "e.g. 65k, 75 L, 1.2 Cr" else Money.parse(text)?.let(Money::full))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TransactionType.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = t == form.transactionType,
                    onClick = { onChange(form.copy(transactionType = t)) },
                    shape = SegmentedButtonDefaults.itemShape(i, TransactionType.entries.size),
                ) { Text(t.label) }
            }
        }
        FieldNote(FormField.TRANSACTION)
        DropdownField("Property type *", PropertyCategory.entries, form.category, { it.label }, { onChange(form.copy(category = it)) }, Modifier.fillMaxWidth())
        FieldNote(FormField.CATEGORY)
        if (showStatus) {
            DropdownField("Status", InquiryStatus.entries, form.status, { it.label }, { if (it != null) onChange(form.copy(status = it)) }, Modifier.fillMaxWidth())
        }

        SectionTitle(if (form.transactionType == TransactionType.BUY) "Purchase budget" else "Monthly rent budget")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Min", form.budgetMin, { onChange(form.copy(budgetMin = it)) }, Modifier.weight(1f),
                moneyHint(FormField.BUDGET_MIN, form.budgetMin), FormField.BUDGET_MIN in errors, KeyboardType.Text)
            NumberField("Max", form.budgetMax, { onChange(form.copy(budgetMax = it)) }, Modifier.weight(1f),
                moneyHint(FormField.BUDGET_MAX, form.budgetMax), FormField.BUDGET_MAX in errors, KeyboardType.Text)
        }

        SectionTitle("Location")
        OutlinedTextField(
            form.locations, { onChange(form.copy(locations = it)) }, label = { Text("Preferred areas") },
            supportingText = { Text(hint(FormField.LOCATIONS, "Comma separated, e.g. Andheri West, Versova, Juhu")!!) },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Furnishing (any selected is acceptable)")
        ChipSelector(Furnishing.entries, form.furnishing, { it.label }) { onChange(form.copy(furnishing = it)) }
        FieldNote(FormField.FURNISHING)

        SectionTitle("Parking & floor")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Min parking", form.minParking, { onChange(form.copy(minParking = it)) }, Modifier.weight(1f), hint(FormField.PARKING), FormField.PARKING in errors)
            NumberField("Floor from", form.floorMin, { onChange(form.copy(floorMin = it)) }, Modifier.weight(1f), hint(FormField.FLOOR_MIN), FormField.FLOOR_MIN in errors)
            NumberField("Floor to", form.floorMax, { onChange(form.copy(floorMax = it)) }, Modifier.weight(1f), hint(FormField.FLOOR_MAX), FormField.FLOOR_MAX in errors)
        }

        SectionTitle("Possession")
        DropdownField("Possession", Possession.entries, form.possession, { it.label }, { onChange(form.copy(possession = it)) }, Modifier.fillMaxWidth(), allowNone = true)
        FieldNote(FormField.POSSESSION)
        NumberField(
            "Needed by (YYYY-MM-DD)", form.possessionBy, { onChange(form.copy(possessionBy = it)) }, Modifier.fillMaxWidth(),
            supporting = hint(FormField.POSSESSION_BY), isError = FormField.POSSESSION_BY in errors, keyboardType = KeyboardType.Text,
        )

        SectionTitle("Must-haves")
        Text(
            "Properties that fail a must-have are never suggested.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipSelector(RequirementField.entries, form.mandatory, { it.label }) { onChange(form.copy(mandatory = it)) }

        OutlinedTextField(form.notes, { onChange(form.copy(notes = it)) }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
    }
}
