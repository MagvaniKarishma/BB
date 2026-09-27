package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.AddNoteRequest
import com.brokerbuddy.core.model.ApiErrorBody
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.Availability
import com.brokerbuddy.core.model.ClientEnvelope
import com.brokerbuddy.core.model.ClientList
import com.brokerbuddy.core.model.ClientNoteEnvelope
import com.brokerbuddy.core.model.ClientNoteList
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.CreateClientRequest
import com.brokerbuddy.core.model.CreateReminderRequest
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.InquiryEnvelope
import com.brokerbuddy.core.model.InquiryList
import com.brokerbuddy.core.model.InquiryUpdateResponse
import com.brokerbuddy.core.model.LeadSource
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.PropertyEnvelope
import com.brokerbuddy.core.model.PropertyList
import com.brokerbuddy.core.model.PropertyRequest
import com.brokerbuddy.core.model.ReminderEnvelope
import com.brokerbuddy.core.model.ReminderKind
import com.brokerbuddy.core.model.ReminderList
import com.brokerbuddy.core.model.ReminderStatus
import com.brokerbuddy.core.model.RequirementRequest
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.model.UpdateClientRequest
import com.brokerbuddy.core.model.UpdateReminderRequest
import kotlinx.serialization.KSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The writable demo, against the real bundled sample data, with request bodies encoded exactly
 * as the app sends them and responses decoded with the app's models.
 */
class DemoWritesTest {
    private val snapshot = File("../app/src/main/assets/demo/responses.json").readText()
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    /** A demo whose changes are "saved" into [saved], like the app's file. */
    private class Phone(snapshot: String, today: LocalDate, var saved: String? = null) {
        val api = DemoApi(snapshot, today, DemoChanges.fromJson(saved), onChange = { saved = it.toJson() })
    }

    private fun <T> DemoApi.send(method: String, path: String, serializer: KSerializer<T>, body: T): DemoApi.Reply =
        handle(method, "/api/v1/$path", emptyList(), ApiJson.encodeToString(serializer, body))

    private fun <T> DemoApi.get(path: String, serializer: KSerializer<T>, vararg params: Pair<String, String?>): T {
        val r = handle("GET", "/api/v1/$path", params.toList())
        assertEquals(200, r.status, "$path: ${r.body}")
        return ApiJson.decodeFromString(serializer, r.body)
    }

    private fun <T> decode(r: DemoApi.Reply, serializer: KSerializer<T>, status: Int = 200): T {
        assertEquals(status, r.status, r.body)
        return ApiJson.decodeFromString(serializer, r.body)
    }

    @Test
    fun createAndEditClient_thenRestart() {
        val phone = Phone(snapshot, today)
        val api = phone.api
        val before = api.get("clients", ClientList.serializer(), "group" to "all")

        val created = decode(
            api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Test Demo Client", "98765 00001", leadSource = LeadSource.WALK_IN)),
            ClientEnvelope.serializer(), 201,
        ).client
        assertEquals("+919876500001", created.primaryPhone)
        assertEquals(ClientStatus.NEW, created.status)

        val all = api.get("clients", ClientList.serializer(), "group" to "all")
        assertEquals(before.clients.size + 1, all.clients.size)
        assertEquals("Test Demo Client", all.clients.first().name)
        assertTrue(api.get("clients", ClientList.serializer(), "group" to "new").clients.any { it.id == created.id })
        assertEquals(1, api.get("clients", ClientList.serializer(), "group" to "all", "q" to "Test Demo").clients.size)

        // Edit: status → Contacted moves it from New to Active.
        val edited = decode(
            api.send("PATCH", "clients/${created.id}", UpdateClientRequest.serializer(),
                UpdateClientRequest("Test Demo Client", "demo@example.com", LeadSource.WALK_IN, ClientStatus.CONTACTED, "Prefers evenings")),
            ClientEnvelope.serializer(),
        ).client
        assertEquals(ClientStatus.CONTACTED, edited.status)
        assertFalse(api.get("clients", ClientList.serializer(), "group" to "new").clients.any { it.id == created.id })
        assertTrue(api.get("clients", ClientList.serializer(), "group" to "active").clients.any { it.id == created.id })
        val detail = api.get("clients/${created.id}", ClientEnvelope.serializer()).client
        assertEquals("demo@example.com", detail.email)
        assertEquals("Prefers evenings", detail.notes)

        // Same phone again → the existing client, as the server does.
        val dup = api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Someone", "+91 98765 00001", leadSource = LeadSource.OTHER))
        assertEquals(409, dup.status)
        assertEquals("DUPLICATE_CLIENT", ApiJson.decodeFromString(ApiErrorBody.serializer(), dup.body).error.code)
        assertEquals(400, api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("X", "12", leadSource = LeadSource.OTHER)).status)

        // Editing a sample client works too.
        val sample = before.clients.first()
        api.send("PATCH", "clients/${sample.id}", UpdateClientRequest.serializer(),
            UpdateClientRequest(sample.name, sample.email, sample.leadSource, ClientStatus.NEGOTIATION, sample.notes))
        assertEquals(ClientStatus.NEGOTIATION, api.get("clients/${sample.id}", ClientEnvelope.serializer()).client.status)

        // "Close and reopen the app": a new demo from the saved changes has everything.
        val reopened = Phone(snapshot, today, phone.saved).api
        val again = reopened.get("clients/${created.id}", ClientEnvelope.serializer()).client
        assertEquals(ClientStatus.CONTACTED, again.status)
        assertEquals(ClientStatus.NEGOTIATION, reopened.get("clients/${sample.id}", ClientEnvelope.serializer()).client.status)
        assertEquals(before.clients.size + 1, reopened.get("clients", ClientList.serializer(), "group" to "all").clients.size)
    }

    @Test
    fun propertiesRequirementsFollowUpsAndNotes() {
        val api = Phone(snapshot, today).api
        val client = api.get("clients", ClientList.serializer(), "group" to "all").clients.first()
        val available = api.get("properties", PropertyList.serializer(), "transactionType" to "RENT", "availability" to "AVAILABLE")

        // Property
        val property = decode(
            api.send("POST", "properties", PropertyRequest.serializer(),
                PropertyRequest("Demo 2 BHK", TransactionType.RENT, PropertyCategory.BHK_2, 55_000, locality = "Chembur", bathrooms = 2)),
            PropertyEnvelope.serializer(), 201,
        ).property
        val rentList = api.get("properties", PropertyList.serializer(), "transactionType" to "RENT", "availability" to "AVAILABLE")
        assertEquals(available.properties.size + 1, rentList.properties.size)
        assertEquals("Demo 2 BHK", api.get("properties/${property.id}", PropertyEnvelope.serializer()).property.title)
        decode(api.send("PATCH", "properties/${property.id}", PropertyRequest.serializer(),
            PropertyRequest("Demo 2 BHK", TransactionType.RENT, PropertyCategory.BHK_2, 55_000, locality = "Chembur", availability = Availability.RENTED)),
            PropertyEnvelope.serializer())
        assertFalse(api.get("properties", PropertyList.serializer(), "transactionType" to "RENT", "availability" to "AVAILABLE").properties.any { it.id == property.id })
        assertTrue(api.get("properties/${property.id}/matches", com.brokerbuddy.core.model.InquiryMatches.serializer()).matches.isEmpty())

        // Requirement
        val req = decode(
            api.send("POST", "clients/${client.id}/inquiries", RequirementRequest.serializer(),
                RequirementRequest(TransactionType.BUY, PropertyCategory.BHK_3, budgetMax = 25_000_000, locations = listOf("Powai"))),
            InquiryEnvelope.serializer(), 201,
        ).inquiry
        assertTrue(api.get("clients/${client.id}", ClientEnvelope.serializer()).client.inquiries.any { it.id == req.id })
        assertTrue(api.get("inquiries", InquiryList.serializer(), "transactionType" to "BUY").inquiries.any { it.id == req.id })
        val updated = decode(api.send("PATCH", "inquiries/${req.id}", RequirementRequest.serializer(),
            RequirementRequest(TransactionType.BUY, PropertyCategory.BHK_3, budgetMax = 27_000_000, locations = listOf("Powai"))),
            InquiryUpdateResponse.serializer())
        assertEquals(27_000_000, updated.inquiry.budgetMax)
        assertEquals(2, updated.inquiry.version)
        assertEquals(27_000_000, api.get("inquiries/${req.id}", InquiryEnvelope.serializer()).inquiry.budgetMax)

        // Follow-up
        val due = Instant.now().plusSeconds(3_600).toString()
        val reminder = decode(
            api.send("POST", "reminders", CreateReminderRequest.serializer(), CreateReminderRequest("Call about Chembur flat", due, clientId = client.id)),
            ReminderEnvelope.serializer(), 201,
        ).reminder
        assertEquals(ReminderKind.FOLLOW_UP, reminder.kind)
        assertTrue(api.get("reminders", ReminderList.serializer(), "status" to "PENDING").reminders.any { it.id == reminder.id })
        assertTrue(api.get("clients/${client.id}", ClientEnvelope.serializer()).client.reminders.any { it.id == reminder.id })
        val home = api.get("dashboard", Dashboard.serializer(), "tz" to "330")
        assertTrue((home.todayWork?.followUps ?: 0) >= 1)
        decode(api.send("PATCH", "reminders/${reminder.id}", UpdateReminderRequest.serializer(), UpdateReminderRequest(status = ReminderStatus.DONE)), ReminderEnvelope.serializer())
        assertFalse(api.get("reminders", ReminderList.serializer(), "status" to "PENDING").reminders.any { it.id == reminder.id })
        assertTrue(api.get("reminders", ReminderList.serializer(), "status" to "DONE").reminders.any { it.id == reminder.id })

        // Note
        decode(api.send("POST", "clients/${client.id}/notes", AddNoteRequest.serializer(), AddNoteRequest("Wants to visit on Sunday")), ClientNoteEnvelope.serializer(), 201)
        assertEquals("Wants to visit on Sunday", api.get("clients/${client.id}/notes", ClientNoteList.serializer()).notes.first().body)
    }

    @Test
    fun sampleDataIsProtectedAndResetRestoresIt() {
        val phone = Phone(snapshot, today)
        val api = phone.api
        val original = api.get("clients", ClientList.serializer(), "group" to "all")
        val originalHome = api.handle("GET", "/api/v1/dashboard", listOf("tz" to "330")).body

        // Sample records can't be deleted; the demo's own can.
        val sample = original.clients.first()
        val refused = api.handle("DELETE", "/api/v1/clients/${sample.id}", emptyList())
        assertEquals(403, refused.status)
        assertTrue("Reset demo" in refused.body)
        val mine = decode(api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Temp Demo", "98765 00002", leadSource = LeadSource.OTHER)), ClientEnvelope.serializer(), 201).client
        assertEquals(204, api.handle("DELETE", "/api/v1/clients/${mine.id}", emptyList()).status)
        assertEquals(404, api.handle("GET", "/api/v1/clients/${mine.id}", emptyList()).status)

        api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Another Demo", "98765 00003", leadSource = LeadSource.OTHER))
        api.send("PATCH", "clients/${sample.id}", UpdateClientRequest.serializer(), UpdateClientRequest("Renamed", sample.email, sample.leadSource, sample.status, sample.notes))
        assertTrue(api.changes.count > 0)

        api.reset()
        assertEquals(0, api.changes.count)
        assertEquals(original.clients.map { it.id to it.name }, api.get("clients", ClientList.serializer(), "group" to "all").clients.map { it.id to it.name })
        assertEquals(originalHome, api.handle("GET", "/api/v1/dashboard", listOf("tz" to "330")).body)
        // The saved changes are cleared too, so reopening shows the original sample data.
        assertEquals(original.clients.size, Phone(snapshot, today, phone.saved).api.get("clients", ClientList.serializer(), "group" to "all").clients.size)
    }

    @Test
    fun damagedSavedChangesFallBackToTheSampleData() {
        val api = Phone(snapshot, today, saved = "{not json").api
        assertEquals(0, api.changes.count)
        assertNull(api.get("clients", ClientList.serializer(), "group" to "all").clients.firstOrNull { it.id.startsWith("demo-") })
    }
}
