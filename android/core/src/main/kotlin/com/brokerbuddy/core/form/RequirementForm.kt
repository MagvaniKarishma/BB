package com.brokerbuddy.core.form

import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.FloorBand
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.PropertyType
import com.brokerbuddy.core.model.RequirementDraft
import com.brokerbuddy.core.model.RequirementField
import com.brokerbuddy.core.model.RequirementRequest
import com.brokerbuddy.core.model.RequirementSource
import com.brokerbuddy.core.model.TransactionType
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Form fields that can be filled from a voice note. */
enum class FormField(val label: String) {
    TRANSACTION("Rent / Buy"),
    CATEGORY("BHK"),
    BUDGET_MIN("Min budget"),
    BUDGET_MAX("Max budget"),
    LOCATIONS("Locations"),
    FURNISHING("Furnishing"),
    PARKING("Parking"),
    FLOORS("Floor"),
    PROPERTY_TYPES("Property type"),
    POSSESSION("Possession"),
    POSSESSION_BY("Needed by"),
}

/**
 * Editable requirement as the agent sees it: raw text for numeric fields so partial
 * or invalid input can be shown with an error instead of being silently dropped.
 * Serializable so screens can save it across configuration changes.
 */
@Serializable
data class RequirementForm(
    val transactionType: TransactionType? = null,
    val category: PropertyCategory? = null,
    val status: InquiryStatus = InquiryStatus.ACTIVE,
    val budgetMin: String = "",
    val budgetMax: String = "",
    val locations: String = "",
    val furnishing: Set<Furnishing> = emptySet(),
    val minParking: String = "",
    /** Lower / middle / higher floors; exact floors are never a client preference. */
    val floorPreference: Set<FloorBand> = emptySet(),
    val propertyTypes: Set<PropertyType> = emptySet(),
    val possession: Possession? = null,
    val possessionBy: String = "",
    val mandatory: Set<RequirementField> = setOf(RequirementField.BUDGET),
    val notes: String = "",
) {
    fun locationList(): List<String> = locations.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun validate(): FormValidation {
        val errors = mutableMapOf<FormField, String>()
        fun <T> field(f: FormField, text: String, message: String, parse: (String) -> T?): T? {
            if (text.isBlank()) return null
            return parse(text.trim()) ?: run { errors[f] = message; null }
        }
        val min = field(FormField.BUDGET_MIN, budgetMin, "Couldn't read this amount", Money::parse)
        val max = field(FormField.BUDGET_MAX, budgetMax, "Couldn't read this amount", Money::parse)
        val parking = field(FormField.PARKING, minParking, "0–20") { it.toIntOrNull()?.takeIf { n -> n in 0..20 } }
        val by = field(FormField.POSSESSION_BY, possessionBy, "Use the format 2027-06-30") {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        if (min != null && max != null && min > max) errors[FormField.BUDGET_MAX] = "Max is below min"
        if (transactionType == null) errors[FormField.TRANSACTION] = "Choose rent or buy"
        if (category == null) errors[FormField.CATEGORY] = "Choose the BHK"

        val request = if (errors.isEmpty()) {
            RequirementRequest(
                transactionType = transactionType!!,
                category = category!!,
                status = status,
                budgetMin = min,
                budgetMax = max,
                locations = locationList(),
                furnishing = furnishing.sortedBy { it.ordinal },
                minParking = parking,
                floorPreference = floorPreference.sortedBy { it.ordinal },
                propertyTypes = propertyTypes.sortedBy { it.ordinal },
                possession = possession,
                possessionBy = by?.toString(),
                mandatory = mandatory.sortedBy { it.ordinal },
                notes = notes.trim().ifEmpty { null },
            )
        } else {
            null
        }
        return FormValidation(errors, request)
    }

    /** Human-readable value of a field, for "was X" hints. */
    fun display(f: FormField): String? = when (f) {
        FormField.TRANSACTION -> transactionType?.label
        FormField.CATEGORY -> category?.label
        FormField.BUDGET_MIN -> Money.parse(budgetMin)?.let(Money::full)
        FormField.BUDGET_MAX -> Money.parse(budgetMax)?.let(Money::full)
        FormField.LOCATIONS -> locationList().takeIf { it.isNotEmpty() }?.joinToString(", ")
        FormField.FURNISHING -> furnishing.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal }?.joinToString(", ") { it.label }
        FormField.PARKING -> minParking.ifBlank { null }
        FormField.FLOORS -> floorPreference.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal }?.joinToString(", ") { it.label }
        FormField.PROPERTY_TYPES -> propertyTypes.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal }?.joinToString(", ") { it.label }
        FormField.POSSESSION -> possession?.label
        FormField.POSSESSION_BY -> possessionBy.ifBlank { null }
    }

    companion object {
        fun fromInquiry(i: Inquiry) = RequirementForm(
            transactionType = i.transactionType,
            category = i.category,
            status = i.status,
            budgetMin = i.budgetMin?.toString() ?: "",
            budgetMax = i.budgetMax?.toString() ?: "",
            locations = i.locations.joinToString(", "),
            furnishing = i.furnishing.toSet(),
            minParking = i.minParking?.toString() ?: "",
            floorPreference = i.floorPreference.toSet(),
            propertyTypes = i.propertyTypes.toSet(),
            possession = i.possession,
            possessionBy = i.possessionBy?.take(10) ?: "",
            mandatory = i.mandatory.toSet(),
            notes = i.notes ?: "",
        )
    }
}

data class FormValidation(val errors: Map<FormField, String>, val request: RequirementRequest?) {
    val isValid get() = request != null
    fun forVoice(): RequirementRequest? = request?.copy(source = RequirementSource.VOICE_NOTE)
}

/** Result of overlaying a voice draft onto a form. */
data class DraftMerge(
    val form: RequirementForm,
    /** Field → the transcript words it was taken from. */
    val evidence: Map<FormField, String>,
    /** Field → previous value, for fields the draft changed on an existing requirement. */
    val previous: Map<FormField, String?>,
)

/**
 * Applies only the fields the speaker actually stated. Everything else keeps the base
 * value (the existing inquiry, or blank for a new one) — nothing is guessed. New
 * locations are added to existing ones rather than replacing them; the agent can
 * remove any during review.
 */
fun RequirementForm.applyDraft(draft: RequirementDraft): DraftMerge {
    var f = this
    val evidence = mutableMapOf<FormField, String>()
    draft.transactionType?.let { f = f.copy(transactionType = it.value); evidence[FormField.TRANSACTION] = it.evidence }
    draft.category?.let { f = f.copy(category = it.value); evidence[FormField.CATEGORY] = it.evidence }
    draft.budgetMin?.let { f = f.copy(budgetMin = it.value.toString()); evidence[FormField.BUDGET_MIN] = it.evidence }
    draft.budgetMax?.let { f = f.copy(budgetMax = it.value.toString()); evidence[FormField.BUDGET_MAX] = it.evidence }
    draft.locations?.takeIf { it.isNotEmpty() }?.let { locs ->
        val merged = (f.locationList() + locs.map { it.value }).distinctBy { it.lowercase() }
        f = f.copy(locations = merged.joinToString(", "))
        evidence[FormField.LOCATIONS] = locs.joinToString(", ") { it.evidence }
    }
    draft.furnishing?.let { f = f.copy(furnishing = it.value.toSet()); evidence[FormField.FURNISHING] = it.evidence }
    draft.minParking?.let { f = f.copy(minParking = it.value.toString()); evidence[FormField.PARKING] = it.evidence }
    draft.floorPreference?.takeIf { it.value.isNotEmpty() }?.let { f = f.copy(floorPreference = it.value.toSet()); evidence[FormField.FLOORS] = it.evidence }
    draft.possession?.let { f = f.copy(possession = it.value); evidence[FormField.POSSESSION] = it.evidence }
    draft.possessionBy?.let { f = f.copy(possessionBy = it.value); evidence[FormField.POSSESSION_BY] = it.evidence }

    val previous = evidence.keys
        .filter { display(it) != f.display(it) && display(it) != null }
        .associateWith { display(it) }
    return DraftMerge(f, evidence, previous)
}
