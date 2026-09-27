package com.brokerbuddy.core.demo

import com.brokerbuddy.core.match.MatchLabel
import com.brokerbuddy.core.model.FloorBand
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.InquiryMatches
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.PropertyMatches
import com.brokerbuddy.core.model.PropertyType
import com.brokerbuddy.core.model.RequirementField
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
                PropertyRequest("Demo 2 BHK", TransactionType.RENT, PropertyCategory.BHK_2, price = 55_000, locality = "Chembur", bathrooms = 2)),
            PropertyEnvelope.serializer(), 201,
        ).property
        val rentList = api.get("properties", PropertyList.serializer(), "transactionType" to "RENT", "availability" to "AVAILABLE")
        assertEquals(available.properties.size + 1, rentList.properties.size)
        assertEquals("Demo 2 BHK", api.get("properties/${property.id}", PropertyEnvelope.serializer()).property.title)
        decode(api.send("PATCH", "properties/${property.id}", PropertyRequest.serializer(),
            PropertyRequest("Demo 2 BHK", TransactionType.RENT, PropertyCategory.BHK_2, price = 55_000, locality = "Chembur", availability = Availability.RENTED)),
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
    fun inventoryWorkflow_createPhotosEditSearchMatchRestart() {
        val phone = Phone(snapshot, today)
        var api = phone.api

        // 1. Create a property with every field.
        val body = PropertyRequest(
            title = "Test 2 BHK, Demo Heights", transactionType = TransactionType.RENT, category = PropertyCategory.BHK_2,
            propertyType = PropertyType.APARTMENT, price = 72_000, deposit = 250_000, locality = "Andheri (W)", building = "Demo Heights",
            carpetAreaSqft = 800, builtUpAreaSqft = 980, bathrooms = 2, furnishing = Furnishing.SEMI_FURNISHED, parkingSpots = 1,
            floor = 15, totalFloors = 20, possession = Possession.READY_TO_MOVE, amenities = listOf("Lift", "Gym"),
            ownerName = "Test Owner", ownerPhone = "9820000099",
        )
        val created = decode(api.send("POST", "properties", PropertyRequest.serializer(), body), PropertyEnvelope.serializer(), 201).property
        assertEquals(listOf("Lift", "Gym"), created.amenities)
        assertEquals(PropertyType.APARTMENT, created.propertyType)
        assertEquals(980, created.builtUpAreaSqft)
        // Photos (the app keeps the files; the record keeps their order).
        assertEquals(201, api.addPhoto(created.id, "demophotoa").status)
        assertEquals(201, api.addPhoto(created.id, "demophotob").status)

        // 2. Open the profile.
        val opened = api.get("properties/${created.id}", PropertyEnvelope.serializer()).property
        assertEquals(listOf("demophotoa", "demophotob"), opened.photoIds)
        assertEquals("Andheri (W)", opened.locality)

        // 3. Edit price and availability (on hold), then back to available.
        decode(api.send("PATCH", "properties/${created.id}", PropertyRequest.serializer(),
            opened.toRequest().copy(price = 68_000, availability = Availability.ON_HOLD)), PropertyEnvelope.serializer())
        fun list(vararg f: Pair<String, String?>) = api.get("properties", PropertyList.serializer(), *f).properties.map { it.id }
        assertFalse(created.id in list("transactionType" to "RENT", "availability" to "AVAILABLE"))
        assertTrue(created.id in list("transactionType" to "RENT", "availability" to "ON_HOLD"))
        assertTrue(created.id in list("transactionType" to "RENT")) // all availabilities: still listed, marked on hold
        decode(api.send("PATCH", "properties/${created.id}", PropertyRequest.serializer(),
            opened.toRequest().copy(price = 68_000, availability = Availability.AVAILABLE)), PropertyEnvelope.serializer())

        // 4. Search and filters (each one, then cleared).
        assertTrue(created.id in list("q" to "demo heights"))
        assertTrue(created.id in list("locality" to "andheri"))
        assertTrue(created.id in list("minPrice" to "60000", "maxPrice" to "70000"))
        assertFalse(created.id in list("maxPrice" to "60000"))
        assertTrue(created.id in list("category" to "BHK_2", "propertyType" to "APARTMENT", "furnishing" to "SEMI_FURNISHED"))
        assertFalse(created.id in list("propertyType" to "VILLA"))
        assertFalse(created.id in list("furnishing" to "FULLY_FURNISHED"))
        assertFalse(created.id in list("transactionType" to "BUY"))
        val all = list()
        assertTrue(created.id in all && all.size > 10)

        // 5. Match it to a client wanting a higher floor apartment in Andheri.
        val client = decode(api.send("POST", "clients", CreateClientRequest.serializer(),
            CreateClientRequest("Test Match Client", "98765 00077", leadSource = LeadSource.WALK_IN)), ClientEnvelope.serializer(), 201).client
        val inquiry = decode(api.send("POST", "clients/${client.id}/inquiries", RequirementRequest.serializer(),
            RequirementRequest(TransactionType.RENT, PropertyCategory.BHK_2, budgetMax = 70_000, locations = listOf("Andheri"),
                furnishing = listOf(Furnishing.SEMI_FURNISHED), floorPreference = listOf(FloorBand.HIGHER), propertyTypes = listOf(PropertyType.APARTMENT))),
            InquiryEnvelope.serializer(), 201).inquiry
        assertEquals(listOf(FloorBand.HIGHER), inquiry.floorPreference)
        val forClient = api.get("inquiries/${inquiry.id}/matches", PropertyMatches.serializer()).matches
        val mine = forClient.single { it.property.id == created.id }
        assertTrue(MatchLabel.isExact(mine.checks), mine.checks.toString())
        assertEquals("Exact match", MatchLabel.of(mine.score, mine.checks))
        assertEquals("Floor 15 of 20 is a higher floor", mine.checks.single { it.field == RequirementField.FLOOR }.detail)
        // The property shows the interested client.
        assertTrue(api.get("properties/${created.id}/matches", InquiryMatches.serializer()).matches.any { it.inquiry.client?.name == "Test Match Client" })
        // The requirement list counts it.
        assertTrue((api.get("inquiries", InquiryList.serializer()).inquiries.single { it.id == inquiry.id }.matchCount ?: 0) >= 1)

        // Editing the property updates the match: a lower floor makes it partial, with the reason.
        decode(api.send("PATCH", "properties/${created.id}", PropertyRequest.serializer(),
            opened.toRequest().copy(price = 68_000, floor = 2)), PropertyEnvelope.serializer())
        val partial = api.get("inquiries/${inquiry.id}/matches", PropertyMatches.serializer()).matches.single { it.property.id == created.id }
        assertFalse(MatchLabel.isExact(partial.checks))
        assertTrue(MatchLabel.of(partial.score, partial.checks).startsWith("Partial match · "))
        assertEquals("mismatch", partial.checks.single { it.field == RequirementField.FLOOR }.outcome)
        // Unknown building height: kept, floor unverified (never guessed).
        decode(api.send("PATCH", "properties/${created.id}", PropertyRequest.serializer(),
            opened.toRequest().copy(price = 68_000, floor = 15, totalFloors = null)), PropertyEnvelope.serializer())
        val unverified = api.get("inquiries/${inquiry.id}/matches", PropertyMatches.serializer()).matches.single { it.property.id == created.id }
        assertTrue("unverified" in unverified.checks.single { it.field == RequirementField.FLOOR }.detail)
        decode(api.send("PATCH", "properties/${created.id}", PropertyRequest.serializer(),
            opened.toRequest().copy(price = 68_000)), PropertyEnvelope.serializer())

        // 6. Restart: the property, its photos, edits and the match are all still there.
        api = Phone(snapshot, today, phone.saved).api
        val again = api.get("properties/${created.id}", PropertyEnvelope.serializer()).property
        assertEquals(68_000, again.price)
        assertEquals(listOf("demophotoa", "demophotob"), again.photoIds)
        assertEquals(Availability.AVAILABLE, again.availability)
        assertTrue(api.get("inquiries/${inquiry.id}/matches", PropertyMatches.serializer()).matches.any { it.property.id == created.id })

        // Removing a match (marking it rented) deletes nothing: both records remain.
        decode(api.send("PATCH", "properties/${created.id}", PropertyRequest.serializer(),
            again.toRequest().copy(availability = Availability.RENTED)), PropertyEnvelope.serializer())
        assertFalse(api.get("inquiries/${inquiry.id}/matches", PropertyMatches.serializer()).matches.any { it.property.id == created.id })
        assertEquals(Availability.RENTED, api.get("properties/${created.id}", PropertyEnvelope.serializer()).property.availability)
        assertEquals(inquiry.id, api.get("inquiries/${inquiry.id}", InquiryEnvelope.serializer()).inquiry.id)

        // Photo removal and the photo limit.
        assertEquals(204, api.handle("DELETE", "/api/v1/properties/${created.id}/photos/demophotoa", emptyList()).status)
        assertEquals(listOf("demophotob"), api.get("properties/${created.id}", PropertyEnvelope.serializer()).property.photoIds)
        repeat(11) { assertEquals(201, api.addPhoto(created.id, "demophotox$it").status) }
        assertEquals(400, api.addPhoto(created.id, "demophotoy").status)
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

    /** Phase 2: the full client workflow in the demo, as the phone would run it. */
    @Test
    fun clientWorkflow_createProfileEditNoteFollowUpSearchRestart() {
        val phone = Phone(snapshot, today)
        var api = phone.api

        // 1. Create (with email), then a double submit and an email match are refused as duplicates.
        val c = decode(api.send("POST", "clients", CreateClientRequest.serializer(),
            CreateClientRequest("Zara Demo", "98765 00011", email = "Zara.Demo@Example.com", leadSource = LeadSource.WALK_IN, notes = "Referred by a friend")),
            ClientEnvelope.serializer(), 201).client
        assertEquals("zara.demo@example.com", c.email)
        assertEquals(409, api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Zara Demo", "9876500011", leadSource = LeadSource.WALK_IN)).status)
        val byEmail = api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Z D", "98765 00019", email = "zara.demo@example.com", leadSource = LeadSource.WALK_IN))
        assertEquals(409, byEmail.status)
        assertTrue("\"matchedOn\":\"email\"" in byEmail.body)
        // Same name, different number: a different person.
        assertEquals(201, api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Zara Demo", "98765 00012", leadSource = LeadSource.OTHER)).status)
        assertEquals(400, api.send("POST", "clients", CreateClientRequest.serializer(), CreateClientRequest("Bad email", "98765 00013", email = "not-an-email", leadSource = LeadSource.OTHER)).status)

        // Live duplicate check on the form.
        assertTrue(c.id in api.handle("GET", "/api/v1/clients/check-duplicate", listOf("phone" to "+919876500011")).body)
        assertTrue("\"duplicate\":null" in api.handle("GET", "/api/v1/clients/check-duplicate", listOf("phone" to "+919876500099")).body)

        // 2. Requirement with budget, area and BHK.
        api.send("POST", "clients/${c.id}/inquiries", RequirementRequest.serializer(),
            RequirementRequest(TransactionType.RENT, PropertyCategory.BHK_2, budgetMin = 60_000, budgetMax = 75_000, locations = listOf("Bandra West")))

        // 3. Edit: new phone and email, status.
        val edited = decode(api.send("PATCH", "clients/${c.id}", UpdateClientRequest.serializer(),
            UpdateClientRequest("Zara Demo", "zara@example.com", LeadSource.WALK_IN, ClientStatus.SITE_VISIT, "Referred by a friend", phone = "98765 00021")),
            ClientEnvelope.serializer()).client
        assertEquals("+919876500021", edited.primaryPhone)
        assertEquals(ClientStatus.SITE_VISIT, edited.status)
        // Someone else's number is refused.
        val other = api.get("clients", ClientList.serializer(), "group" to "all").clients.first { it.id != c.id && !it.id.startsWith("demo-") }
        assertEquals(409, api.send("PATCH", "clients/${c.id}", UpdateClientRequest.serializer(),
            UpdateClientRequest("Zara Demo", null, LeadSource.WALK_IN, ClientStatus.SITE_VISIT, null, phone = other.primaryPhone)).status)

        // 4. Note and 5. follow-up, then reschedule it.
        api.send("POST", "clients/${c.id}/notes", AddNoteRequest.serializer(), AddNoteRequest("Liked the Bandra flat"))
        val r = decode(api.send("POST", "reminders", CreateReminderRequest.serializer(),
            CreateReminderRequest("Call Zara", Instant.now().plusSeconds(7_200).toString(), clientId = c.id)), ReminderEnvelope.serializer(), 201).reminder
        val later = Instant.now().plusSeconds(3 * 86_400).toString()
        assertEquals(later, decode(api.send("PATCH", "reminders/${r.id}", UpdateReminderRequest.serializer(), UpdateReminderRequest(dueAt = later)), ReminderEnvelope.serializer()).reminder.dueAt)

        // Profile has everything, and only this client's.
        val profile = api.get("clients/${c.id}", ClientEnvelope.serializer()).client
        assertEquals("zara@example.com", profile.email)
        assertEquals(listOf("Bandra West"), profile.inquiries.single().locations)
        assertEquals(listOf("Call Zara"), profile.reminders.map { it.title })
        assertEquals("Liked the Bandra flat", api.get("clients/${c.id}/notes", ClientNoteList.serializer()).notes.first().body)
        assertFalse(api.get("clients/${other.id}", ClientEnvelope.serializer()).client.reminders.any { it.id == r.id })

        // 6. Search: name, new phone, email, requirement area and type, status tab.
        fun found(q: String, group: String = "all") = api.get("clients", ClientList.serializer(), "group" to group, "q" to q).clients.map { it.id }
        assertTrue(c.id in found("zara"))
        assertEquals(listOf(c.id), found("98765 00021"))
        assertTrue(found("00011").isEmpty()) // the old number is gone
        assertEquals(listOf(c.id), found("zara@"))
        assertTrue(c.id in found("bandra"))
        assertTrue(c.id in found("2 BHK"))
        assertFalse(c.id in found("3 BHK bandra"))
        assertTrue(c.id in api.get("clients", ClientList.serializer(), "group" to "active").clients.map { it.id })

        // 7. Restart: everything is still there.
        api = Phone(snapshot, today, phone.saved).api
        val again = api.get("clients/${c.id}", ClientEnvelope.serializer()).client
        assertEquals("+919876500021", again.primaryPhone)
        assertEquals(ClientStatus.SITE_VISIT, again.status)
        assertEquals(later, again.reminders.single().dueAt)
        assertEquals(1, again.inquiries.size)
        assertEquals("Liked the Bandra flat", api.get("clients/${c.id}/notes", ClientNoteList.serializer()).notes.first().body)
        assertEquals(listOf(c.id), api.get("clients", ClientList.serializer(), "group" to "all", "q" to "98765 00021").clients.map { it.id })

        // Deleting a demo client removes it (and its follow-ups) — sample clients stay protected.
        assertEquals(204, api.handle("DELETE", "/api/v1/clients/${c.id}", emptyList()).status)
        assertEquals(404, api.handle("GET", "/api/v1/clients/${c.id}", emptyList()).status)
        assertFalse(api.get("reminders", ReminderList.serializer(), "status" to "PENDING").reminders.any { it.id == r.id })
        assertEquals(403, api.handle("DELETE", "/api/v1/clients/${other.id}", emptyList()).status)
    }
}
