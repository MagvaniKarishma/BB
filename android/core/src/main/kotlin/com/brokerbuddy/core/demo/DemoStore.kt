package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.phone.PhoneNumbers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the broker changed in the demo, kept on the phone. The original sample data (bundled
 * with the app) is never modified: records created or edited in the demo live here and are
 * laid over the sample data on every read. Clearing this restores the original demo.
 */
@Serializable
class DemoChanges(
    val clients: MutableMap<String, JsonObject> = mutableMapOf(),
    val properties: MutableMap<String, JsonObject> = mutableMapOf(),
    val inquiries: MutableMap<String, JsonObject> = mutableMapOf(),
    val reminders: MutableMap<String, JsonObject> = mutableMapOf(),
    val notes: MutableMap<String, JsonObject> = mutableMapOf(),
    /** Portal enquiries whose status the broker changed. */
    val leads: MutableMap<String, JsonObject> = mutableMapOf(),
    /** Voice notes recorded or typed in the demo, and edits to sample ones. */
    val voiceNotes: MutableMap<String, JsonObject> = mutableMapOf(),
    /** Ids of records created in the demo (only these can be deleted). */
    val created: MutableSet<String> = mutableSetOf(),
    val deleted: MutableSet<String> = mutableSetOf(),
    var seq: Long = 0,
) {
    val count: Int get() = clients.size + properties.size + inquiries.size + reminders.size + notes.size + leads.size + voiceNotes.size + deleted.size

    fun clear() {
        clients.clear(); properties.clear(); inquiries.clear(); reminders.clear(); notes.clear(); leads.clear(); voiceNotes.clear()
        created.clear(); deleted.clear(); seq = 0
    }

    fun toJson(): String = ApiJson.encodeToString(serializer(), this)

    /** Makes this hold exactly what [other] holds (used to undo a change that couldn't be saved). */
    fun replaceWith(other: DemoChanges) {
        clear()
        clients += other.clients; properties += other.properties; inquiries += other.inquiries
        reminders += other.reminders; notes += other.notes; leads += other.leads; voiceNotes += other.voiceNotes
        created += other.created; deleted += other.deleted; seq = other.seq
    }

    companion object {
        fun fromJson(json: String?): DemoChanges = parse(json) ?: DemoChanges()

        /** Null when [json] is missing or can't be read (damaged file). */
        fun parse(json: String?): DemoChanges? = json?.let { runCatching { ApiJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

// ---------- small JSON helpers ----------

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonObject.plus(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(this + pairs.associate { (k, v) -> k to (v ?: JsonNull) })
internal fun JsonObject.merge(patch: JsonObject): JsonObject = JsonObject(this + patch)
internal fun jsonOf(vararg pairs: Pair<String, JsonElement?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to (v ?: JsonNull) })
internal fun s(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull
internal fun list(items: List<JsonElement>) = JsonArray(items)

/**
 * The writable part of the demo: creates and edits of clients, requirements, properties,
 * follow-ups and notes, answered the way the server would answer them, and merged into
 * what the screens read. Photos, voice notes, WhatsApp, portal imports and other
 * server-side features stay read-only in the demo.
 */
internal class DemoStore(
    private val changes: DemoChanges,
    private val base: (String) -> JsonElement?,
    private val me: JsonObject,
    private val brokerageId: String,
    private val now: () -> Instant,
    /** The phone's time zone, for "due today" (updated from each request's tz). */
    var zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) {
    private fun nowIso() = now().toString()
    private fun newId(kind: String): String {
        changes.seq += 1
        return "demo-$kind-${changes.seq}"
    }
    private fun meRef() = jsonOf("id" to me["id"], "name" to me["name"])
    fun meId(): String? = me.str("id")

    // ---------- merged reads ----------

    /** Every client list item (sample + created), with edits applied. */
    fun allClientItems(): List<JsonObject> {
        val sample = base("clients?group=all")?.jsonObject?.arr("clients").orEmpty()
        val sampleIds = sample.map { it.str("id") }.toSet()
        val items = sample.filter { it.str("id") !in changes.deleted }.map { item ->
            val id = item.str("id")!!
            changes.clients[id]?.let { edited -> item.merge(clientFields(edited)) } ?: item
        }
        val created = changes.clients.values
            .filter { it.str("id") !in sampleIds && it.str("id") !in changes.deleted }
            .sortedByDescending { it.str("createdAt") }
            .map { c -> c.plus("assignedTo" to meRef()) }
        return (created + items).map { withFollowUpAndRequirement(it) }
    }

    private fun clientFields(c: JsonObject) = JsonObject(c.filterKeys { it in CLIENT_FIELDS })

    private fun withFollowUpAndRequirement(item: JsonObject): JsonObject {
        val id = item.str("id")!!
        val pending = remindersAll().filter { it.str("clientId") == id && it.str("status") == "PENDING" }
        val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        val next = pending.mapNotNull { it.str("dueAt") }.minOrNull()
        val due = pending.any { r -> r.str("dueAt")?.let { runCatching { Instant.parse(it) < endOfToday }.getOrDefault(false) } == true }
        val active = inquiriesOf(id).filter { it.str("status") == "ACTIVE" }
        val latest = active.maxByOrNull { it.str("updatedAt") ?: "" }
        val touched = id in changes.created || changes.inquiries.values.any { it.str("clientId") == id }
        return item.plus(
            "nextFollowUpAt" to s(next),
            "followUpDue" to JsonPrimitive(due),
        ).let { i ->
            if (!touched) i else i.plus(
                "activeInquiries" to JsonPrimitive(active.size),
                "requirement" to latest?.let {
                    jsonOf(
                        "id" to it["id"], "transactionType" to it["transactionType"], "category" to it["category"],
                        "locations" to it["locations"], "budgetMin" to it["budgetMin"], "budgetMax" to it["budgetMax"],
                    )
                },
            )
        }
    }

    fun clientDetail(id: String): JsonObject? {
        if (id in changes.deleted) return null
        val sample = base("clients/$id")?.jsonObject?.obj("client")
        val edited = changes.clients[id]
        val client = when {
            sample != null && edited != null -> sample.merge(clientFields(edited)).let { c -> edited["phones"]?.let { c.plus("phones" to it) } ?: c }
            sample != null -> sample
            edited != null -> edited.plus("assignedTo" to meRef(), "portalLeads" to list(emptyList()))
            else -> return null
        }
        val inquiries = inquiriesOf(id).map { JsonObject(it.filterKeys { k -> k != "client" && k != "matchCount" }) }
            .sortedWith(compareBy<JsonObject>({ if (it.str("status") == "ACTIVE") 0 else 1 }).thenByDescending { it.str("updatedAt") })
        val reminders = remindersAll().filter { it.str("clientId") == id && it.str("status") == "PENDING" }
            .map { JsonObject(it.filterKeys { k -> k != "client" && k != "assignedTo" }) }
            .sortedBy { it.str("dueAt") }
        val leads = portalLeadsAll().filter { it.str("clientId") == id }
        return client.plus("inquiries" to list(inquiries), "reminders" to list(reminders), "portalLeads" to list(leads))
    }

    // ---------- voice notes ----------

    /** Sample voice notes (with edits) and the ones made in the demo, newest first. */
    fun voiceNotesAll(): List<JsonObject> {
        val byId = LinkedHashMap<String, JsonObject>()
        for (n in base("voice-notes")?.jsonObject?.arr("voiceNotes").orEmpty()) byId[n.str("id")!!] = n
        for ((id, n) in changes.voiceNotes) byId[id] = byId[id]?.merge(n) ?: n
        return byId.values.filter { it.str("clientId") !in changes.deleted }
            .map { it.plus("hasAudio" to JsonPrimitive(it.str("audioSize") != null || (it["hasAudio"] as? JsonPrimitive)?.content == "true")) }
            .sortedByDescending { it.str("createdAt") }
    }

    fun voiceNote(id: String): JsonObject? = voiceNotesAll().firstOrNull { it.str("id") == id }

    /**
     * A new note: typed ([transcript] given, read straight away) or recorded ([hasAudio]; without a
     * server there's no speech-to-text for a recording, so it waits for the broker to type what was said).
     */
    fun createVoiceNote(clientId: String, inquiryId: String?, language: String, transcript: String?, hasAudio: Boolean, durationMs: Long?, read: (String) -> JsonElement): Out {
        clientDetail(clientId) ?: return notFound("Client")
        if (inquiryId != null) {
            val i = inquiriesAll().firstOrNull { it.str("id") == inquiryId } ?: return notFound("Inquiry")
            if (i.str("clientId") != clientId) return badRequest("That inquiry belongs to a different client")
        }
        val id = newId("voicenote")
        val base = jsonOf(
            "id" to s(id), "clientId" to s(clientId), "inquiryId" to s(inquiryId), "language" to s(language),
            "hasAudio" to JsonPrimitive(hasAudio), "audioSize" to (if (hasAudio) JsonPrimitive(1) else JsonNull),
            "durationMs" to (durationMs?.let(::JsonPrimitive) ?: JsonNull), "createdAt" to s(nowIso()), "createdBy" to meRef(),
            "appliedVersion" to JsonNull,
        )
        changes.voiceNotes[id] = if (transcript != null) base.merge(transcribed(transcript, read)).plus("originalTranscript" to s(transcript))
        else base.plus(
            "status" to s("NEEDS_TRANSCRIPT"), "originalTranscript" to JsonNull, "transcript" to JsonNull, "extraction" to JsonNull,
            "error" to s("Speech-to-text needs a BrokerBuddy server, so it isn't available in the demo. Play the recording and type what was said."),
        )
        changes.created += id
        return Out.Ok(201, jsonOf("id" to s(id)))
    }

    private fun transcribed(transcript: String, read: (String) -> JsonElement): JsonObject {
        val extraction = read(transcript)
        return jsonOf(
            "status" to s("READY"), "transcript" to s(transcript), "transcriptSource" to s("manual"),
            "extraction" to extraction, "extractor" to ((extraction as? JsonObject)?.get("extractor") ?: JsonNull), "error" to JsonNull,
        )
    }

    private fun openNote(id: String): Pair<JsonObject?, Out.Err?> {
        val note = voiceNote(id) ?: return null to notFound("Voice note")
        return when (note.str("status")) {
            "APPLIED" -> null to Out.Err(409, "ALREADY_APPLIED", "This voice note was already saved to an inquiry")
            "DISCARDED" -> null to Out.Err(409, "DISCARDED", "This voice note was discarded")
            else -> note to null
        }
    }

    fun setTranscript(id: String, transcript: String?, read: (String) -> JsonElement): Out {
        val text = transcript?.trim().orEmpty()
        if (text.isEmpty()) return badRequest("Type what was said")
        val (note, err) = openNote(id)
        if (err != null) return err
        val patch = transcribed(text, read).let { if (note!!.str("originalTranscript") == null) it.plus("originalTranscript" to s(text)) else it }
        changes.voiceNotes[id] = (changes.voiceNotes[id] ?: JsonObject(emptyMap())).merge(patch)
        return Out.Ok(200, JsonNull)
    }

    fun discardVoiceNote(id: String): Out {
        val (_, err) = openNote(id)
        if (err != null) return err
        changes.voiceNotes[id] = (changes.voiceNotes[id] ?: JsonObject(emptyMap())).plus("status" to s("DISCARDED"))
        return Out.Ok(200, JsonNull)
    }

    /** Saves the reviewed requirement from a note: updates the chosen requirement or creates one. */
    fun applyVoiceNote(id: String, body: JsonObject): Out {
        val (note, err) = openNote(id)
        if (err != null) return err
        if (note!!.str("status") != "READY") return badRequest("Add a transcript before saving this note")
        val requirement = body.obj("requirement") ?: return badRequest("Missing requirement")
        val withSource = requirement.plus("source" to s("VOICE_NOTE"))
        val targetId = body.str("inquiryId")
        val out = if (targetId != null) {
            val target = inquiriesAll().firstOrNull { it.str("id") == targetId } ?: return notFound("Inquiry")
            if (target.str("clientId") != note.str("clientId")) return badRequest("That inquiry belongs to a different client")
            updateInquiry(targetId, withSource)
        } else {
            createInquiry(note.str("clientId")!!, withSource)
        }
        if (out !is Out.Ok) return out
        val inquiry = (out.body as JsonObject).obj("inquiry")!!
        changes.voiceNotes[id] = (changes.voiceNotes[id] ?: JsonObject(emptyMap())).plus(
            "status" to s("APPLIED"), "inquiryId" to inquiry["id"], "appliedVersion" to inquiry["version"],
        )
        return Out.Ok(200, jsonOf("inquiry" to inquiry, "changed" to JsonPrimitive(true), "voiceNote" to voiceNote(id)))
    }

    // ---------- portal leads (99acres / Housing.com) ----------

    /** The sample listing summaries of one portal: the listing's own facts (counts are recomputed). */
    fun portalListingFacts(portal: String): List<JsonObject> =
        base("portal-leads/listings?portal=$portal")?.jsonObject?.arr("listings").orEmpty()

    /** Every sample enquiry, newest first, with status changes and current client details applied. */
    fun portalLeadsAll(): List<JsonObject> {
        val byId = LinkedHashMap<String, JsonObject>()
        for (portal in PORTALS) {
            for (l in portalListingFacts(portal)) {
                for (lead in base("portal-leads/listings/${l.str("id")}?portal=$portal")?.jsonObject?.arr("leads").orEmpty()) {
                    byId.putIfAbsent(lead.str("id")!!, lead)
                }
            }
        }
        return byId.values.map { lead ->
            val edited = changes.leads[lead.str("id")]?.let { lead.merge(it) } ?: lead
            val clientId = edited.str("clientId")
            when {
                clientId == null -> edited
                clientId in changes.deleted -> edited.plus("clientId" to JsonNull, "client" to JsonNull)
                else -> edited.plus("client" to (clientRef(clientId) ?: edited["client"]))
            }
        }.sortedByDescending { it.str("enquiredAt") }
    }

    /**
     * Sets an enquiry's status, as the server does: marking it anything but New also moves a
     * client still marked New to Contacted; nothing else about the client changes.
     */
    fun setLeadStatus(id: String, body: JsonObject): Out {
        val status = body.str("status")
        if (status !in LEAD_STATUSES) return badRequest("Choose a valid status")
        val lead = portalLeadsAll().firstOrNull { it.str("id") == id } ?: return notFound("Lead")
        changes.leads[id] = jsonOf("status" to s(status), "updatedAt" to s(nowIso()))
        val clientId = lead.str("clientId")
        if (clientId != null && status != "NEW") {
            val current = changes.clients[clientId] ?: base("clients/$clientId")?.jsonObject?.obj("client")?.let(::clientFields)
            if (current != null && current.str("status") == "NEW") {
                changes.clients[clientId] = current.plus("status" to s("CONTACTED"), "updatedAt" to s(nowIso()))
            }
        }
        return Out.Ok(200, jsonOf("lead" to portalLeadsAll().first { it.str("id") == id }))
    }

    fun clientRef(id: String): JsonObject? {
        val c = changes.clients[id] ?: base("clients/$id")?.jsonObject?.obj("client")
            ?: allClientItems().firstOrNull { it.str("id") == id } ?: return null
        return jsonOf("id" to c["id"], "name" to c["name"], "primaryPhone" to c["primaryPhone"], "status" to c["status"])
    }

    /** Requirements of one client: the sample ones (with edits) plus ones created in the demo. */
    fun inquiriesOf(clientId: String): List<JsonObject> =
        inquiriesAll().filter { it.str("clientId") == clientId }

    fun inquiriesAll(): List<JsonObject> {
        val sample = base("inquiries")?.jsonObject?.arr("inquiries").orEmpty()
        // Sample requirements of clients the list doesn't cover (e.g. fulfilled ones) come from client details.
        val byId = LinkedHashMap<String, JsonObject>()
        for (i in sample) byId[i.str("id")!!] = i
        for (c in base("clients?group=all")?.jsonObject?.arr("clients").orEmpty()) {
            val cid = c.str("id") ?: continue
            for (i in base("clients/$cid")?.jsonObject?.obj("client")?.arr("inquiries").orEmpty()) byId.putIfAbsent(i.str("id")!!, i)
        }
        for ((id, i) in changes.inquiries) byId[id] = byId[id]?.merge(i) ?: i
        return byId.values.filter { it.str("id") !in changes.deleted && it.str("clientId") !in changes.deleted }
    }

    fun inquiryItem(i: JsonObject): JsonObject =
        i.plus("client" to (i.str("clientId")?.let(::clientRef)), "matchCount" to (i["matchCount"] ?: JsonPrimitive(0)))

    fun propertiesAll(): List<JsonObject> {
        val sample = base("properties")?.jsonObject?.arr("properties").orEmpty()
        val byId = LinkedHashMap<String, JsonObject>()
        changes.properties.values.filter { it.str("id") in changes.created }.sortedByDescending { it.str("createdAt") }
            .forEach { byId[it.str("id")!!] = it }
        for (p in sample) byId[p.str("id")!!] = changes.properties[p.str("id")]?.let { p.merge(it) } ?: p
        return byId.values.filter { it.str("id") !in changes.deleted }
    }

    fun remindersAll(): List<JsonObject> {
        val sample = base("reminders")?.jsonObject?.arr("reminders").orEmpty()
        val byId = LinkedHashMap<String, JsonObject>()
        for (r in sample) byId[r.str("id")!!] = r
        for ((id, r) in changes.reminders) byId[id] = byId[id]?.merge(r) ?: r
        return byId.values.filter { it.str("id") !in changes.deleted && it.str("clientId") !in changes.deleted }
            .sortedBy { it.str("dueAt") }
    }

    fun notesOf(clientId: String): List<JsonObject> {
        val sample = base("clients/$clientId/notes")?.jsonObject?.arr("notes").orEmpty()
        val mine = changes.notes.values.filter { it.str("clientId") == clientId }
        return (sample + mine).sortedByDescending { it.str("createdAt") }
    }

    // ---------- writes ----------

    sealed interface Out {
        data class Ok(val status: Int, val body: JsonElement) : Out
        data class Err(val status: Int, val code: String, val message: String, val details: JsonElement? = null) : Out
    }

    private fun badRequest(message: String) = Out.Err(400, "VALIDATION_ERROR", message)
    private fun notFound(what: String) = Out.Err(404, "NOT_FOUND", "$what not found")

    private fun existingClientWithPhone(e164: String, exceptId: String? = null): JsonObject? =
        allClientItems().firstOrNull { it.str("primaryPhone") == e164 && it.str("id") != exceptId }

    private fun existingClientWithEmail(email: String, exceptId: String? = null): JsonObject? =
        allClientItems().firstOrNull { it.str("email")?.equals(email, ignoreCase = true) == true && it.str("id") != exceptId }

    private fun duplicate(c: JsonObject, matchedOn: String) = Out.Err(
        409, "DUPLICATE_CLIENT", "A client with this $matchedOn already exists: ${c.str("name")}",
        jsonOf("existingClient" to jsonOf("id" to c["id"], "name" to c["name"], "primaryPhone" to c["primaryPhone"]), "matchedOn" to s(matchedOn)),
    )

    private val emailRe = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** A cleaned email: lower case; "" / null → null; Err when it isn't an email. */
    private fun email(raw: JsonElement?): Pair<String?, Out.Err?> {
        val e = (raw as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null to null
        return if (emailRe.matches(e)) e to null else null to badRequest("\"$e\" is not a valid email")
    }

    /** "Is this number already a client?" — the live check on the New client form. */
    fun checkDuplicate(raw: String): JsonObject {
        val e164 = PhoneNumbers.normalize(raw)
        val c = e164?.let { existingClientWithPhone(it) }
        return jsonOf(
            "normalized" to s(e164 ?: raw),
            "duplicate" to c?.let { jsonOf("id" to it["id"], "name" to it["name"], "primaryPhone" to it["primaryPhone"]) },
        )
    }

    /** Client search like the server's: name, email, phone digits, or an active requirement's area / type. */
    fun clientMatches(item: JsonObject, q: String): Boolean {
        val text = q.trim().lowercase()
        if (text.isEmpty()) return true
        if (item.str("name")?.lowercase()?.contains(text) == true) return true
        if (item.str("email")?.lowercase()?.contains(text) == true) return true
        val digits = text.filter(Char::isDigit)
        if (digits.length >= 3 && item.str("primaryPhone")?.contains(digits) == true) return true
        val bhk = Regex("\\b(\\d)\\s?bhk\\b").find(text)
        val category = when {
            bhk != null -> if (bhk.groupValues[1].toInt() >= 5) "BHK_5_PLUS" else "BHK_${bhk.groupValues[1]}"
            Regex("\\b(studio|1\\s?rk)\\b").containsMatchIn(text) -> "STUDIO"
            Regex("\\b(commercial|shop|office)\\b").containsMatchIn(text) -> "COMMERCIAL"
            else -> null
        }
        val term = text.replace(Regex("\\b\\d\\s?bhk\\b|\\b(studio|1\\s?rk)\\b"), "").trim()
        if (category == null && term.isEmpty()) return false
        return inquiriesOf(item.str("id")!!).any { i ->
            i.str("status") == "ACTIVE" &&
                (category == null || i.str("category") == category) &&
                (term.isEmpty() || i["locations"].let { l -> (l as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.contains(term) == true } == true })
        }
    }

    fun createClient(body: JsonObject): Out {
        val name = body.str("name")?.trim().orEmpty()
        val raw = body.str("phone")?.trim().orEmpty()
        if (name.isEmpty() || raw.isEmpty()) return badRequest("Name and phone are required")
        val phone = PhoneNumbers.normalize(raw) ?: return Out.Err(400, "INVALID_PHONE", "\"$raw\" is not a valid phone number")
        existingClientWithPhone(phone)?.let { return duplicate(it, "phone number") }
        val (mail, bad) = email(body["email"])
        bad?.let { return it }
        mail?.let { m -> existingClientWithEmail(m)?.let { return duplicate(it, "email") } }
        val id = newId("client")
        val t = nowIso()
        val client = jsonOf(
            "id" to s(id), "brokerageId" to s(brokerageId), "name" to s(name), "primaryPhone" to s(phone),
            "email" to s(mail), "leadSource" to (body["leadSource"] ?: s("OTHER")), "status" to (body["status"] ?: s("NEW")),
            "notes" to body["notes"], "assignedToId" to me["id"], "createdAt" to s(t), "updatedAt" to s(t),
            "phones" to list(listOf(jsonOf("id" to s("$id-phone"), "brokerageId" to s(brokerageId), "clientId" to s(id), "e164" to s(phone), "label" to s("primary")))),
        )
        changes.clients[id] = client
        changes.created += id
        return Out.Ok(201, jsonOf("client" to client))
    }

    fun updateClient(id: String, body: JsonObject): Out {
        val current = clientDetail(id) ?: return notFound("Client")
        var patch = JsonObject(body.filterKeys { it in setOf("name", "email", "leadSource", "status", "notes") })
        if (patch.str("name")?.isBlank() == true) return badRequest("Name can't be empty")
        if (body.containsKey("email")) {
            val (mail, bad) = email(body["email"])
            bad?.let { return it }
            mail?.let { m -> existingClientWithEmail(m, exceptId = id)?.let { return duplicate(it, "email") } }
            patch = patch.plus("email" to s(mail))
        }
        // A new main number replaces the old one (checked against other clients).
        body.str("phone")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val e164 = PhoneNumbers.normalize(raw) ?: return Out.Err(400, "INVALID_PHONE", "\"$raw\" is not a valid phone number")
            if (e164 != current.str("primaryPhone")) {
                existingClientWithPhone(e164, exceptId = id)?.let { return duplicate(it, "phone number") }
                patch = patch.plus(
                    "primaryPhone" to s(e164),
                    "phones" to list(listOf(jsonOf("id" to s("$id-phone"), "brokerageId" to s(brokerageId), "clientId" to s(id), "e164" to s(e164), "label" to s("primary")))),
                )
            }
        }
        val updated = JsonObject(clientFields(current) + patch).plus("updatedAt" to s(nowIso()))
        changes.clients[id] = (changes.clients[id] ?: JsonObject(emptyMap())).merge(updated).let { u ->
            patch["phones"]?.let { u.plus("phones" to it) } ?: u
        }
        return Out.Ok(200, jsonOf("client" to JsonObject(updated.filterKeys { it in CLIENT_FIELDS })))
    }

    fun deleteClient(id: String): Out {
        if (id !in changes.created) return Out.Err(403, "DEMO_SAMPLE", SAMPLE_NOT_DELETABLE)
        changes.deleted += id
        return Out.Ok(204, JsonNull)
    }

    private val requirementKeys = setOf(
        "transactionType", "category", "status", "budgetMin", "budgetMax", "locations", "furnishing", "minParking",
        "floorPreference", "propertyTypes", "possession", "possessionBy", "mandatory", "notes", "source",
    )

    fun createInquiry(clientId: String, body: JsonObject): Out {
        clientDetail(clientId) ?: return notFound("Client")
        if (body.str("transactionType") == null || body.str("category") == null) return badRequest("Rent/Buy and property type are required")
        val id = newId("requirement")
        val t = nowIso()
        val inquiry = JsonObject(body.filterKeys { it in requirementKeys }).plus(
            "id" to s(id), "brokerageId" to s(brokerageId), "clientId" to s(clientId),
            "status" to (body["status"] ?: s("ACTIVE")), "source" to (body["source"] ?: s("MANUAL")),
            "version" to JsonPrimitive(1), "createdAt" to s(t), "updatedAt" to s(t),
        )
        changes.inquiries[id] = inquiry
        changes.created += id
        return Out.Ok(201, jsonOf("inquiry" to inquiry))
    }

    fun updateInquiry(id: String, body: JsonObject): Out {
        val current = inquiriesAll().firstOrNull { it.str("id") == id } ?: return notFound("Requirement")
        val version = (current["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        val updated = JsonObject(current.filterKeys { it != "client" && it != "matchCount" })
            .merge(JsonObject(body.filterKeys { it in requirementKeys }))
            .plus("version" to JsonPrimitive(version + 1), "updatedAt" to s(nowIso()))
        changes.inquiries[id] = updated
        return Out.Ok(200, jsonOf("inquiry" to updated, "changed" to JsonPrimitive(true)))
    }

    private val propertyKeys = setOf(
        "title", "transactionType", "category", "propertyType", "price", "deposit", "locality", "building", "address",
        "carpetAreaSqft", "builtUpAreaSqft", "bathrooms", "furnishing", "parkingSpots", "floor", "totalFloors", "possession",
        "possessionDate", "availability", "amenities", "ownerName", "ownerPhone", "notes",
    )

    fun createProperty(body: JsonObject): Out {
        if (body.str("title").isNullOrBlank() || body.str("locality").isNullOrBlank() || body.str("price") == null) {
            return badRequest("Title, area and price are required")
        }
        val id = newId("property")
        val t = nowIso()
        val property = JsonObject(body.filterKeys { it in propertyKeys }).plus(
            "id" to s(id), "brokerageId" to s(brokerageId), "availability" to (body["availability"] ?: s("AVAILABLE")),
            "amenities" to (body["amenities"] ?: list(emptyList())),
            "listedById" to me["id"], "createdAt" to s(t), "updatedAt" to s(t), "photoIds" to list(emptyList()),
        )
        changes.properties[id] = property
        changes.created += id
        return Out.Ok(201, jsonOf("property" to property))
    }

    fun updateProperty(id: String, body: JsonObject): Out {
        val current = propertiesAll().firstOrNull { it.str("id") == id } ?: return notFound("Property")
        val updated = current.merge(JsonObject(body.filterKeys { it in propertyKeys })).plus("updatedAt" to s(nowIso()))
        changes.properties[id] = updated
        return Out.Ok(200, jsonOf("property" to updated))
    }

    /** Records a photo the app saved on the phone; the first photo is the cover. */
    fun addPhoto(propertyId: String, photoId: String): Out {
        val current = propertiesAll().firstOrNull { it.str("id") == propertyId } ?: return notFound("Property")
        val ids = photoIdsOf(current)
        if (ids.size >= MAX_PHOTOS) return badRequest("A property can have at most $MAX_PHOTOS photos")
        val updated = current.plus("photoIds" to list((ids + photoId).map(::s)), "updatedAt" to s(nowIso()))
        changes.properties[propertyId] = updated
        return Out.Ok(201, jsonOf("photoId" to s(photoId), "property" to updated))
    }

    fun removePhoto(propertyId: String, photoId: String): Out {
        val current = propertiesAll().firstOrNull { it.str("id") == propertyId } ?: return notFound("Property")
        val ids = photoIdsOf(current)
        if (photoId !in ids) return notFound("Photo")
        changes.properties[propertyId] = current.plus("photoIds" to list((ids - photoId).map(::s)), "updatedAt" to s(nowIso()))
        return Out.Ok(204, JsonNull)
    }

    fun photoIdsOf(property: JsonObject): List<String> =
        (property["photoIds"] as? JsonArray)?.mapNotNull { it.primitiveOrNull()?.contentOrNull }.orEmpty()

    fun deleteProperty(id: String): Out {
        if (id !in changes.created) return Out.Err(403, "DEMO_SAMPLE", SAMPLE_NOT_DELETABLE)
        changes.deleted += id
        return Out.Ok(204, JsonNull)
    }

    fun createReminder(body: JsonObject): Out {
        val title = body.str("title")?.trim().orEmpty()
        val due = body.str("dueAt")
        if (title.isEmpty() || due == null || runCatching { Instant.parse(due) }.isFailure) return badRequest("Title and a valid due time are required")
        val clientId = body.str("clientId")
        val client = clientId?.let { clientRef(it) ?: return notFound("Client") }
        val id = newId("followup")
        val reminder = jsonOf(
            "id" to s(id), "brokerageId" to s(brokerageId), "clientId" to s(clientId), "inquiryId" to body["inquiryId"],
            "assignedToId" to me["id"], "createdById" to me["id"], "dueAt" to s(due), "title" to s(title), "note" to body["note"],
            "kind" to (body["kind"]?.takeIf { it != JsonNull } ?: s("FOLLOW_UP")), "status" to s("PENDING"), "completedAt" to JsonNull,
            "createdAt" to s(nowIso()),
            "client" to client?.let { jsonOf("id" to it["id"], "name" to it["name"], "primaryPhone" to it["primaryPhone"]) },
            "assignedTo" to meRef(),
        )
        changes.reminders[id] = reminder
        changes.created += id
        return Out.Ok(201, jsonOf("reminder" to reminder))
    }

    fun updateReminder(id: String, body: JsonObject): Out {
        val current = remindersAll().firstOrNull { it.str("id") == id } ?: return notFound("Reminder")
        var updated = current
        body.str("title")?.let { updated = updated.plus("title" to s(it)) }
        body.str("dueAt")?.let { if (runCatching { Instant.parse(it) }.isSuccess) updated = updated.plus("dueAt" to s(it)) }
        if (body.containsKey("note")) updated = updated.plus("note" to body["note"])
        body.str("status")?.let { st ->
            updated = updated.plus("status" to s(st), "completedAt" to if (st == "DONE") s(nowIso()) else JsonNull)
        }
        changes.reminders[id] = updated
        return Out.Ok(200, jsonOf("reminder" to updated))
    }

    fun addNote(clientId: String, body: JsonObject): Out {
        clientDetail(clientId) ?: return notFound("Client")
        val text = body.str("body")?.trim().orEmpty()
        if (text.isEmpty()) return badRequest("Note can't be empty")
        val id = newId("note")
        val note = jsonOf(
            "id" to s(id), "brokerageId" to s(brokerageId), "clientId" to s(clientId), "authorId" to me["id"], "body" to s(text),
            "source" to (body["source"] ?: s("MANUAL")), "createdAt" to s(nowIso()), "author" to meRef(),
        )
        changes.notes[id] = note
        changes.created += id
        return Out.Ok(201, jsonOf("note" to note))
    }

    companion object {
        val CLIENT_FIELDS = setOf("id", "brokerageId", "name", "primaryPhone", "email", "leadSource", "status", "notes", "assignedToId", "createdAt", "updatedAt")
        const val MAX_PHOTOS = 12
        val PORTALS = listOf("ACRES_99", "HOUSING_COM")
        private val LEAD_STATUSES = com.brokerbuddy.core.model.PortalLeadStatus.entries.map { it.name }.toSet()
        const val SAMPLE_NOT_DELETABLE = "Sample records can't be deleted in the demo. Use Reset demo to undo your own changes."

        fun parseBody(body: String?): JsonObject? = body?.let { runCatching { ApiJson.parseToJsonElement(it).jsonObject }.getOrNull() }

        fun arrayOf(o: JsonElement?, key: String): List<JsonObject> = (o as? JsonObject)?.get(key)?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
    }
}

internal fun JsonElement.primitiveOrNull() = (this as? JsonPrimitive)?.jsonPrimitive
