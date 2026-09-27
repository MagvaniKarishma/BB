package com.brokerbuddy.core.demo

import com.brokerbuddy.core.match.Matching
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.MeResponse
import com.brokerbuddy.core.model.Property
import com.brokerbuddy.core.model.User
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
            p == "voice-notes" && param("clientId") in changes.created -> jsonOf("voiceNotes" to list(emptyList()))
            p == "dashboard" -> dashboard()
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

    /** The home screen with counts that include demo changes. */
    private fun dashboard(): JsonElement? {
        val d = base("dashboard")?.jsonObject ?: return null
        val zone = ZoneId.of("Asia/Kolkata")
        val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        val clients = store.allClientItems()
        val pending = store.remindersAll().filter { it.str("status") == "PENDING" }
        fun dueToday(r: JsonObject) = r.str("dueAt")?.let { runCatching { Instant.parse(it) < endOfToday }.getOrDefault(false) } == true
        val available = store.propertiesAll().count { it.str("availability") == "AVAILABLE" }
        val totals = d.obj("totals")?.plus(
            "clients" to JsonPrimitive(clients.size),
            "pendingFollowUps" to JsonPrimitive(pending.size),
            "followUpsDueToday" to JsonPrimitive(pending.count(::dueToday)),
            "availableProperties" to JsonPrimitive(available),
            "activeRequirements" to JsonPrimitive(store.inquiriesAll().count { it.str("status") == "ACTIVE" }),
        )
        val work = d.obj("todayWork")?.plus(
            "newLeads" to JsonPrimitive(clients.count { it.str("status") == "NEW" }),
            "followUps" to JsonPrimitive(pending.count { dueToday(it) && (it.str("kind") ?: "FOLLOW_UP") == "FOLLOW_UP" }),
            "callbacks" to JsonPrimitive(pending.count { dueToday(it) && it.str("kind") == "CALLBACK" }),
        )
        return d.plus("totals" to totals, "todayWork" to work, "availableProperties" to JsonPrimitive(available))
    }

    // ---------- writes ----------

    private fun write(method: String, p: String, seg: List<String>, body: JsonObject?): Reply {
        if (p == "voice-notes/extract") return error(403, "DEMO_MODE", NO_VOICE_FILL)
        val b = body ?: JsonObject(emptyMap())
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
