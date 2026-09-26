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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.SegmentedPill
import com.brokerbuddy.ui.theme.brand

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
        SegmentedPill(TransactionType.entries, form.transactionType, { it.label }, { onChange(form.copy(transactionType = it)) })
        FieldNote(FormField.TRANSACTION)

        BrandCard(Modifier.fillMaxWidth(), contentPadding = 4.dp) {
            EditorRow(Icons.Outlined.Apartment, "Property type / BHK *") {
                DropdownField("Choose", PropertyCategory.entries, form.category, { it.label }, { onChange(form.copy(category = it)) }, Modifier.fillMaxWidth())
                FieldNote(FormField.CATEGORY)
            }
            if (showStatus) {
                EditorRow(Icons.Outlined.Flag, "Status") {
                    DropdownField("Status", InquiryStatus.entries, form.status, { it.label }, { if (it != null) onChange(form.copy(status = it)) }, Modifier.fillMaxWidth())
                }
            }
            EditorRow(Icons.Outlined.CurrencyRupee, if (form.transactionType == TransactionType.BUY) "Budget range" else "Budget range (monthly rent)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField("Min", form.budgetMin, { onChange(form.copy(budgetMin = it)) }, Modifier.weight(1f),
                        moneyHint(FormField.BUDGET_MIN, form.budgetMin), FormField.BUDGET_MIN in errors, KeyboardType.Text)
                    Text("to", color = MaterialTheme.brand.muted)
                    NumberField("Max", form.budgetMax, { onChange(form.copy(budgetMax = it)) }, Modifier.weight(1f),
                        moneyHint(FormField.BUDGET_MAX, form.budgetMax), FormField.BUDGET_MAX in errors, KeyboardType.Text)
                }
            }
            EditorRow(Icons.Outlined.LocationOn, "Preferred locations") {
                OutlinedTextField(
                    form.locations, { onChange(form.copy(locations = it)) }, placeholder = { Text("Andheri West, Andheri East") },
                    supportingText = { Text(hint(FormField.LOCATIONS, "Comma separated")!!) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            EditorRow(Icons.Outlined.Chair, "Furnishing (any selected is fine)") {
                ChipSelector(Furnishing.entries, form.furnishing, { it.label }) { onChange(form.copy(furnishing = it)) }
                FieldNote(FormField.FURNISHING)
            }
            EditorRow(Icons.Outlined.LocalParking, "Parking") {
                NumberField("Minimum spots", form.minParking, { onChange(form.copy(minParking = it)) }, Modifier.fillMaxWidth(), hint(FormField.PARKING), FormField.PARKING in errors)
            }
            EditorRow(Icons.Outlined.Stairs, "Preferred floor") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("From", form.floorMin, { onChange(form.copy(floorMin = it)) }, Modifier.weight(1f), hint(FormField.FLOOR_MIN), FormField.FLOOR_MIN in errors)
                    NumberField("Maximum", form.floorMax, { onChange(form.copy(floorMax = it)) }, Modifier.weight(1f), hint(FormField.FLOOR_MAX), FormField.FLOOR_MAX in errors)
                }
            }
            EditorRow(Icons.Outlined.EventAvailable, "Possession") {
                DropdownField("Any", Possession.entries, form.possession, { it.label }, { onChange(form.copy(possession = it)) }, Modifier.fillMaxWidth(), allowNone = true)
                FieldNote(FormField.POSSESSION)
                NumberField(
                    "Needed by (YYYY-MM-DD)", form.possessionBy, { onChange(form.copy(possessionBy = it)) }, Modifier.fillMaxWidth(),
                    supporting = hint(FormField.POSSESSION_BY), isError = FormField.POSSESSION_BY in errors, keyboardType = KeyboardType.Text,
                )
            }
            EditorRow(Icons.Outlined.Star, "Must-haves") {
                Text(
                    "Properties that fail a must-have are never suggested.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.brand.muted,
                )
                ChipSelector(RequirementField.entries, form.mandatory, { it.label }) { onChange(form.copy(mandatory = it)) }
            }
            EditorRow(Icons.AutoMirrored.Outlined.Notes, "Additional preferences") {
                OutlinedTextField(
                    form.notes, { onChange(form.copy(notes = it)) }, placeholder = { Text("E.g. gated society, pet friendly, etc.") },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One requirement field: tinted icon, label, then the input. */
@Composable
private fun EditorRow(icon: ImageVector, label: String, content: @Composable () -> Unit) {
    val b = MaterialTheme.brand
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Box(Modifier.size(36.dp).background(b.info.container, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = b.link, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = b.muted)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
