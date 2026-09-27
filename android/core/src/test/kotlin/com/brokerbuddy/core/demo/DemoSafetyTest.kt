package com.brokerbuddy.core.demo

import com.brokerbuddy.core.model.ApiErrorBody
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ClientList
import com.brokerbuddy.core.model.CreateClientRequest
import com.brokerbuddy.core.model.LeadSource
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The demo never pretends: a change that can't be saved is undone and reported; bad input is refused. */
class DemoSafetyTest {
    private val snapshot = File("../app/src/main/assets/demo/responses.json").readText()
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    private fun client(name: String, phone: String) = ApiJson.encodeToString(CreateClientRequest.serializer(), CreateClientRequest(name, phone, leadSource = LeadSource.WALK_IN))
    private fun names(api: DemoApi) = ApiJson.decodeFromString(ClientList.serializer(), api.handle("GET", "/api/v1/clients", listOf("group" to "all")).body).clients.map { it.name }

    @Test
    fun aFailedSaveIsUndoneAndReported() {
        var storageFull = false
        var saved: String? = null
        val api = DemoApi(snapshot, today, onChange = { if (storageFull) throw java.io.IOException("No space left") else saved = it.toJson() })
        assertEquals(201, api.handle("POST", "/api/v1/clients", emptyList(), client("Test Saved", "98765 00401")).status)

        storageFull = true
        val r = api.handle("POST", "/api/v1/clients", emptyList(), client("Test Not Saved", "98765 00402"))
        assertEquals(507, r.status)
        assertEquals("SAVE_FAILED", ApiJson.decodeFromString(ApiErrorBody.serializer(), r.body).error.code)
        // Not shown as if it were saved, and what was saved before is intact.
        assertTrue("Test Not Saved" !in names(api))
        assertTrue("Test Saved" in names(api))
        // Same for a photo: not added to the property.
        val propertyId = ApiJson.decodeFromString(com.brokerbuddy.core.model.PropertyList.serializer(), api.handle("GET", "/api/v1/properties", emptyList()).body).properties.first().id
        assertEquals(507, api.addPhoto(propertyId, "demophotofail").status)
        assertTrue("demophotofail" !in ApiJson.decodeFromString(com.brokerbuddy.core.model.PropertyEnvelope.serializer(),
            api.handle("GET", "/api/v1/properties/$propertyId", emptyList()).body).property.photoIds)

        storageFull = false
        assertEquals(201, api.handle("POST", "/api/v1/clients", emptyList(), client("Test Not Saved", "98765 00402")).status)
        assertTrue("Test Not Saved" in DemoChanges.parse(saved)!!.clients.values.map { it["name"].toString().trim('"') })
    }

    @Test
    fun damagedOrInvalidInput() {
        // A damaged saved file is recognised (the app keeps it as a backup and tells the broker).
        assertNull(DemoChanges.parse("{not json"))
        assertNull(DemoChanges.parse(null))
        assertNotNull(DemoChanges.parse(DemoChanges().toJson()))
        // Invalid requests are refused without changing anything.
        val api = DemoApi(snapshot, today)
        val before = names(api)
        assertEquals(400, api.handle("POST", "/api/v1/clients", emptyList(), "{not json").status)
        assertEquals(400, api.handle("POST", "/api/v1/clients", emptyList(), client("", "98765 00403")).status)
        assertEquals(400, api.handle("POST", "/api/v1/reminders", emptyList(), """{"title":"x","dueAt":"tomorrow"}""").status)
        assertEquals(before, names(api))
        // Unknown screens answer "not available", never made-up data.
        assertEquals(404, api.handle("GET", "/api/v1/clients/does-not-exist", emptyList()).status)
    }
}
