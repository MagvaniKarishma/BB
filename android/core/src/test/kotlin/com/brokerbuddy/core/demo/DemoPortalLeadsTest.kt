package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.AddNoteRequest
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ClientNoteList
import com.brokerbuddy.core.model.CreateReminderRequest
import com.brokerbuddy.core.model.ClientEnvelope
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.PortalLeadEnvelope
import com.brokerbuddy.core.model.PortalLeadStatus
import com.brokerbuddy.core.model.PortalListingDetail
import com.brokerbuddy.core.model.PortalListingList
import com.brokerbuddy.core.model.UpdatePortalLeadRequest
import com.brokerbuddy.core.portal.DateFilter
import com.brokerbuddy.core.portal.DateWindows
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 99acres and Housing.com leads in the demo: date filters, per-listing enquiries, status changes,
 * the two portals kept apart, Home counts, and a restart. Sample enquiries (fictional people) are
 * at fixed IST times: 99acres — 3 today (CSV) + 1 today (WhatsApp share), 1 yesterday, 1 three days
 * ago; Housing.com — 2 today (CSV) + 1 today (WhatsApp share), 1 yesterday.
 */
class DemoPortalLeadsTest {
    private val snapshot = File("../app/src/main/assets/demo/responses.json").readText()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.now(zone)
    private var saved: String? = null
    private fun phone() = DemoApi(snapshot, today, DemoChanges.fromJson(saved), onChange = { saved = it.toJson() })

    private fun <T> DemoApi.get(path: String, serializer: KSerializer<T>, vararg params: Pair<String, String?>): T {
        val r = handle("GET", "/api/v1/$path", params.toList())
        assertEquals(200, r.status, "$path: ${r.body}")
        return ApiJson.decodeFromString(serializer, r.body)
    }

    private fun window(f: DateFilter, start: LocalDate? = null, end: LocalDate? = null) =
        DateWindows.of(f, today, zone, start, end)!!.let { arrayOf("from" to it.from.toString(), "to" to it.to.toString()) }

    private fun DemoApi.listings(portal: Portal, f: DateFilter, start: LocalDate? = null, end: LocalDate? = null) =
        get("portal-leads/listings", PortalListingList.serializer(), "portal" to portal.name, *window(f, start, end)).listings

    private fun DemoApi.leadCount(portal: Portal, f: DateFilter, start: LocalDate? = null, end: LocalDate? = null) =
        listings(portal, f, start, end).sumOf { it.totalLeads }

    @Test
    fun dateFiltersReturnTheRightEnquiries() {
        val api = phone()
        assertEquals(4, api.leadCount(Portal.ACRES_99, DateFilter.TODAY))
        assertEquals(1, api.leadCount(Portal.ACRES_99, DateFilter.YESTERDAY))
        assertEquals(6, api.leadCount(Portal.ACRES_99, DateFilter.LAST_7_DAYS))
        assertEquals(3, api.leadCount(Portal.HOUSING_COM, DateFilter.TODAY))
        assertEquals(1, api.leadCount(Portal.HOUSING_COM, DateFilter.YESTERDAY))
        assertEquals(4, api.leadCount(Portal.HOUSING_COM, DateFilter.LAST_7_DAYS))
        // Custom: only the day three days ago, then a range that excludes today.
        val threeAgo = today.minusDays(3)
        assertEquals(1, api.leadCount(Portal.ACRES_99, DateFilter.CUSTOM, threeAgo, threeAgo))
        assertEquals(0, api.leadCount(Portal.HOUSING_COM, DateFilter.CUSTOM, threeAgo, threeAgo))
        assertEquals(2, api.leadCount(Portal.ACRES_99, DateFilter.CUSTOM, today.minusDays(5), today.minusDays(1)))
        assertEquals(0, api.leadCount(Portal.ACRES_99, DateFilter.CUSTOM, today.minusDays(30), today.minusDays(10)))

        // A listing's page shows only its own enquiries in the chosen window; the listing's totals are all-time.
        val powai = api.listings(Portal.ACRES_99, DateFilter.LAST_7_DAYS).single { it.locality == "Powai" && it.category?.name == "BHK_3" }
        assertEquals(2, powai.totalLeads)
        val todayOnly = api.get("portal-leads/listings/${powai.id}", PortalListingDetail.serializer(), "portal" to "ACRES_99", *window(DateFilter.TODAY))
        assertEquals(listOf("Imran Khan"), todayOnly.leads.map { it.name })
        assertEquals(2, todayOnly.totalInterestedClients)
        val week = api.get("portal-leads/listings/${powai.id}", PortalListingDetail.serializer(), "portal" to "ACRES_99", *window(DateFilter.LAST_7_DAYS))
        assertEquals(listOf("Imran Khan", "Deepak Gupta"), week.leads.map { it.name }) // newest first
        assertTrue(week.leads.all { it.listingId == powai.id && it.portal == Portal.ACRES_99 })
    }

    @Test
    fun portalsAreKeptApart() {
        val api = phone()
        val acres = api.listings(Portal.ACRES_99, DateFilter.LAST_7_DAYS)
        val housing = api.listings(Portal.HOUSING_COM, DateFilter.LAST_7_DAYS)
        assertTrue(acres.all { it.portal == Portal.ACRES_99 } && housing.all { it.portal == Portal.HOUSING_COM })
        assertTrue(acres.map { it.id }.intersect(housing.map { it.id }.toSet()).isEmpty())
        // Mulund West 1 BHK is on both portals: two separate listings, each with its own enquiries.
        val mulund99 = acres.single { it.locality == "Mulund West" }
        val mulundHousing = housing.filter { it.locality == "Mulund West" }
        assertTrue(mulundHousing.isNotEmpty() && mulundHousing.none { it.id == mulund99.id })
        val leads99 = api.get("portal-leads/listings/${mulund99.id}", PortalListingDetail.serializer(), "portal" to "ACRES_99").leads
        assertTrue(leads99.all { it.portal == Portal.ACRES_99 })
        // Home counts today's enquiries per portal, without mixing them.
        val work = api.get("dashboard", Dashboard.serializer()).todayWork!!
        assertEquals(4, work.acres99Leads)
        assertEquals(3, work.housingLeads)
    }

    @Test
    fun statusChangesShowEverywhereAndSurviveRestart() {
        var api = phone()
        val powai = api.listings(Portal.ACRES_99, DateFilter.LAST_7_DAYS).single { it.locality == "Powai" && it.category?.name == "BHK_3" }
        val imran = api.get("portal-leads/listings/${powai.id}", PortalListingDetail.serializer(), "portal" to "ACRES_99").leads.single { it.name == "Imran Khan" }
        assertEquals(PortalLeadStatus.NEW, imran.status)
        assertEquals(ClientStatus.NEW, imran.client!!.status)
        val newBefore = api.get("dashboard", Dashboard.serializer()).todayWork!!.newLeads

        val r = api.handle("PATCH", "/api/v1/portal-leads/${imran.id}", emptyList(),
            ApiJson.encodeToString(UpdatePortalLeadRequest.serializer(), UpdatePortalLeadRequest(PortalLeadStatus.CONTACTED)))
        assertEquals(200, r.status, r.body)
        val updated = ApiJson.decodeFromString(PortalLeadEnvelope.serializer(), r.body).lead
        assertEquals(PortalLeadStatus.CONTACTED, updated.status)
        assertEquals(ClientStatus.CONTACTED, updated.client!!.status) // a New client becomes Contacted

        // The listing's New count, the client's profile and Home all follow.
        assertEquals(powai.newLeads - 1, api.listings(Portal.ACRES_99, DateFilter.LAST_7_DAYS).single { it.id == powai.id }.newLeads)
        val profile = api.get("clients/${imran.clientId}", ClientEnvelope.serializer()).client
        assertEquals(ClientStatus.CONTACTED, profile.status)
        assertEquals(PortalLeadStatus.CONTACTED, profile.portalLeads.single { it.id == imran.id }.status)
        assertEquals(newBefore - 1, api.get("dashboard", Dashboard.serializer()).todayWork!!.newLeads)

        // Note and follow-up from the lead card go on the client's profile.
        val note = api.handle("POST", "/api/v1/clients/${imran.clientId}/notes", emptyList(),
            ApiJson.encodeToString(AddNoteRequest.serializer(), AddNoteRequest("Asked about Powai 3 BHK on 99acres; call after 6 pm")))
        assertEquals(201, note.status, note.body)
        val due = java.time.Instant.now().plusSeconds(3600).toString()
        val followUp = api.handle("POST", "/api/v1/reminders", emptyList(),
            ApiJson.encodeToString(CreateReminderRequest.serializer(), CreateReminderRequest("Follow up: 3 BHK Apartment for Sale", due, clientId = imran.clientId)))
        assertEquals(201, followUp.status, followUp.body)
        val withNote = api.get("clients/${imran.clientId}", ClientEnvelope.serializer()).client
        assertTrue(withNote.reminders.any { it.title == "Follow up: 3 BHK Apartment for Sale" })
        assertTrue(api.get("clients/${imran.clientId}/notes", ClientNoteList.serializer()).notes.any { "Powai 3 BHK" in it.body })

        // Invalid status and unknown lead are refused; nothing changes.
        assertEquals(400, api.handle("PATCH", "/api/v1/portal-leads/${imran.id}", emptyList(), """{"status":"MAYBE"}""").status)
        assertEquals(404, api.handle("PATCH", "/api/v1/portal-leads/nope", emptyList(), """{"status":"CLOSED"}""").status)

        // Restart.
        api = phone()
        val again = api.get("portal-leads/listings/${powai.id}", PortalListingDetail.serializer(), "portal" to "ACRES_99").leads.single { it.id == imran.id }
        assertEquals(PortalLeadStatus.CONTACTED, again.status)

        // Reset brings the sample status back.
        api.reset()
        assertEquals(PortalLeadStatus.NEW, api.get("portal-leads/listings/${powai.id}", PortalListingDetail.serializer(), "portal" to "ACRES_99")
            .leads.single { it.id == imran.id }.status)
    }

    @Test
    fun matchesTheServerWithoutChanges() {
        // With no date filter and no demo changes, the recomputed lists equal the server's.
        val api = phone()
        val root = ApiJson.parseToJsonElement(snapshot).jsonObject.getValue("responses").jsonObject
        for (portal in listOf("ACRES_99", "HOUSING_COM")) {
            val server = ApiJson.decodeFromJsonElement(PortalListingList.serializer(), root.getValue("portal-leads/listings?portal=$portal"))
            val demo = api.get("portal-leads/listings", PortalListingList.serializer(), "portal" to portal)
            assertEquals(server.listings.map { Triple(it.id, it.totalLeads, it.newLeads) }, demo.listings.map { Triple(it.id, it.totalLeads, it.newLeads) })
            assertEquals(server.listings.map { it.interestedClients }, demo.listings.map { it.interestedClients })
        }
        assertTrue(root.keys.any { it.startsWith("portal-leads/listings/") })
        assertTrue(root.getValue("dashboard") is JsonObject)
    }
}
