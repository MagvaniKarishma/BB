package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.AiCallList
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.CallAssistantInfo
import com.brokerbuddy.core.model.CallerDirectory
import com.brokerbuddy.core.model.ClientEnvelope
import com.brokerbuddy.core.model.ClientList
import com.brokerbuddy.core.model.ClientNoteList
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.InquiryEnvelope
import com.brokerbuddy.core.model.InquiryList
import com.brokerbuddy.core.model.InquiryMatches
import com.brokerbuddy.core.model.MeResponse
import com.brokerbuddy.core.model.OtpStatus
import com.brokerbuddy.core.model.PortalIntegrations
import com.brokerbuddy.core.model.PortalListingDetail
import com.brokerbuddy.core.model.PortalListingList
import com.brokerbuddy.core.model.PropertyEnvelope
import com.brokerbuddy.core.model.PropertyList
import com.brokerbuddy.core.model.PropertyMatches
import com.brokerbuddy.core.model.ReminderList
import com.brokerbuddy.core.model.RevisionList
import com.brokerbuddy.core.model.TeamList
import com.brokerbuddy.core.model.VoiceNoteList
import com.brokerbuddy.core.model.VoiceNoteResponse
import com.brokerbuddy.core.model.WaAccountStatus
import com.brokerbuddy.core.model.WaConversation
import com.brokerbuddy.core.model.WaInbox
import com.brokerbuddy.core.model.WaMessageDetail
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoApiTest {
    private val snapshot = """
        {"capturedOn":"2026-09-26","responses":{
          "clients?group=all":{"clients":[{"id":"a","name":"Rahul Sharma","primaryPhone":"+919820011001"},{"id":"b","name":"Priya Mehta","primaryPhone":"+919820011002"}],"total":2},
          "reminders":{"reminders":[{"id":"r","status":"PENDING","dueAt":"2026-09-26T09:30:00.000Z"}]}
        }}
    """.trimIndent()

    @Test
    fun keysSortParamsAndIgnoreTimeZoneAndPaging() {
        assertEquals("inquiries?category=BHK_2&transactionType=RENT",
            DemoApi.key("inquiries", listOf("transactionType" to "RENT", "tz" to "330", "category" to "BHK_2", "q" to null, "page" to "1")))
        assertEquals("dashboard", DemoApi.key("dashboard", listOf("tz" to "330")))
    }

    @Test
    fun servesSnapshotAndMovesDatesToToday() {
        val api = DemoApi(snapshot, LocalDate.parse("2026-10-01"))
        val r = api.handle("GET", "/api/v1/reminders", listOf("status" to "PENDING", "from" to "x", "to" to "y"))
        assertEquals(200, r.status)
        assertTrue("2026-10-01T09:30:00Z" in r.body, r.body)
    }

    @Test
    fun searchNarrowsByNameOrPhoneDigits() {
        val api = DemoApi(snapshot, LocalDate.parse("2026-09-26"))
        val byName = ApiJson.parseToJsonElement(api.handle("GET", "/api/v1/clients", listOf("q" to "priya", "group" to "all")).body).jsonObject
        assertEquals(listOf("b"), byName.getValue("clients").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals("1", byName.getValue("total").toString())
        val byPhone = ApiJson.parseToJsonElement(api.handle("GET", "/api/v1/clients", listOf("q" to "98200 11001", "group" to "all")).body).jsonObject
        assertEquals(1, byPhone.getValue("clients").jsonArray.size)
    }

    @Test
    fun serverOnlyFeaturesAreRefusedAndUnknownReadsAre404() {
        val api = DemoApi(snapshot, LocalDate.parse("2026-09-26"))
        val post = api.handle("POST", "/api/v1/whatsapp/import", emptyList(), "{}")
        assertEquals(403, post.status)
        assertTrue("DEMO_MODE" in post.body)
        assertEquals(404, api.handle("GET", "/api/v1/properties/x/photos/y", emptyList()).status)
        // Voice fill now reads the words on the phone; only speech-to-text of a recording needs a server.
        assertEquals(400, api.handle("POST", "/api/v1/voice-notes/extract", emptyList(), "{}").status)
        assertTrue("needs a BrokerBuddy server" in api.handle("POST", "/api/v1/voice-notes/x/retry", emptyList(), "{}").body)
    }

    /** Every answer in the bundled snapshot decodes with the app's models, the way its screens read it. */
    @Test
    fun bundledSnapshotDecodes() {
        val text = File("../app/src/main/assets/demo/responses.json").readText()
        val api = DemoApi(text, LocalDate.now())
        assertEquals("Riya Desai", api.user().name)
        val keys = ApiJson.parseToJsonElement(text).jsonObject.getValue("responses").jsonObject.keys
        val id = "[^/?]+"
        val routes: List<Pair<Regex, KSerializer<*>>> = listOf(
            "auth/me" to MeResponse.serializer(),
            "auth/otp" to OtpStatus.serializer(),
            "team" to TeamList.serializer(),
            "caller/directory" to CallerDirectory.serializer(),
            "whatsapp/account" to WaAccountStatus.serializer(),
            "call-assistant" to CallAssistantInfo.serializer(),
            "call-assistant/calls" to AiCallList.serializer(),
            "dashboard" to Dashboard.serializer(),
            "clients(\\?.*)?" to ClientList.serializer(),
            "clients/$id" to ClientEnvelope.serializer(),
            "clients/$id/notes" to ClientNoteList.serializer(),
            "voice-notes(\\?.*)?" to VoiceNoteList.serializer(),
            "voice-notes/$id" to VoiceNoteResponse.serializer(),
            "whatsapp/clients/$id/messages" to WaConversation.serializer(),
            "inquiries(\\?.*)?" to InquiryList.serializer(),
            "inquiries/$id" to InquiryEnvelope.serializer(),
            "inquiries/$id/history" to RevisionList.serializer(),
            "inquiries/$id/matches" to PropertyMatches.serializer(),
            "properties(\\?.*)?" to PropertyList.serializer(),
            "properties/$id" to PropertyEnvelope.serializer(),
            "properties/$id/matches" to InquiryMatches.serializer(),
            "reminders(\\?.*)?" to ReminderList.serializer(),
            "whatsapp/inbox\\?.*" to WaInbox.serializer(),
            "whatsapp/messages/$id" to WaMessageDetail.serializer(),
            "portal-leads/integrations" to PortalIntegrations.serializer(),
            "portal-leads/listings\\?.*" to PortalListingList.serializer(),
            "portal-leads/listings/$id\\?.*" to PortalListingDetail.serializer(),
        ).map { (pattern, serializer) -> Regex(pattern) to serializer }
        assertTrue(keys.size > 100)
        for (key in keys) {
            val serializer = routes.firstOrNull { it.first.matches(key) }?.second ?: error("no model for demo answer $key")
            val path = key.substringBefore('?')
            val params = key.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=') }
            val reply = api.handle("GET", "/api/v1/$path", params)
            assertEquals(200, reply.status, key)
            runCatching { ApiJson.decodeFromString(serializer, reply.body) }.onFailure { throw AssertionError("$key: ${it.message}", it) }
        }
        val home = ApiJson.decodeFromString(Dashboard.serializer(), api.handle("GET", "/api/v1/dashboard", listOf("tz" to "330")).body)
        assertTrue((home.todayWork?.acres99Leads ?: 0) > 0 && (home.todayWork?.housingLeads ?: 0) > 0, "demo has today's portal leads")
        val clients = ApiJson.decodeFromString(ClientList.serializer(), api.handle("GET", "/api/v1/clients", listOf("group" to "all", "tz" to "330")).body)
        assertTrue(clients.clients.size >= 10)
    }
}
