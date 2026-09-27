package com.brokerbuddy.core.demo

import com.brokerbuddy.core.match.Matching
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.Extraction
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.MeResponse
import com.brokerbuddy.core.model.Property
import com.brokerbuddy.core.model.User
import com.brokerbuddy.core.voice.PhoneRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The offline demo ("Try the demo"): answers the app's API requests on the phone.
 *
 * - Reads come from a snapshot of real server responses (built by backend/scripts/demo-snapshot.ts),
 *   with dates moved forward so today's follow-ups are still today's.
 * - Clients, requirements, properties, follow-ups and notes can be created and edited: those
 *   changes are kept in [changes] (saved on the phone by the app) and merged into every read.
 *   The bundled sample data itself is never modified; clearing [changes] restores it.
 * - Sample records can't be deleted; features that need a real server (photos, voice notes,
 *   WhatsApp, portal imports…) say so instead of pretending to work.
 * - Nothing here ever goes to the network.
 */
class DemoApi(
    snapshotJson: String,
    today: LocalDate,
    val changes: DemoChanges = DemoChanges(),
    private val now: () -> Instant = Instant::now,
    private val onChange: (DemoChanges) -> Unit = {},
) {
    data class Reply(val status: Int, val body: String)

    private val root = ApiJson.parseToJsonElement(snapshotJson).jsonObject
    private val responses = root.getValue("responses").jsonObject
    private val shiftDays = ChronoUnit.DAYS.between(LocalDate.parse(root.getValue("capturedOn").jsonPrimitive.content), today)
    private val shifted = HashMap<String, JsonElement?>()

    /** A snapshot answer with its dates moved to today. */
    private fun base(key: String): JsonElement? = shifted.getOrPut(key) {
        responses[key]?.let { ApiJson.parseToJsonElement(shiftDates(it.toString(), shiftDays)) }
    }

    private val meResponse = responses["auth/me"] as? JsonObject
    private val store by lazy {
        DemoStore(
            changes, ::base,
            me = meResponse?.obj("user") ?: jsonOf("id" to s("demo-user"), "name" to s("Demo broker")),
            brokerageId = meResponse?.obj("brokerage")?.str("id") ?: "demo",
            now = now,
        )
    }

    /** The demo broker's account, for signing in. */
    fun user(): User = ApiJson.decodeFromJsonElement(MeResponse.serializer(), responses.getValue("auth/me")).user

    /** Forgets every change made in the demo (the sample data comes back). */
    @Synchronized
    fun reset() {
        changes.clear()
        onChange(changes)
    }

    /**
     * A photo the app has saved on the phone for [propertyId] (the app keeps the image file;
     * this records it on the property). Errors (unknown property, too many photos) come back as
     * the server would answer them, and the caller then discards the file.
     */
    @Synchronized
    fun addPhoto(propertyId: String, photoId: String): Reply = when (val out = store.addPhoto(propertyId, photoId)) {
        is DemoStore.Out.Err -> error(out.status, out.code, out.message, out.details)
        is DemoStore.Out.Ok -> {
            onChange(changes)
            Reply(out.status, out.body.toString())
        }
    }

    /** Is [photoId] one of [propertyId]'s photos? */
    @Synchronized
    fun hasPhoto(propertyId: String, photoId: String): Boolean =
        store.propertiesAll().firstOrNull { it.str("id") == propertyId }?.let { photoId in store.photoIdsOf(it) } == true

    /** Every photo id still in use (files for any others can be deleted). */
    @Synchronized
    fun photoIdsInUse(): Set<String> = store.propertiesAll().flatMap(store::photoIdsOf).toSet()

    /** [path] is the request's URL path (anything before "api/v1/" is ignored); [body] the JSON body of writes. */
    @Synchronized
    fun handle(method: String, path: String, params: List<Pair<String, String?>>, body: String? = null): Reply {
        val p = path.substringAfter("api/v1/").trim('/')
        val seg = p.split('/')
        val q = params.firstOrNull { it.first == "q" }?.second?.trim().orEmpty()
        fun param(name: String) = params.firstOrNull { it.first == name }?.second?.takeIf { it.isNotEmpty() }

        // "Today" is the phone's today: screens that care send their UTC offset (minutes).
        param("tz")?.toIntOrNull()?.takeIf { it in -720..840 }?.let { store.zone = java.time.ZoneOffset.ofTotalSeconds(it * 60) }
        if (method != "GET") return write(method, p, seg, DemoStore.parseBody(body))

        val merged: JsonElement? = when {
            p == "clients" -> return ok(clientList(param("group"), q))
            p == "clients/check-duplicate" -> store.checkDuplicate(param("phone").orEmpty())
            seg.size == 2 && seg[0] == "clients" && seg[1] !in setOf("lookup", "check-duplicate") ->
                store.clientDetail(seg[1])?.let { jsonOf("client" to it) }
            seg.size == 3 && seg[0] == "clients" && seg[2] == "notes" -> jsonOf("notes" to list(store.notesOf(seg[1])))
            p == "properties" -> return ok(propertyList(::param, q))
            seg.size == 2 && seg[0] == "properties" -> store.propertiesAll().firstOrNull { it.str("id") == seg[1] }?.let { jsonOf("property" to it) }
            seg.size == 3 && seg[0] == "properties" && seg[2] == "matches" -> propertyMatches(seg[1])
            p == "inquiries" -> inquiryList(param("transactionType"), param("category"), param("status"))
            seg.size == 2 && seg[0] == "inquiries" ->
                store.inquiriesAll().firstOrNull { it.str("id") == seg[1] }?.let { jsonOf("inquiry" to store.inquiryItem(it).let { i -> JsonObject(i - "matchCount") }) }
            seg.size == 3 && seg[0] == "inquiries" && seg[2] == "history" && seg[1] in changes.created -> jsonOf("revisions" to list(emptyList()))
            seg.size == 3 && seg[0] == "inquiries" && seg[2] == "matches" -> inquiryMatches(seg[1])
            p == "reminders" -> reminderList(param("status"), param("kind"), param("clientId"))
            p == "portal-leads/listings" -> param("portal")?.let { portalListings(it, range(::param)) }
            seg.size == 3 && seg[0] == "portal-leads" && seg[1] == "listings" -> portalListing(seg[2], param("portal"), range(::param))
            p == "portal-leads" -> {
                val (from, to) = range(::param)
                jsonOf("leads" to list(store.portalLeadsAll().filter {
                    (param("portal") == null || it.str("portal") == param("portal")) &&
                        (param("clientId") == null || it.str("clientId") == param("clientId")) && inRange(it, from, to)
                }))
            }
            p == "voice-notes" -> jsonOf("voiceNotes" to list(store.voiceNotesAll().filter {
                (param("clientId") == null || it.str("clientId") == param("clientId")) && (param("inquiryId") == null || it.str("inquiryId") == param("inquiryId"))
            }))
            seg.size == 2 && seg[0] == "voice-notes" -> presentNote(seg[1])
            p == "caller/directory" -> callerDirectory()
            p == "caller/lookup" -> callerLookup(param("phone").orEmpty())
            p == "dashboard" -> dashboard(param("tz")?.toIntOrNull() ?: IST_MINUTES)
            else -> null
        }
        if (merged != null) return ok(if (q.isEmpty()) merged else narrow(merged, q))

        base(key(p, params))?.let { return ok(it) }
        // Searches: the unfiltered list, narrowed to entries that mention the search text.
        base(key(p, params.filter { it.first != "q" }))?.let { return ok(if (q.isEmpty()) it else narrow(it, q)) }
        return error(404, "NOT_FOUND", "This isn't available in the demo.")
    }

    // ---------- merged lists ----------

    private fun clientList(group: String?, q: String): JsonElement {
        val all = store.allClientItems().filter { store.clientMatches(it, q) }
        fun inGroup(c: JsonObject, g: String?) = when (g) {
            null, "all" -> true
            "new" -> c.str("status") == "NEW"
            "active" -> c.str("status") in setOf("CONTACTED", "SITE_VISIT", "NEGOTIATION")
            "lost" -> c.str("status") == "CLOSED_LOST"
            "followup" -> (c["followUpDue"] as? JsonPrimitive)?.content == "true"
            else -> true
        }
        val shown = all.filter { inGroup(it, group) }
        val counts = listOf("all", "new", "active", "followup", "lost").associateWith { g -> JsonPrimitive(all.count { inGroup(it, g) }) }
        return jsonOf(
            "total" to JsonPrimitive(shown.size), "page" to JsonPrimitive(1), "pageSize" to JsonPrimitive(maxOf(30, shown.size)),
            "groupCounts" to JsonObject(counts), "clients" to list(shown),
        )
    }

    /** Same filters as the server's GET /properties. */
    private fun propertyList(param: (String) -> String?, q: String): JsonElement {
        fun eq(p: JsonObject, key: String) = param(key).let { it == null || p.str(key) == it }
        val locality = param("locality")?.trim()?.lowercase()
        val minPrice = param("minPrice")?.toLongOrNull()
        val maxPrice = param("maxPrice")?.toLongOrNull()
        val text = q.lowercase()
        val shown = store.propertiesAll().filter { p ->
            val price = p.str("price")?.toLongOrNull()
            eq(p, "transactionType") && eq(p, "category") && eq(p, "availability") && eq(p, "propertyType") && eq(p, "furnishing") &&
                (locality.isNullOrEmpty() || p.str("locality").orEmpty().lowercase().contains(locality)) &&
                (minPrice == null || (price != null && price >= minPrice)) &&
                (maxPrice == null || (price != null && price <= maxPrice)) &&
                (text.isEmpty() || listOf("title", "locality", "building").any { p.str(it).orEmpty().lowercase().contains(text) })
        }.sortedByDescending { it.str("updatedAt") }
        return jsonOf("total" to JsonPrimitive(shown.size), "page" to JsonPrimitive(1), "pageSize" to JsonPrimitive(maxOf(30, shown.size)), "properties" to list(shown))
    }

    private fun inquiryList(type: String?, category: String?, status: String?): JsonElement {
        val properties = typedProperties()
        val shown = store.inquiriesAll().filter {
            (type == null || it.str("transactionType") == type) && (category == null || it.str("category") == category) &&
                (status == null || it.str("status") == status) &&
                // The list shows active requirements (as the server does) unless a status is asked for.
                (status != null || it.str("status") == "ACTIVE")
        }.sortedByDescending { it.str("updatedAt") }.map { i ->
            val count = typed(i, Inquiry.serializer())?.let { r ->
                properties.count { (_, p) -> p.availability.name == "AVAILABLE" && Matching.evaluate(r, p).eligible }
            } ?: 0
            store.inquiryItem(i).plus("matchCount" to JsonPrimitive(count))
        }
        return jsonOf("total" to JsonPrimitive(shown.size), "inquiries" to list(shown))
    }

    // ---------- caller screen: who is calling (demo clients included) ----------

    private fun phonesOf(clientId: String, primary: String?): List<String> =
        (store.clientDetail(clientId)?.arr("phones").orEmpty().mapNotNull { it.str("e164") } + listOfNotNull(primary)).distinct()

    private fun callerDirectory(): JsonElement = jsonOf(
        "generatedAt" to s(now().toString()),
        "entries" to list(store.allClientItems().flatMap { c ->
            val id = c.str("id")!!
            phonesOf(id, c.str("primaryPhone")).map { e164 -> jsonOf("e164" to s(e164), "clientId" to s(id), "name" to c["name"]) }
        }),
    )

    private fun callerLookup(raw: String): JsonElement {
        val number = com.brokerbuddy.core.phone.PhoneNumbers.normalize(raw) ?: return jsonOf("number" to JsonNull, "client" to JsonNull)
        val item = store.allClientItems().firstOrNull { number in phonesOf(it.str("id")!!, it.str("primaryPhone")) }
            ?: return jsonOf("number" to s(number), "client" to JsonNull)
        val id = item.str("id")!!
        val detail = store.clientDetail(id) ?: item
        val properties = typedProperties().filter { it.second.availability.name == "AVAILABLE" }
        val all = store.inquiriesOf(id).map { JsonObject(it - "client" - "matchCount") }.sortedByDescending { it.str("updatedAt") }
        val open = all.filter { it.str("status") == "ACTIVE" || it.str("status") == "PAUSED" }.map { i ->
            val count = if (i.str("status") != "ACTIVE") null else typed(i, Inquiry.serializer())?.let { r -> properties.count { (_, p) -> Matching.evaluate(r, p).eligible } }
            i.plus("matchCount" to (count?.let(::JsonPrimitive) ?: JsonNull))
        }
        val reminders = store.remindersAll().filter { it.str("clientId") == id && it.str("status") == "PENDING" }.sortedBy { it.str("dueAt") }.take(5)
        val note = store.notesOf(id).maxByOrNull { it.str("createdAt") ?: "" }
        val voice = store.voiceNotesAll().filter { it.str("clientId") == id && it.str("transcript") != null && it.str("status") != "DISCARDED" }
            .maxByOrNull { it.str("createdAt") ?: "" }
        val last = listOfNotNull(
            note?.let { jsonOf("kind" to s("NOTE"), "text" to it["body"], "at" to it["createdAt"], "by" to (it.obj("author")?.get("name") ?: JsonNull)) },
            voice?.let { jsonOf("kind" to s("VOICE_NOTE"), "text" to it["transcript"], "at" to it["createdAt"], "by" to (it.obj("createdBy")?.get("name") ?: JsonNull)) },
        ).maxByOrNull { it.str("at") ?: "" }
        return jsonOf(
            "number" to s(number),
            "client" to jsonOf(
                "id" to s(id), "name" to detail["name"], "primaryPhone" to detail["primaryPhone"], "status" to detail["status"],
                "leadSource" to detail["leadSource"], "assignedTo" to (detail["assignedTo"] ?: JsonNull),
                "inquiries" to list(open), "closedInquiries" to JsonPrimitive(all.size - open.size),
                "reminders" to list(reminders), "lastInteraction" to last,
            ),
        )
    }

    // ---------- voice notes: typed or recorded, read on the phone ----------

    private fun read(text: String): JsonElement = ApiJson.encodeToJsonElement(Extraction.serializer(), PhoneRules.extract(text))

    /**
     * Voice fill: the original words decide what they can; the phone's English translation (Hindi or
     * Marathi speech) fills what they missed. Area names only in English.
     */
    private fun readForForm(text: String, english: String?): Extraction {
        val original = PhoneRules.extract(text)
        if (english.isNullOrBlank()) return original
        val translated = PhoneRules.extract(english)
        val o = original.draft
        val t = translated.draft
        val locations = (o.locations.orEmpty() + t.locations.orEmpty()).filter { !DEVANAGARI.containsMatchIn(it.value) }.distinctBy { it.value.lowercase() }
        return Extraction(
            draft = com.brokerbuddy.core.model.RequirementDraft(
                transactionType = o.transactionType ?: t.transactionType, category = o.category ?: t.category,
                budgetMin = o.budgetMin ?: t.budgetMin, budgetMax = o.budgetMax ?: t.budgetMax, locations = locations.ifEmpty { null },
                furnishing = o.furnishing ?: t.furnishing, minParking = o.minParking ?: t.minParking,
                floorPreference = o.floorPreference ?: t.floorPreference, possession = o.possession ?: t.possession,
                possessionBy = o.possessionBy ?: t.possessionBy,
            ),
            warnings = (original.warnings + translated.warnings).distinct(),
            extractor = PhoneRules.NAME,
        )
    }

    /** A note as the review screen reads it: which requirement it most likely belongs to, and warnings. */
    private fun presentNote(id: String): JsonElement? {
        val note = store.voiceNote(id) ?: return null
        val clientId = note.str("clientId") ?: return null
        val inquiries = store.inquiriesOf(clientId).map { JsonObject(it - "client" - "matchCount") }
            .sortedWith(compareBy<JsonObject>({ it.str("status") }).thenByDescending { it.str("updatedAt") })
        val draft = (note["extraction"] as? JsonObject)?.obj("draft")
        fun valueOf(key: String) = draft?.obj(key)?.str("value")
        val warnings = mutableListOf<String>()
        var suggested = note.str("inquiryId")
        if (draft != null && suggested != null) {
            val chosen = inquiries.firstOrNull { it.str("id") == suggested }
            if (chosen != null && valueOf("transactionType") != null && valueOf("transactionType") != chosen.str("transactionType")) {
                warnings += "The note talks about ${valueOf("transactionType")} but the selected inquiry is ${chosen.str("transactionType")}"
            }
            if (chosen != null && valueOf("category") != null && valueOf("category") != chosen.str("category")) {
                warnings += "The note mentions ${valueOf("category")} but the selected inquiry is ${chosen.str("category")}"
            }
        } else if (draft != null && note.str("status") == "READY") {
            val fits = inquiries.filter {
                it.str("status") == "ACTIVE" && (valueOf("transactionType") == null || it.str("transactionType") == valueOf("transactionType")) &&
                    (valueOf("category") == null || it.str("category") == valueOf("category"))
            }
            // Only when the note pins it down.
            if (fits.size == 1 && (valueOf("transactionType") != null || valueOf("category") != null)) suggested = fits.first().str("id")
        }
        return jsonOf("voiceNote" to note, "suggestedInquiryId" to s(suggested), "targetWarnings" to list(warnings.map(::s)), "inquiries" to list(inquiries))
    }

    /** A recording the app has saved on the phone (it keeps the audio file). */
    @Synchronized
    fun addRecording(clientId: String, inquiryId: String?, language: String, durationMs: Long?): Pair<Reply, String?> =
        when (val out = store.createVoiceNote(clientId, inquiryId, language, null, true, durationMs, ::read)) {
            is DemoStore.Out.Err -> error(out.status, out.code, out.message, out.details) to null
            is DemoStore.Out.Ok -> {
                onChange(changes)
                val id = (out.body as JsonObject).str("id")!!
                Reply(201, presentNote(id).toString()) to id
            }
        }

    /** Is this a note (with a recording) the broker can see? */
    @Synchronized
    fun hasRecording(noteId: String): Boolean = store.voiceNote(noteId)?.let { (it["hasAudio"] as? JsonPrimitive)?.content == "true" } == true

    /** Ids of every note, so recordings of deleted notes can be removed. */
    @Synchronized
    fun voiceNoteIds(): Set<String> = store.voiceNotesAll().mapNotNull { it.str("id") }.toSet()

    private fun voiceWrite(method: String, p: String, seg: List<String>, b: JsonObject): Reply {
        if (method == "POST" && p == "voice-notes/extract") {
            val text = b.str("text")?.trim().orEmpty()
            if (text.isEmpty()) return error(400, "VALIDATION_ERROR", "Say or type the requirement first")
            return Reply(200, ApiJson.encodeToString(Extraction.serializer(), readForForm(text, b.str("english"))))
        }
        val id = seg.getOrNull(1)
        val out: DemoStore.Out = when {
            method == "POST" && p == "voice-notes/text" -> {
                val transcript = b.str("transcript")?.trim().orEmpty()
                if (transcript.isEmpty()) return error(400, "VALIDATION_ERROR", "Type what was said")
                store.createVoiceNote(b.str("clientId").orEmpty(), b.str("inquiryId"), b.str("language") ?: "AUTO", transcript, false, null, ::read)
            }
            method == "PUT" && seg.size == 3 && seg[2] == "transcript" -> store.setTranscript(id!!, b.str("transcript"), ::read)
            method == "POST" && seg.size == 3 && seg[2] == "apply" -> store.applyVoiceNote(id!!, b)
            method == "POST" && seg.size == 3 && seg[2] == "discard" -> store.discardVoiceNote(id!!)
            method == "POST" && seg.size == 3 && seg[2] == "retry" ->
                return error(400, "DEMO_MODE", "Turning a recording into text needs a BrokerBuddy server. Play it and type what was said.")
            else -> return error(403, "DEMO_MODE", NEEDS_SERVER)
        }
        return when (out) {
            is DemoStore.Out.Err -> error(out.status, out.code, out.message, out.details)
            is DemoStore.Out.Ok -> {
                onChange(changes)
                when {
                    seg.size == 3 && seg[2] == "apply" -> Reply(200, out.body.toString())
                    seg.size == 3 && seg[2] == "discard" -> Reply(200, jsonOf("voiceNote" to store.voiceNote(id!!)).toString())
                    p == "voice-notes/text" -> Reply(201, presentNote((out.body as JsonObject).str("id")!!).toString())
                    else -> Reply(200, presentNote(id!!).toString())
                }
            }
        }
    }

    // ---------- portal leads: grouped by listing and filtered by date, as on the server ----------

    private fun range(param: (String) -> String?): Pair<Instant?, Instant?> =
        param("from")?.let { runCatching { Instant.parse(it) }.getOrNull() } to param("to")?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** Enquired within [from, to). */
    private fun inRange(lead: JsonObject, from: Instant?, to: Instant?): Boolean {
        val at = lead.str("enquiredAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return from == null && to == null
        return (from == null || at >= from) && (to == null || at < to)
    }

    /** Distinct people: the client, else the phone, else the email, else the enquiry itself. */
    private fun personKey(l: JsonObject) =
        l.str("clientId")?.let { "c:$it" } ?: l.str("phone")?.let { "p:$it" } ?: l.str("email")?.let { "e:$it" } ?: "l:${l.str("id")}"

    private fun summaries(portal: String, from: Instant?, to: Instant?): List<JsonObject> {
        val facts = store.portalListingFacts(portal).associateBy { it.str("id") }
        val cover = store.propertiesAll().associate { it.str("id") to store.photoIdsOf(it).firstOrNull() }
        return store.portalLeadsAll().filter { it.str("portal") == portal && inRange(it, from, to) }
            .groupBy { it.str("listingId") ?: UNIDENTIFIED }
            .map { (key, leads) ->
                val f = facts[key]
                val base = f ?: jsonOf("id" to s(key), "portal" to s(portal), "identified" to JsonPrimitive(key != UNIDENTIFIED))
                base.plus(
                    // The linked inventory property's cover photo, including photos added in the demo.
                    "propertyPhotoId" to s(base.str("propertyId")?.let { cover[it] }),
                    "interestedClients" to JsonPrimitive(leads.map(::personKey).toSet().size),
                    "newLeads" to JsonPrimitive(leads.count { it.str("status") == "NEW" }),
                    "totalLeads" to JsonPrimitive(leads.size),
                    "lastEnquiryAt" to s(leads.mapNotNull { it.str("enquiredAt") }.maxOrNull()),
                )
            }
            .sortedByDescending { it.str("lastEnquiryAt") }
    }

    private fun portalListings(portal: String, range: Pair<Instant?, Instant?>) =
        jsonOf("listings" to list(summaries(portal, range.first, range.second)))

    private fun portalListing(id: String, portalParam: String?, range: Pair<Instant?, Instant?>): JsonElement? {
        val portal = if (id == UNIDENTIFIED) portalParam ?: return null
        else DemoStore.PORTALS.firstOrNull { p -> store.portalListingFacts(p).any { it.str("id") == id } } ?: return null
        val summary = summaries(portal, null, null).firstOrNull { it.str("id") == id }
        val leads = store.portalLeadsAll().filter {
            it.str("portal") == portal && (it.str("listingId") ?: UNIDENTIFIED) == id && inRange(it, range.first, range.second)
        }
        return jsonOf(
            "listing" to summary,
            "totalInterestedClients" to JsonPrimitive(summary?.let { (it["interestedClients"] as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0),
            "leads" to list(leads),
        )
    }

    // ---------- matching (the server's rules, on the phone; follows every demo edit) ----------

    private fun <T> typed(o: JsonObject, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { ApiJson.decodeFromJsonElement(serializer, o) }.getOrNull()

    private fun typedProperties(): List<Pair<JsonObject, Property>> =
        store.propertiesAll().mapNotNull { o -> typed(o, Property.serializer())?.let { o to it } }

    private fun result(r: Matching.Result): Array<Pair<String, JsonElement?>> = arrayOf(
        "eligible" to JsonPrimitive(r.eligible),
        "score" to JsonPrimitive(r.score),
        "checks" to list(r.checks.map { c ->
            jsonOf("field" to s(c.field.name), "outcome" to s(c.outcome.wire), "mandatory" to JsonPrimitive(c.mandatory), "detail" to s(c.detail))
        }),
        "violations" to list(r.violations.map(::s)),
        "needsVerification" to list(r.needsVerification.map { s(it.name) }),
    )

    /** Available properties for a requirement, best first; a must-have budget caps the price. */
    private fun inquiryMatches(id: String): JsonElement? {
        val raw = store.inquiriesAll().firstOrNull { it.str("id") == id } ?: return null
        val r = typed(JsonObject(raw - "client" - "matchCount"), Inquiry.serializer()) ?: return null
        val cap = r.budgetMax.takeIf { "BUDGET" in r.mandatory.map { f -> f.name } }
        val candidates = typedProperties().filter { (_, p) ->
            p.transactionType == r.transactionType && p.category == r.category && p.availability.name == "AVAILABLE" &&
                (cap == null || p.price <= cap)
        }.sortedBy { (o, _) -> o.str("createdAt") } // ties: oldest first, as on the server
        val ranked = Matching.rank(candidates, { (_, p) -> Matching.evaluate(r, p) }, { (_, p) -> p.price })
        return jsonOf("inquiryId" to s(id), "matches" to list(ranked.map { (pair, res) -> jsonOf("property" to pair.first, *result(res)) }))
    }

    /** Clients whose active requirement this property fits. */
    private fun propertyMatches(id: String): JsonElement? {
        val raw = store.propertiesAll().firstOrNull { it.str("id") == id } ?: return null
        val p = typed(raw, Property.serializer()) ?: return null
        val candidates = store.inquiriesAll().filter {
            it.str("status") == "ACTIVE" && it.str("transactionType") == p.transactionType.name && it.str("category") == p.category.name
        }.sortedBy { it.str("createdAt") } // ties: oldest first, as on the server
            .mapNotNull { o -> typed(JsonObject(o - "client" - "matchCount"), Inquiry.serializer())?.let { o to it } }
        val ranked = Matching.rank(candidates, { (_, r) -> Matching.evaluate(r, p) }, { 0L })
        return jsonOf(
            "propertyId" to s(id),
            "matches" to list(ranked.map { (pair, res) ->
                val o = pair.first
                val client = o.str("clientId")?.let(store::clientRef)
                jsonOf("inquiry" to JsonObject(o - "client" - "matchCount").plus("client" to client), *result(res))
            }),
        )
    }

    private fun reminderList(status: String?, kind: String?, clientId: String?): JsonElement =
        jsonOf(
            "reminders" to list(
                store.remindersAll().filter {
                    (status == null || it.str("status") == status) && (kind == null || (it.str("kind") ?: "FOLLOW_UP") == kind) &&
                        (clientId == null || it.str("clientId") == clientId)
                },
            ),
        )

    /**
     * The home screen, worked out from the current demo data the way the server does it, in the
     * phone's time zone ([tzMinutes] east of UTC): Today's Work counts, totals, the Rent/Buy
     * boards, today's follow-ups, new leads and top matches.
     */
    private fun dashboard(tzMinutes: Int): JsonElement? {
        val d = base("dashboard")?.jsonObject ?: return null
        val nowI = now()
        val zone = java.time.ZoneOffset.ofTotalSeconds(tzMinutes.coerceIn(-720, 840) * 60)
        val startOfToday = nowI.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val endOfToday = startOfToday.plus(Duration.ofDays(1)).minusMillis(1)
        val weekAgo = nowI.minus(Duration.ofDays(7))
        fun instant(o: JsonObject, key: String) = o.str(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        fun summary(i: JsonObject?) = i?.let {
            jsonOf("transactionType" to it["transactionType"], "category" to it["category"],
                "location" to ((it["locations"] as? JsonArray)?.firstOrNull() ?: JsonNull))
        }

        val clients = store.allClientItems()
        val inquiries = store.inquiriesAll()
        val active = inquiries.filter { it.str("status") == "ACTIVE" }
        val properties = store.propertiesAll()
        val available = properties.filter { it.str("availability") == "AVAILABLE" }
        // Follow-ups assigned to the signed-in broker (as on the server).
        val me = store.meId()
        val pending = store.remindersAll().filter { it.str("status") == "PENDING" && (it.str("assignedToId") ?: me) == me }
        val overdue = pending.count { r -> instant(r, "dueAt")?.let { it < nowI } == true }
        val dueToday = pending.count { r -> instant(r, "dueAt")?.let { it >= nowI && it <= endOfToday } == true }
        val dueByToday = pending.filter { r -> instant(r, "dueAt")?.let { it <= endOfToday } == true }

        fun board(type: String): JsonObject {
            val tiles = CATEGORIES.map { cat ->
                val rows = active.filter { it.str("transactionType") == type && it.str("category") == cat }
                jsonOf("category" to s(cat), "inquiries" to JsonPrimitive(rows.size), "clients" to JsonPrimitive(rows.mapNotNull { it.str("clientId") }.toSet().size))
            }
            return jsonOf("total" to JsonPrimitive(tiles.sumOf { (it["inquiries"] as JsonPrimitive).content.toInt() }), "tiles" to list(tiles))
        }
        fun createdSince(o: JsonObject) = instant(o, "createdAt")?.let { it >= weekAgo } == true

        val totals = jsonOf(
            "clients" to JsonPrimitive(clients.size),
            "newClientsThisWeek" to JsonPrimitive(clients.count(::createdSince)),
            "activeRequirements" to JsonPrimitive(active.size),
            "newRequirementsThisWeek" to JsonPrimitive(active.count(::createdSince)),
            "availableProperties" to JsonPrimitive(available.size),
            "newPropertiesThisWeek" to JsonPrimitive(available.count(::createdSince)),
            "pendingFollowUps" to JsonPrimitive(pending.size),
            "followUpsDueToday" to JsonPrimitive(overdue + dueToday),
        )
        val leads = store.portalLeadsAll()
        fun portalToday(portal: String) = leads.count { l -> l.str("portal") == portal && instant(l, "enquiredAt")?.let { it >= startOfToday && it <= endOfToday } == true }
        val work = jsonOf(
            "newLeads" to JsonPrimitive(clients.count { it.str("status") == "NEW" }),
            "callbacks" to JsonPrimitive(dueByToday.count { it.str("kind") == "CALLBACK" }),
            "followUps" to JsonPrimitive(dueByToday.count { (it.str("kind") ?: "FOLLOW_UP") == "FOLLOW_UP" }),
            "acres99Leads" to JsonPrimitive(portalToday("ACRES_99")),
            "housingLeads" to JsonPrimitive(portalToday("HOUSING_COM")),
        )
        val latestActive = { clientId: String? -> active.filter { it.str("clientId") == clientId }.maxByOrNull { it.str("updatedAt") ?: "" } }
        val todayFollowUps = dueByToday.sortedBy { it.str("dueAt") }.take(10).map { r ->
            val client = r.str("clientId")?.let(store::clientRef)
            jsonOf(
                "id" to r["id"], "title" to r["title"], "dueAt" to r["dueAt"],
                "overdue" to JsonPrimitive(instant(r, "dueAt")?.let { it < nowI } == true),
                "client" to client?.let { jsonOf("id" to it["id"], "name" to it["name"], "primaryPhone" to it["primaryPhone"]) },
                "requirement" to summary(r.str("inquiryId")?.let { id -> inquiries.firstOrNull { it.str("id") == id } } ?: latestActive(r.str("clientId"))),
            )
        }
        // New leads: clients added this week still marked New, plus the sample's WhatsApp/portal leads not yet linked.
        val freshClients = clients.filter { it.str("status") == "NEW" && createdSince(it) }.map { c ->
            jsonOf(
                "kind" to s("CLIENT"), "id" to c["id"], "clientId" to c["id"], "messageId" to JsonNull, "name" to c["name"],
                "phone" to c["primaryPhone"], "source" to c["leadSource"], "at" to c["createdAt"], "requirement" to summary(latestActive(c.str("id"))),
            )
        }
        val unlinked = d.arr("newLeads").filter { it.str("kind") == "WHATSAPP" }
        val newLeads = (freshClients + unlinked).sortedByDescending { it.str("at") }.take(5)

        val typed = available.mapNotNull { o -> typed(o, Property.serializer())?.let { o to it } }
        val requirements = active.mapNotNull { typed(JsonObject(it - "client" - "matchCount"), Inquiry.serializer()) }
        val topMatches = typed.map { (o, p) -> o to requirements.count { r -> r.transactionType == p.transactionType && r.category == p.category && Matching.evaluate(r, p).eligible } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(6)
            .map { (o, n) -> jsonOf("property" to o, "matchingRequirements" to JsonPrimitive(n)) }

        return d.plus(
            "generatedAt" to s(nowI.toString()),
            "rent" to board("RENT"), "buy" to board("BUY"),
            "clientsByStatus" to JsonObject(clients.groupingBy { it.str("status") ?: "NEW" }.eachCount().mapValues { JsonPrimitive(it.value) }),
            "reminders" to jsonOf("overdue" to JsonPrimitive(overdue), "dueToday" to JsonPrimitive(dueToday)),
            "availableProperties" to JsonPrimitive(available.size),
            "totals" to totals, "todayWork" to work, "todayFollowUps" to list(todayFollowUps),
            "newLeads" to list(newLeads), "topMatches" to list(topMatches),
        )
    }

    // ---------- writes ----------

    private fun write(method: String, p: String, seg: List<String>, body: JsonObject?): Reply {
        val b = body ?: JsonObject(emptyMap())
        if (seg.firstOrNull() == "voice-notes") return voiceWrite(method, p, seg, b)
        val out: DemoStore.Out? = when {
            method == "POST" && p == "clients" -> store.createClient(b)
            method == "PATCH" && seg.size == 2 && seg[0] == "clients" -> store.updateClient(seg[1], b)
            method == "DELETE" && seg.size == 2 && seg[0] == "clients" -> store.deleteClient(seg[1])
            method == "POST" && seg.size == 3 && seg[0] == "clients" && seg[2] == "inquiries" -> store.createInquiry(seg[1], b)
            method == "POST" && seg.size == 3 && seg[0] == "clients" && seg[2] == "notes" -> store.addNote(seg[1], b)
            method == "PATCH" && seg.size == 2 && seg[0] == "inquiries" -> store.updateInquiry(seg[1], b)
            method == "POST" && p == "properties" -> store.createProperty(b)
            method == "PATCH" && seg.size == 2 && seg[0] == "properties" -> store.updateProperty(seg[1], b)
            method == "DELETE" && seg.size == 2 && seg[0] == "properties" -> store.deleteProperty(seg[1])
            method == "DELETE" && seg.size == 4 && seg[0] == "properties" && seg[2] == "photos" -> store.removePhoto(seg[1], seg[3])
            method == "POST" && p == "reminders" -> store.createReminder(b)
            method == "PATCH" && seg.size == 2 && seg[0] == "reminders" -> store.updateReminder(seg[1], b)
            method == "PATCH" && seg.size == 2 && seg[0] == "portal-leads" -> store.setLeadStatus(seg[1], b)
            else -> null
        }
        return when (out) {
            null -> error(403, "DEMO_MODE", NEEDS_SERVER)
            is DemoStore.Out.Err -> error(out.status, out.code, out.message, out.details)
            is DemoStore.Out.Ok -> {
                onChange(changes)
                Reply(out.status, if (out.body == JsonNull) "" else out.body.toString())
            }
        }
    }

    private fun ok(body: JsonElement) = Reply(200, body.toString())

    private fun error(status: Int, code: String, message: String, details: JsonElement? = null) =
        Reply(status, jsonOf("error" to jsonOf("code" to s(code), "message" to s(message), "details" to details)).toString())

    companion object {
        const val NO_VOICE_FILL = "Voice fill reads your words on the BrokerBuddy server, so it isn't available in the demo. Fill the form by hand, or connect to a server."
        const val NEEDS_SERVER = "This needs a BrokerBuddy server, so it isn't available in the demo. In the demo you can add and edit clients, requirements, properties, follow-ups and notes."
        /** Kept for older callers/tests: what the demo said before writes were supported. */
        const val READ_ONLY = NEEDS_SERVER

        private const val UNIDENTIFIED = "unidentified"
        private const val IST_MINUTES = 330
        private val CATEGORIES = listOf("STUDIO", "BHK_1", "BHK_2", "BHK_3", "BHK_4", "BHK_5_PLUS", "COMMERCIAL", "OTHER")
        private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

        /** Query parameters that don't change which answer is shown. */
        private val IGNORED = setOf("tz", "page", "pageSize", "from", "to")

        /** Same as the snapshot script's key(): path, then the query sorted by name. */
        fun key(path: String, params: List<Pair<String, String?>>): String {
            val q = params.filter { (k, v) -> !v.isNullOrEmpty() && k !in IGNORED }.sortedBy { it.first }
            return if (q.isEmpty()) path else path + "?" + q.joinToString("&") { (k, v) -> "$k=$v" }
        }

        private val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z""")

        fun shiftDates(json: String, days: Long): String =
            if (days == 0L) json else INSTANT.replace(json) { m ->
                runCatching { Instant.parse(m.value).plus(Duration.ofDays(days)).toString() }.getOrDefault(m.value)
            }

        /** Keeps the list entries that mention [q] (text, or the digits of a phone number); updates "total". */
        fun narrow(body: JsonElement, q: String): JsonElement {
            val obj = body as? JsonObject ?: return body
            val text = q.lowercase()
            val digits = q.filter(Char::isDigit)
            fun matches(e: JsonElement): Boolean {
                val s = e.toString()
                return text in s.lowercase() || (digits.length >= 3 && digits in s.filter(Char::isDigit))
            }
            var kept: Int? = null
            val fields = obj.mapValues { (_, v) ->
                if (v is JsonArray && v.all { it is JsonObject }) JsonArray(v.filter(::matches)).also { if (kept == null) kept = it.size } else v
            }
            return JsonObject(fields.mapValues { (k, v) -> if (k == "total" && kept != null) JsonPrimitive(kept) else v })
        }
    }
}
