package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.MeResponse
import com.brokerbuddy.core.model.User
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The offline demo ("Try the demo"): answers the app's API requests from a snapshot of real
 * server responses (built by backend/scripts/demo-snapshot.ts), so every screen can be seen
 * without a server. Reads only — anything that would change data is refused with a clear message.
 *
 * Dates are moved forward by whole days from the snapshot's date to [today], so today's
 * follow-ups are still today's.
 */
class DemoApi(snapshotJson: String, today: LocalDate) {
    data class Reply(val status: Int, val body: String)

    private val root = ApiJson.parseToJsonElement(snapshotJson).jsonObject
    private val responses = root.getValue("responses").jsonObject
    private val shiftDays = ChronoUnit.DAYS.between(LocalDate.parse(root.getValue("capturedOn").jsonPrimitive.content), today)

    /** The demo broker's account, for signing in. */
    fun user(): User = ApiJson.decodeFromJsonElement(MeResponse.serializer(), responses.getValue("auth/me")).user

    /** [path] is the request's URL path (anything before "api/v1/" is ignored). */
    fun handle(method: String, path: String, params: List<Pair<String, String?>>): Reply {
        val p = path.substringAfter("api/v1/").trim('/')
        if (method != "GET") return error(403, "DEMO_MODE", if (p == "voice-notes/extract") NO_VOICE_FILL else READ_ONLY)
        responses[key(p, params)]?.let { return ok(it) }
        // Searches: the unfiltered list, narrowed to entries that mention the search text.
        val q = params.firstOrNull { it.first == "q" }?.second?.trim().orEmpty()
        responses[key(p, params.filter { it.first != "q" })]?.let { return ok(if (q.isEmpty()) it else narrow(it, q)) }
        return error(404, "NOT_FOUND", "This isn't available in the demo.")
    }

    private fun ok(body: JsonElement) = Reply(200, shiftDates(body.toString(), shiftDays))

    private fun error(status: Int, code: String, message: String) =
        Reply(status, JsonObject(mapOf("error" to JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message))))).toString())

    companion object {
        const val NO_VOICE_FILL = "Voice fill reads your words on the BrokerBuddy server, so it isn't available in the demo. Fill the form by hand, or connect to a server."
        const val READ_ONLY = "This is the demo, so changes aren't saved. Connect to a BrokerBuddy server to add or edit data."

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
