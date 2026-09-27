package com.brokerbuddy.core.match

import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.Availability
import com.brokerbuddy.core.model.FloorBand
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.Property
import com.brokerbuddy.core.model.RequirementField

/**
 * The server's matcher (backend/src/domain/matching.ts), on the phone, for the offline demo.
 * Same rules, weights and wording; MatchingParityTest checks it against the server's answers.
 */
object Matching {
    enum class Outcome(val wire: String, val credit: Double) {
        MATCH("match", 1.0), NEAR("near", 0.5), UNKNOWN("unknown", 0.5), MISMATCH("mismatch", 0.0), NA("n/a", 0.0)
    }

    data class Check(val field: RequirementField, val outcome: Outcome, val mandatory: Boolean, val detail: String)

    data class Result(
        val eligible: Boolean,
        val score: Int,
        val checks: List<Check>,
        val violations: List<String>,
        val needsVerification: List<RequirementField>,
    )

    private val WEIGHTS = mapOf(
        RequirementField.BUDGET to 30, RequirementField.LOCATION to 30, RequirementField.POSSESSION to 15,
        RequirementField.FURNISHING to 10, RequirementField.PARKING to 10, RequirementField.FLOOR to 5,
        RequirementField.PROPERTY_TYPE to 10,
    )

    /** Same order as the server's checks. */
    private val ORDER = listOf(
        RequirementField.BUDGET, RequirementField.LOCATION, RequirementField.FURNISHING, RequirementField.PARKING,
        RequirementField.FLOOR, RequirementField.POSSESSION, RequirementField.PROPERTY_TYPE,
    )

    private fun inr(v: Long) = Money.full(v)

    private fun budget(r: Inquiry, p: Property): Pair<Outcome, String> {
        if (r.budgetMax == null && r.budgetMin == null) return Outcome.NA to "No budget specified"
        val max = r.budgetMax
        if (max != null && p.price > max) {
            return if (p.price * 100 <= max * 110) {
                Outcome.NEAR to "${inr(p.price)} is within 10% over budget ${inr(max)}"
            } else {
                Outcome.MISMATCH to "${inr(p.price)} exceeds budget ${inr(max)}"
            }
        }
        return Outcome.MATCH to "${inr(p.price)} is within budget"
    }

    private fun location(r: Inquiry, p: Property): Pair<Outcome, String> {
        if (r.locations.isEmpty()) return Outcome.NA to "No location preference"
        val hit = r.locations.firstOrNull { Locality.matches(it, p.locality) }
        return if (hit != null) {
            Outcome.MATCH to "${p.locality} matches preferred $hit"
        } else {
            Outcome.MISMATCH to "${p.locality} is not in ${r.locations.joinToString(", ")}"
        }
    }

    private fun furnishing(r: Inquiry, p: Property): Pair<Outcome, String> {
        if (r.furnishing.isEmpty()) return Outcome.NA to "No furnishing preference"
        val f = p.furnishing ?: return Outcome.UNKNOWN to "Listing furnishing not recorded"
        return if (f in r.furnishing) Outcome.MATCH to "${f.name} accepted"
        else Outcome.MISMATCH to "${f.name} not in ${r.furnishing.joinToString("/") { it.name }}"
    }

    private fun parking(r: Inquiry, p: Property): Pair<Outcome, String> {
        val need = r.minParking
        if (need == null || need <= 0) return Outcome.NA to "No parking requirement"
        val has = p.parkingSpots ?: return Outcome.UNKNOWN to "Listing parking not recorded"
        return if (has >= need) Outcome.MATCH to "$has parking spot(s)"
        else Outcome.MISMATCH to "Needs $need parking, listing has $has"
    }

    /**
     * Which third of the building a floor is in (ground and the bottom third are lower). Null when
     * the floor or the building's height isn't recorded, so the band is never guessed.
     */
    fun floorBandOf(floor: Int?, totalFloors: Int?): FloorBand? {
        if (floor == null || totalFloors == null || totalFloors <= 0 || floor > totalFloors) return null
        return when {
            floor * 3 <= totalFloors -> FloorBand.LOWER
            floor * 3 > totalFloors * 2 -> FloorBand.HIGHER
            else -> FloorBand.MIDDLE
        }
    }

    private fun FloorBand.word() = name.lowercase()

    private fun floor(r: Inquiry, p: Property): Pair<Outcome, String> {
        if (r.floorPreference.isEmpty()) return Outcome.NA to "No floor preference"
        val wanted = r.floorPreference.joinToString("/") { it.word() }
        val floor = p.floor ?: return Outcome.UNKNOWN to "Floor not recorded — unverified (client wants $wanted floors)"
        val band = floorBandOf(floor, p.totalFloors)
            ?: return Outcome.UNKNOWN to "Floor $floor, but the building's total floors aren't recorded — $wanted floor unverified"
        val where = "Floor $floor of ${p.totalFloors} is a ${band.word()} floor"
        return if (band in r.floorPreference) Outcome.MATCH to where else Outcome.MISMATCH to "$where; client wants $wanted"
    }

    private fun propertyType(r: Inquiry, p: Property): Pair<Outcome, String> {
        if (r.propertyTypes.isEmpty()) return Outcome.NA to "No property type preference"
        val t = p.propertyType ?: return Outcome.UNKNOWN to "Listing property type not recorded"
        return if (t in r.propertyTypes) Outcome.MATCH to "${t.label} accepted"
        else Outcome.MISMATCH to "${t.label} not in ${r.propertyTypes.joinToString("/") { it.label }}"
    }

    private fun possession(r: Inquiry, p: Property): Pair<Outcome, String> {
        if (r.possession == null && r.possessionBy == null) return Outcome.NA to "No possession preference"
        val has = p.possession ?: return Outcome.UNKNOWN to "Listing possession status not recorded"
        if (r.possession == Possession.UNDER_CONSTRUCTION) {
            return if (has == Possession.UNDER_CONSTRUCTION) Outcome.MATCH to "Under construction"
            else Outcome.MISMATCH to "Client wants under-construction, listing is ready"
        }
        if (has == Possession.READY_TO_MOVE) return Outcome.MATCH to "Ready to move"
        val by = r.possessionBy?.take(10)
            ?: return Outcome.MISMATCH to "Client wants ready-to-move, listing is under construction"
        val date = p.possessionDate?.take(10) ?: return Outcome.UNKNOWN to "Listing possession date not recorded"
        return if (date <= by) Outcome.MATCH to "Possession before $by" else Outcome.MISMATCH to "Possession $date is after $by"
    }

    private fun check(field: RequirementField, r: Inquiry, p: Property) = when (field) {
        RequirementField.BUDGET -> budget(r, p)
        RequirementField.LOCATION -> location(r, p)
        RequirementField.FURNISHING -> furnishing(r, p)
        RequirementField.PARKING -> parking(r, p)
        RequirementField.FLOOR -> floor(r, p)
        RequirementField.POSSESSION -> possession(r, p)
        RequirementField.PROPERTY_TYPE -> propertyType(r, p)
    }

    /**
     * Hard rules: same rent/buy, same BHK, listing available. A must-have that doesn't match
     * (or is only near, for budget) excludes the property; a must-have the listing hasn't recorded
     * keeps it, flagged for verification.
     */
    fun evaluate(r: Inquiry, p: Property): Result {
        val violations = mutableListOf<String>()
        if (p.transactionType != r.transactionType) violations += "Listing is for ${p.transactionType.name}"
        if (p.category != r.category) violations += "Listing is ${p.category.name}, client wants ${r.category.name}"
        if (p.availability != Availability.AVAILABLE) violations += "Listing is ${p.availability.name}"
        val checks = mutableListOf<Check>()
        val verify = mutableListOf<RequirementField>()
        var earned = 0.0
        var possible = 0
        for (field in ORDER) {
            val (outcome, detail) = check(field, r, p)
            val mandatory = field in r.mandatory
            checks += Check(field, outcome, mandatory, detail)
            if (outcome == Outcome.NA) continue
            if (mandatory && (outcome == Outcome.MISMATCH || outcome == Outcome.NEAR)) {
                violations += "Mandatory ${field.name.lowercase()}: $detail"
            }
            if (mandatory && outcome == Outcome.UNKNOWN) verify += field
            val w = WEIGHTS.getValue(field)
            possible += w
            earned += w * outcome.credit
        }
        val eligible = violations.isEmpty()
        // Math.round in JavaScript rounds halves up.
        val score = if (possible == 0) 100 else kotlin.math.floor(earned / possible * 100 + 0.5).toInt()
        return Result(eligible, if (eligible) score else 0, checks, violations, verify)
    }

    /** Eligible entries only, best score first, then cheapest. */
    fun <T> rank(items: List<T>, evaluate: (T) -> Result, priceOf: (T) -> Long): List<Pair<T, Result>> =
        items.map { it to evaluate(it) }
            .filter { it.second.eligible }
            .sortedWith(compareByDescending<Pair<T, Result>> { it.second.score }.thenBy { priceOf(it.first) })

}

/** Mumbai locality comparison, as on the server (backend/src/domain/locality.ts). */
object Locality {
    private val DIRECTION = mapOf("w" to "west", "west" to "west", "e" to "east", "east" to "east", "n" to "north", "north" to "north", "s" to "south", "south" to "south")
    private val ALIASES = mapOf(
        "bkc" to "bandra kurla complex", "vileparle" to "vile parle", "parle" to "vile parle", "ghatkoper" to "ghatkopar",
        "l parel" to "lower parel", "bombay" to "mumbai", "santa cruz" to "santacruz", "juhu scheme" to "juhu",
        "powai hiranandani" to "powai", "hiranandani powai" to "powai", "mira rd" to "mira road", "mira bhayander" to "mira road",
        "thane w" to "thane west", "worli sea face" to "worli",
    )

    fun normalize(raw: String): String {
        var s = raw.lowercase().replace(Regex("[()\\[\\],.]"), " ").replace(Regex("[-_/]"), " ").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return s
        s = s.replace(Regex("\\brd\\b"), "road")
        val parts = s.split(" ").toMutableList()
        if (parts.size > 1) DIRECTION[parts.last()]?.let { parts[parts.size - 1] = it }
        s = parts.joinToString(" ")
        return ALIASES[s] ?: s
    }

    private val DIRECTIONAL = Regex("\\b(west|east|north|south)$")

    /** "Andheri" covers Andheri East and West; "Andheri West" covers only Andheri West. */
    fun matches(preferred: String, propertyLocality: String): Boolean {
        val pref = normalize(preferred)
        val prop = normalize(propertyLocality)
        if (pref.isEmpty() || prop.isEmpty()) return false
        if (pref == prop) return true
        if (!DIRECTIONAL.containsMatchIn(pref) && DIRECTIONAL.containsMatchIn(prop)) {
            return prop.replace(Regex(" (west|east|north|south)$"), "") == pref
        }
        return false
    }
}

/** How a match is labelled in the app. */
object MatchLabel {
    /** Every stated preference is met (and verified): an exact match. Anything else is partial. */
    fun isExact(checks: List<com.brokerbuddy.core.model.FieldCheck>): Boolean =
        checks.all { it.outcome == "match" || it.outcome == "n/a" }

    fun of(score: Int, checks: List<com.brokerbuddy.core.model.FieldCheck>): String =
        if (isExact(checks)) "Exact match" else "Partial match · $score%"
}
