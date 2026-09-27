package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.AddNoteRequest
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.CallerDirectory
import com.brokerbuddy.core.model.CallerLookup
import com.brokerbuddy.core.model.ClientEnvelope
import com.brokerbuddy.core.model.CreateClientRequest
import com.brokerbuddy.core.model.LeadSource
import com.brokerbuddy.core.model.NoteSource
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The caller screen in the demo: numbers are recognised in any format, demo clients included. */
class DemoCallerTest {
    private val snapshot = File("../app/src/main/assets/demo/responses.json").readText()
    private val api = DemoApi(snapshot, LocalDate.now(ZoneId.of("Asia/Kolkata")))
    private fun lookup(phone: String) = ApiJson.decodeFromString(CallerLookup.serializer(), api.handle("GET", "/api/v1/caller/lookup", listOf("phone" to phone)).body)

    @Test
    fun directoryAndLookup() {
        val server = ApiJson.decodeFromJsonElement(CallerDirectory.serializer(),
            ApiJson.parseToJsonElement(snapshot).jsonObject.getValue("responses").jsonObject.getValue("caller/directory"))
        val demo = ApiJson.decodeFromString(CallerDirectory.serializer(), api.handle("GET", "/api/v1/caller/directory", emptyList()).body)
        assertEquals(server.entries.map { it.e164 to it.clientId }.toSet(), demo.entries.map { it.e164 to it.clientId }.toSet())

        // Rahul Sharma (sample, fictional) in several formats.
        for (raw in listOf("+919820011001", "09820011001", "98200 11001", "+91-98200-11001")) {
            val r = lookup(raw)
            assertEquals("+919820011001", r.number)
            assertEquals("Rahul Sharma", r.client?.name, raw)
        }
        val rahul = lookup("9820011001").client!!
        assertTrue(rahul.inquiries.isNotEmpty())
        assertTrue(rahul.inquiries.first().matchCount != null)
        assertEquals("NOTE", rahul.lastInteraction?.kind)

        // Unknown and hidden numbers.
        val unknown = lookup("+919000000001")
        assertEquals("+919000000001", unknown.number)
        assertNull(unknown.client)
        assertNull(lookup("").number)
        assertNull(lookup("private").number)
    }

    @Test
    fun demoClientsAndCallNotes() {
        val c = ApiJson.decodeFromString(ClientEnvelope.serializer(), api.handle("POST", "/api/v1/clients", emptyList(),
            ApiJson.encodeToString(CreateClientRequest.serializer(), CreateClientRequest("Test Caller", "98765 00321", leadSource = LeadSource.PHONE_CALL))).body).client
        assertTrue(ApiJson.decodeFromString(CallerDirectory.serializer(), api.handle("GET", "/api/v1/caller/directory", emptyList()).body)
            .entries.any { it.clientId == c.id && it.e164 == "+919876500321" })
        assertEquals(c.id, lookup("9876500321").client?.id)
        // A note typed on the caller screen becomes the last interaction.
        api.handle("POST", "/api/v1/clients/${c.id}/notes", emptyList(),
            ApiJson.encodeToString(AddNoteRequest.serializer(), AddNoteRequest("Called about 1 BHK in Malad; call back Monday", NoteSource.CALLER_SCREEN)))
        assertEquals("Called about 1 BHK in Malad; call back Monday", lookup("+919876500321").client?.lastInteraction?.text)
    }
}
