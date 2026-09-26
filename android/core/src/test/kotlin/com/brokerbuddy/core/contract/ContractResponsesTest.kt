package com.brokerbuddy.core.contract

import com.brokerbuddy.core.model.AiCallList
import com.brokerbuddy.core.model.AssistantCommandResult
import com.brokerbuddy.core.model.InboundKeyResponse
import com.brokerbuddy.core.model.PortalCsvImportResult
import com.brokerbuddy.core.model.PortalIntegrations
import com.brokerbuddy.core.model.PortalLeadEnvelope
import com.brokerbuddy.core.model.PortalListingDetail
import com.brokerbuddy.core.model.PortalListingList
import com.brokerbuddy.core.model.PortalRecordResult
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ApplyVoiceNoteResponse
import com.brokerbuddy.core.model.AuthResponse
import com.brokerbuddy.core.model.CallAssistantInfo
import com.brokerbuddy.core.model.CallAssistantSettingsResponse
import com.brokerbuddy.core.model.CallerDirectory
import com.brokerbuddy.core.model.CallerLookup
import com.brokerbuddy.core.model.ClientEnvelope
import com.brokerbuddy.core.model.ClientList
import com.brokerbuddy.core.model.ClientNoteEnvelope
import com.brokerbuddy.core.model.ClientNoteList
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.DuplicateCheck
import com.brokerbuddy.core.model.GreetingResponse
import com.brokerbuddy.core.model.ImportChatResponse
import com.brokerbuddy.core.model.InquiryEnvelope
import com.brokerbuddy.core.model.InquiryList
import com.brokerbuddy.core.model.InquiryMatches
import com.brokerbuddy.core.model.InquiryUpdateResponse
import com.brokerbuddy.core.model.MeResponse
import com.brokerbuddy.core.model.MemberEnvelope
import com.brokerbuddy.core.model.NullableClientEnvelope
import com.brokerbuddy.core.model.OtpSent
import com.brokerbuddy.core.model.OtpStatus
import com.brokerbuddy.core.model.PropertyEnvelope
import com.brokerbuddy.core.model.PropertyList
import com.brokerbuddy.core.model.PropertyMatches
import com.brokerbuddy.core.model.ReminderEnvelope
import com.brokerbuddy.core.model.ReminderList
import com.brokerbuddy.core.model.RevisionList
import com.brokerbuddy.core.model.TeamList
import com.brokerbuddy.core.model.TestCallResponse
import com.brokerbuddy.core.model.UserEnvelope
import com.brokerbuddy.core.model.VoiceNoteEnvelope
import com.brokerbuddy.core.model.VoiceNoteList
import com.brokerbuddy.core.model.VoiceNoteResponse
import com.brokerbuddy.core.model.WaAccountEnvelope
import com.brokerbuddy.core.model.WaAccountStatus
import com.brokerbuddy.core.model.WaApplyResponse
import com.brokerbuddy.core.model.WaConversation
import com.brokerbuddy.core.model.WaInbox
import com.brokerbuddy.core.model.WaMessageDetail
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Decodes every real server response captured by the backend's contract test
 * (WRITE_CONTRACT=1 npx vitest run test/contract.test.ts) with the model the app uses for
 * that endpoint.
 *
 * Twice: with the app's own settings (must work), and strictly — the app coerces unknown
 * enum values and nulls to defaults, which would hide a mismatch on the phone.
 */
class ContractResponsesTest {
    private val dir = File("src/test/resources/contract/responses")
    private val strict = Json { ignoreUnknownKeys = true; coerceInputValues = false }

    private val models: Map<String, KSerializer<*>> = mapOf(
        "auth-register" to AuthResponse.serializer(),
        "auth-register-no-phone" to AuthResponse.serializer(),
        "auth-login" to AuthResponse.serializer(),
        "auth-otp" to OtpStatus.serializer(),
        "auth-otp-request" to OtpSent.serializer(),
        "auth-otp-verify" to AuthResponse.serializer(),
        "auth-phone-request" to OtpSent.serializer(),
        "auth-phone-verify" to UserEnvelope.serializer(),
        "auth-me" to MeResponse.serializer(),
        "team-add" to MemberEnvelope.serializer(),
        "team-add-no-phone" to MemberEnvelope.serializer(),
        "team-update" to MemberEnvelope.serializer(),
        "team-list" to TeamList.serializer(),
        "client-check-duplicate" to DuplicateCheck.serializer(),
        "client-create" to ClientEnvelope.serializer(),
        "client-update" to ClientEnvelope.serializer(),
        "client-get" to ClientEnvelope.serializer(),
        "clients-list" to ClientList.serializer(),
        "client-lookup" to NullableClientEnvelope.serializer(),
        "inquiry-create" to InquiryEnvelope.serializer(),
        "inquiry-update" to InquiryUpdateResponse.serializer(),
        "inquiry-get" to InquiryEnvelope.serializer(),
        "inquiries-list" to InquiryList.serializer(),
        "inquiry-history" to RevisionList.serializer(),
        "inquiry-matches" to PropertyMatches.serializer(),
        "property-create" to PropertyEnvelope.serializer(),
        "property-update" to PropertyEnvelope.serializer(),
        "property-photo-upload" to PropertyEnvelope.serializer(),
        "property-get" to PropertyEnvelope.serializer(),
        "properties-list" to PropertyList.serializer(),
        "property-matches" to InquiryMatches.serializer(),
        "reminder-create" to ReminderEnvelope.serializer(),
        "reminders-list" to ReminderList.serializer(),
        "reminder-snooze" to ReminderEnvelope.serializer(),
        "reminder-update" to ReminderEnvelope.serializer(),
        "note-add" to ClientNoteEnvelope.serializer(),
        "notes-list" to ClientNoteList.serializer(),
        "caller-lookup-known" to CallerLookup.serializer(),
        "caller-lookup-unknown" to CallerLookup.serializer(),
        "caller-directory" to CallerDirectory.serializer(),
        "dashboard" to Dashboard.serializer(),
        "voice-text" to VoiceNoteResponse.serializer(),
        "voice-transcript" to VoiceNoteResponse.serializer(),
        "voice-get" to VoiceNoteResponse.serializer(),
        "voice-apply" to ApplyVoiceNoteResponse.serializer(),
        "voice-upload" to VoiceNoteResponse.serializer(),
        "voice-discard" to VoiceNoteEnvelope.serializer(),
        "voice-list" to VoiceNoteList.serializer(),
        "whatsapp-account" to WaAccountStatus.serializer(),
        "whatsapp-connect" to WaAccountEnvelope.serializer(),
        "whatsapp-import" to WaMessageDetail.serializer(),
        "whatsapp-message" to WaMessageDetail.serializer(),
        "whatsapp-inbox" to WaInbox.serializer(),
        "whatsapp-create-client" to WaMessageDetail.serializer(),
        "whatsapp-extract" to WaMessageDetail.serializer(),
        "whatsapp-link-inquiry" to WaMessageDetail.serializer(),
        "whatsapp-apply" to WaApplyResponse.serializer(),
        "whatsapp-link-client" to WaMessageDetail.serializer(),
        "whatsapp-dismiss" to WaMessageDetail.serializer(),
        "whatsapp-import-chat" to ImportChatResponse.serializer(),
        "whatsapp-conversation" to WaConversation.serializer(),
        "call-assistant-settings" to CallAssistantSettingsResponse.serializer(),
        "greeting-script" to GreetingResponse.serializer(),
        "greeting-audio-upload" to GreetingResponse.serializer(),
        "call-assistant" to CallAssistantInfo.serializer(),
        "test-call-start" to TestCallResponse.serializer(),
        "test-call-turn" to TestCallResponse.serializer(),
        "ai-calls" to AiCallList.serializer(),
        "reminder-callback" to ReminderEnvelope.serializer(),
        "reminders-callbacks" to ReminderList.serializer(),
        "portal-import-csv" to PortalCsvImportResult.serializer(),
        "portal-import-text" to PortalRecordResult.serializer(),
        "portal-listings" to PortalListingList.serializer(),
        "portal-listing" to PortalListingDetail.serializer(),
        "portal-listing-unidentified" to PortalListingDetail.serializer(),
        "portal-lead-status" to PortalLeadEnvelope.serializer(),
        "portal-integrations" to PortalIntegrations.serializer(),
        "portal-inbound-key" to InboundKeyResponse.serializer(),
        "client-with-portal-leads" to ClientEnvelope.serializer(),
        "assistant-command" to AssistantCommandResult.serializer(),
    )

    @Test
    fun `every captured server response decodes with the app's model`() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.associateBy { it.name.removeSuffix(".json") }.orEmpty()
        val problems = mutableListOf<String>()
        for ((name, serializer) in models) {
            val file = files[name]
            if (file == null) {
                problems += "$name: no captured response"
                continue
            }
            val text = file.readText()
            runCatching { ApiJson.decodeFromString(serializer, text) }.onFailure { problems += "$name (app settings): ${it.message}" }
            runCatching { strict.decodeFromString(serializer, text) }.onFailure { problems += "$name (strict): ${it.message}" }
        }
        (files.keys - models.keys).forEach { problems += "$it: response captured but no model mapped" }
        if (problems.isNotEmpty()) fail("API contract mismatches:\n" + problems.joinToString("\n"))
    }
}
