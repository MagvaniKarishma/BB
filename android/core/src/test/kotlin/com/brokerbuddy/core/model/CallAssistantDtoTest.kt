package com.brokerbuddy.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallAssistantDtoTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true; coerceInputValues = true }

    @Test
    fun `reads the server's settings and greetings`() {
        val body = """
            {"settings":{"brokerageId":"b1","enabled":true,"mode":"SMART_ASSISTANT","voice":"RECORDED_STANDARD",
             "customGreetingEnabled":true,"defaultLanguage":"MARATHI",
             "businessHours":{"days":[1,2,3],"start":"10:00","end":"19:00","timeZone":"Asia/Kolkata"},
             "callbackReminder":true,"callbackDelayMinutes":30,"callbackAssigneeId":null,"unclearBehavior":"TRANSFER",
             "maxUnclearRetries":2,"humanTransfer":"ON_REQUEST","transferNumber":"+919811122233",
             "businessNumbers":["+912240001234"],"updatedAt":"2026-09-26T10:00:00.000Z"},
             "greetings":[{"language":"HINGLISH","script":"Hello!","isDefaultScript":true,"hasAudio":true,
              "mimeType":"audio/wav","size":1234,"durationMs":4000,"updatedAt":null}],
             "capabilities":{"provider":null,"voices":{"RECORDED_STANDARD":{"available":true},
              "CUSTOM_AI_VOICE":{"available":false,"reason":"Needs a provider"}},"greetingFormats":["audio/wav"],"maxGreetingSeconds":60}}
        """.trimIndent()
        val info = json.decodeFromString(CallAssistantInfo.serializer(), body)
        assertEquals(CallMode.SMART_ASSISTANT, info.settings.mode)
        assertEquals(GreetingLanguage.MARATHI, info.settings.defaultLanguage)
        assertEquals(listOf(1, 2, 3), info.settings.businessHours.days)
        assertTrue(info.greetings.single().hasAudio)
        assertTrue(info.capabilities.voices.getValue(AssistantVoice.RECORDED_STANDARD).available)
        assertFalse(info.capabilities.voices.getValue(AssistantVoice.CUSTOM_AI_VOICE).available)
    }

    @Test
    fun `sends every setting so a partial form can't clear others`() {
        val out = json.encodeToString(CallAssistantSettings.serializer(), CallAssistantSettings(enabled = true))
        assertTrue(out.contains("\"businessHours\":{\"days\":[1,2,3,4,5,6]"))
        assertTrue(out.contains("\"transferNumber\":null"))
    }
}

class ClientListDtoTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `reads tab counts and the one-line requirement`() {
        val body = """
            {"total":1,"page":1,"pageSize":50,"groupCounts":{"all":4,"new":2,"active":1,"followup":1,"lost":1},
             "clients":[{"id":"c1","name":"Amit Patil","primaryPhone":"+919820011111","leadSource":"AI_CALL_ASSISTANT",
              "status":"CONTACTED","createdAt":"x","updatedAt":"x","activeInquiries":1,"followUpDue":true,
              "requirement":{"id":"i1","transactionType":"RENT","category":"BHK_2","locations":["Andheri West","Powai"],
               "budgetMin":65000,"budgetMax":75000}}]}
        """.trimIndent()
        val list = json.decodeFromString(ClientList.serializer(), body)
        assertEquals(2, list.groupCounts["new"])
        val c = list.clients.single()
        assertTrue(c.followUpDue)
        assertEquals("2 BHK • Rent • Andheri West", c.requirement?.text())
    }
}

class PropertyDtoTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `keeps every field when only availability changes`() {
        val p = json.decodeFromString(
            Property.serializer(),
            """{"id":"p1","title":"2 BHK Apartment","transactionType":"RENT","category":"BHK_2","price":75000,
               "locality":"Andheri East","building":"Oberoi Splendor","carpetAreaSqft":850,"bathrooms":2,
               "floor":5,"totalFloors":15,"availability":"AVAILABLE","possessionDate":"2027-03-01T00:00:00.000Z",
               "createdAt":"x","updatedAt":"x","photoIds":["ph1","ph2"]}""",
        )
        assertEquals(listOf("ph1", "ph2"), p.photoIds)
        assertEquals(2, p.bedrooms)
        val req = p.toRequest().copy(availability = Availability.RENTED)
        assertEquals(Availability.RENTED, req.availability)
        assertEquals(2, req.bathrooms)
        assertEquals("Oberoi Splendor", req.building)
        assertEquals("2027-03-01", req.possessionDate)
    }
}

class IndianMobileTest {
    @Test
    fun `accepts the ways people type a mobile number and rejects the rest`() {
        val p = com.brokerbuddy.core.phone.PhoneNumbers
        assertEquals("+919820012345", p.indianMobile("98200 12345"))
        assertEquals("+919820012345", p.indianMobile("098200 12345"))
        assertEquals("+919820012345", p.indianMobile("919820012345"))
        assertEquals(null, p.indianMobile("2240001234")) // landline
        assertEquals(null, p.indianMobile("98200"))
        assertEquals(null, p.indianMobile("9199999"))
    }
}
