package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ClientEnvelope
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.CreateClientRequest
import com.brokerbuddy.core.model.CreateReminderRequest
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.LeadSource
import com.brokerbuddy.core.model.ReminderEnvelope
import com.brokerbuddy.core.model.ReminderKind
import com.brokerbuddy.core.model.ReminderList
import com.brokerbuddy.core.model.ReminderStatus
import com.brokerbuddy.core.model.UpdateClientRequest
import com.brokerbuddy.core.model.UpdateReminderRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Home in the demo: Today's Work counts and lists follow the data, in the phone's time zone. */
class DemoDashboardTest {
    private val snapshot = File("../app/src/main/assets/demo/responses.json").readText()
    private val root = ApiJson.parseToJsonElement(snapshot).jsonObject
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test
    fun sameAsTheServerAtCaptureTime() {
        val captured = LocalDate.parse(root.getValue("capturedOn").jsonPrimitive.content)
        val server = root.getValue("responses").jsonObject.getValue("dashboard").jsonObject
        val at = Instant.parse(server.getValue("generatedAt").jsonPrimitive.content)
        val api = DemoApi(snapshot, captured, now = { at })
        val demo = ApiJson.parseToJsonElement(api.handle("GET", "/api/v1/dashboard", listOf("tz" to "330")).body).jsonObject
        for (key in listOf("rent", "buy", "clientsByStatus", "reminders", "availableProperties", "totals", "todayWork", "todayFollowUps", "newLeads")) {
            assertEquals(server[key], demo[key], key)
        }
        fun top(o: JsonObject) = ApiJson.decodeFromJsonElement(Dashboard.serializer(), o).topMatches.map { it.property.id to it.matchingRequirements }
        assertEquals(top(server).toSet(), top(demo).toSet())
    }

    // 20:00 today in Mumbai (after the sample data's own times, which are earlier in the day).
    private val today = LocalDate.now(ist)
    private val now = ZonedDateTime.of(today, LocalTime.of(20, 0), ist).toInstant()
    private var saved: String? = null
    private fun phone() = DemoApi(snapshot, today, DemoChanges.fromJson(saved), now = { now }, onChange = { saved = it.toJson() })
    private fun DemoApi.home(tz: Int = 330) = ApiJson.decodeFromString(Dashboard.serializer(), handle("GET", "/api/v1/dashboard", listOf("tz" to "$tz")).body)
    private fun DemoApi.remind(title: String, due: Instant, kind: ReminderKind, clientId: String) = ApiJson.decodeFromString(
        ReminderEnvelope.serializer(),
        handle("POST", "/api/v1/reminders", emptyList(), ApiJson.encodeToString(CreateReminderRequest.serializer(), CreateReminderRequest(title, due.toString(), clientId = clientId, kind = kind))).body,
    ).reminder
    private fun at(day: LocalDate, h: Int, m: Int = 0, s: Int = 0) = ZonedDateTime.of(day, LocalTime.of(h, m, s), ist).toInstant()

    @Test
    fun newLeadsCallbacksAndFollowUps() {
        val api = phone()
        val start = api.home()
        val w0 = start.todayWork!!

        // New lead appears; contacting them removes it.
        val client = ApiJson.decodeFromString(ClientEnvelope.serializer(), api.handle("POST", "/api/v1/clients", emptyList(),
            ApiJson.encodeToString(CreateClientRequest.serializer(), CreateClientRequest("Test Dash Client", "98765 00123", leadSource = LeadSource.WALK_IN))).body).client
        assertEquals(w0.newLeads + 1, api.home().todayWork!!.newLeads)
        assertTrue(api.home().newLeads.any { it.clientId == client.id })

        // Callbacks: later today and overdue (yesterday) count; tomorrow doesn't.
        val laterToday = api.remind("Call back", at(today, 21, 30), ReminderKind.CALLBACK, client.id)
        val overdue = api.remind("Missed call back", at(today.minusDays(1), 17), ReminderKind.CALLBACK, client.id)
        api.remind("Call back tomorrow", at(today.plusDays(1), 10), ReminderKind.CALLBACK, client.id)
        assertEquals(w0.callbacks + 2, api.home().todayWork!!.callbacks)
        val listed = api.home().todayFollowUps
        assertTrue(listed.single { it.id == overdue.id }.overdue)
        assertFalse(listed.single { it.id == laterToday.id }.overdue)
        assertEquals(client.id, listed.single { it.id == overdue.id }.client?.id) // linked to the right client
        // Completing one removes it.
        api.handle("PATCH", "/api/v1/reminders/${overdue.id}", emptyList(), ApiJson.encodeToString(UpdateReminderRequest.serializer(), UpdateReminderRequest(status = ReminderStatus.DONE)))
        assertEquals(w0.callbacks + 1, api.home().todayWork!!.callbacks)
        assertTrue(ApiJson.decodeFromString(ReminderList.serializer(), api.handle("GET", "/api/v1/reminders", listOf("status" to "DONE")).body).reminders.any { it.id == overdue.id })

        // Follow-ups: created, edited (moved to tomorrow → off today's count), kept apart from callbacks.
        val fu = api.remind("Share brochure", at(today, 22), ReminderKind.FOLLOW_UP, client.id)
        assertEquals(w0.followUps + 1, api.home().todayWork!!.followUps)
        api.handle("PATCH", "/api/v1/reminders/${fu.id}", emptyList(), ApiJson.encodeToString(UpdateReminderRequest.serializer(), UpdateReminderRequest(dueAt = at(today.plusDays(1), 16).toString())))
        assertEquals(w0.followUps, api.home().todayWork!!.followUps)
        assertEquals(w0.callbacks + 1, api.home().todayWork!!.callbacks)
        assertEquals(start.totals!!.pendingFollowUps + 3, api.home().totals!!.pendingFollowUps) // later today + tomorrow's callback + the moved follow-up

        // Client status change updates New Leads.
        api.handle("PATCH", "/api/v1/clients/${client.id}", emptyList(), ApiJson.encodeToString(UpdateClientRequest.serializer(),
            UpdateClientRequest("Test Dash Client", null, LeadSource.WALK_IN, ClientStatus.CONTACTED, null)))
        assertEquals(w0.newLeads, api.home().todayWork!!.newLeads)

        // Restart keeps it all.
        assertEquals(api.home().todayWork, phone().home().todayWork)
    }

    @Test
    fun dayBoundariesAndTimeZones() {
        val api = phone()
        val client = ApiJson.decodeFromString(ClientEnvelope.serializer(), api.handle("POST", "/api/v1/clients", emptyList(),
            ApiJson.encodeToString(CreateClientRequest.serializer(), CreateClientRequest("Test Tz Client", "98765 00124", leadSource = LeadSource.WALK_IN))).body).client
        val base = api.home().todayWork!!.followUps
        api.remind("Last second of today", at(today, 23, 59, 59), ReminderKind.FOLLOW_UP, client.id)
        assertEquals(base + 1, api.home().todayWork!!.followUps)
        api.remind("First second of tomorrow", at(today.plusDays(1), 0), ReminderKind.FOLLOW_UP, client.id)
        assertEquals(base + 1, api.home().todayWork!!.followUps) // tomorrow isn't today
        // For a phone in London (UTC+1 in summer / UTC+0 in winter) 20:00 IST is the afternoon of the
        // same day, and 00:00 IST tomorrow is still "today" there.
        val london = ZoneId.of("Europe/London").rules.getOffset(now).totalSeconds / 60
        assertEquals(api.home(330).todayWork!!.followUps + 1, api.home(london).todayWork!!.followUps)
    }
}
