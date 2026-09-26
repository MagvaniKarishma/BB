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
