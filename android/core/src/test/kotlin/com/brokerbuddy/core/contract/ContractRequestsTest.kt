package com.brokerbuddy.core.contract

import com.brokerbuddy.core.model.AddNoteRequest
import com.brokerbuddy.core.model.AssistantCommandRequest
import com.brokerbuddy.core.model.ExtractRequest
import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.PortalCsvImportRequest
import com.brokerbuddy.core.model.PortalLeadStatus
import com.brokerbuddy.core.model.PortalTextImportRequest
import com.brokerbuddy.core.model.ReminderKind
import com.brokerbuddy.core.model.UpdatePortalLeadRequest
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ApplyVoiceNoteRequest
import com.brokerbuddy.core.model.Availability
import com.brokerbuddy.core.model.BusinessHours
import com.brokerbuddy.core.model.CallAssistantSettings
import com.brokerbuddy.core.model.CallMode
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.ConnectWhatsAppRequest
import com.brokerbuddy.core.model.CreateClientFromMessage
import com.brokerbuddy.core.model.CreateClientRequest
import com.brokerbuddy.core.model.CreateMemberRequest
import com.brokerbuddy.core.model.CreateReminderRequest
import com.brokerbuddy.core.model.FloorBand
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.PropertyType
import com.brokerbuddy.core.model.GreetingLanguage
import com.brokerbuddy.core.model.GreetingScriptRequest
import com.brokerbuddy.core.model.ImportChatRequest
import com.brokerbuddy.core.model.ImportMessageRequest
import com.brokerbuddy.core.model.LeadSource
import com.brokerbuddy.core.model.LinkClientRequest
import com.brokerbuddy.core.model.LinkInquiryRequest
import com.brokerbuddy.core.model.LoginRequest
import com.brokerbuddy.core.model.NoteSource
import com.brokerbuddy.core.model.OtpRequest
import com.brokerbuddy.core.model.OtpVerifyRequest
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.PropertyRequest
import com.brokerbuddy.core.model.RegisterRequest
import com.brokerbuddy.core.model.ReminderStatus
import com.brokerbuddy.core.model.RequirementField
import com.brokerbuddy.core.model.RequirementRequest
import com.brokerbuddy.core.model.RequirementSource
import com.brokerbuddy.core.model.TestCallRequest
import com.brokerbuddy.core.model.TestTurnRequest
import com.brokerbuddy.core.model.TextVoiceNoteRequest
import com.brokerbuddy.core.model.TranscriptRequest
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.model.UpdateClientRequest
import com.brokerbuddy.core.model.UpdateMemberRequest
import com.brokerbuddy.core.model.UpdateReminderRequest
import com.brokerbuddy.core.model.VoiceLanguage
import kotlinx.serialization.KSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The request bodies the app sends, serialised exactly as the app does (every field,
 * explicit nulls). The backend's contract test sends these files to the real API, so a
 * model change that the server would reject is caught.
 *
 * "{{name}}" placeholders are filled in by the backend test (ids created during the run).
 * To regenerate after changing a model: UPDATE_CONTRACT=1 ./gradlew :core:test
 */
class ContractRequestsTest {
    private val dir = File("src/test/resources/contract/requests")

    private fun <T> entry(name: String, serializer: KSerializer<T>, value: T) = name to ApiJson.encodeToString(serializer, value)

    private val requirement = RequirementRequest(
        transactionType = TransactionType.RENT, category = PropertyCategory.BHK_2, budgetMin = 65_000, budgetMax = 75_000,
        locations = listOf("Andheri West"), furnishing = listOf(Furnishing.SEMI_FURNISHED), minParking = 1, floorPreference = listOf(FloorBand.MIDDLE, FloorBand.HIGHER), propertyTypes = listOf(PropertyType.APARTMENT),
        possession = Possession.READY_TO_MOVE, mandatory = listOf(RequirementField.BUDGET), notes = "Gated society",
        source = RequirementSource.MANUAL,
    )
    private val property = PropertyRequest(
        title = "2 BHK Apartment", transactionType = TransactionType.RENT, category = PropertyCategory.BHK_2, price = 70_000,
        deposit = 200_000, locality = "Andheri West", building = "Oberoi Splendor", carpetAreaSqft = 850, bathrooms = 2,
        propertyType = PropertyType.APARTMENT, builtUpAreaSqft = 1000, amenities = listOf("Lift", "Gym"),
        furnishing = Furnishing.SEMI_FURNISHED, parkingSpots = 1, floor = 5, totalFloors = 15, possession = Possession.READY_TO_MOVE,
        availability = Availability.AVAILABLE, ownerName = "Mr Mehta", ownerPhone = "98111 22233",
    )

    private val samples: List<Pair<String, String>> = listOf(
        entry("auth-register", RegisterRequest.serializer(), RegisterRequest("Sharma Realty", "Rahul Sharma", "{{email}}", "password123", "+919820000001")),
        entry("auth-register-no-phone", RegisterRequest.serializer(), RegisterRequest("Mehta Estates", "Kiran Mehta", "{{email2}}", "password123")),
        entry("auth-login", LoginRequest.serializer(), LoginRequest("{{email}}", "password123")),
        entry("auth-otp-request", OtpRequest.serializer(), OtpRequest("+919820000001")),
        entry("auth-otp-verify", OtpVerifyRequest.serializer(), OtpVerifyRequest("+919820000001", "{{otpCode}}")),
        entry("auth-phone-request", OtpRequest.serializer(), OtpRequest("+919820000002")),
        entry("auth-phone-verify", OtpVerifyRequest.serializer(), OtpVerifyRequest("+919820000002", "{{otpCode}}")),
        entry("client-create", CreateClientRequest.serializer(), CreateClientRequest("Amit Patil", "98765 43210", leadSource = LeadSource.ACRES_99)),
        entry("client-update", UpdateClientRequest.serializer(), UpdateClientRequest("Amit Patil", null, LeadSource.ACRES_99, ClientStatus.CONTACTED, null)),
        entry("inquiry-create", RequirementRequest.serializer(), requirement),
        entry("inquiry-update", RequirementRequest.serializer(), requirement.copy(budgetMax = 80_000)),
        entry("property-create", PropertyRequest.serializer(), property),
        entry("property-update", PropertyRequest.serializer(), property.copy(availability = Availability.AVAILABLE, price = 72_000)),
        entry("reminder-create", CreateReminderRequest.serializer(), CreateReminderRequest("Discuss 2 BHK options", "{{dueAt}}", clientId = "{{clientId}}")),
        entry("reminder-snooze", UpdateReminderRequest.serializer(), UpdateReminderRequest(dueAt = "{{dueAt}}")),
        entry("reminder-update", UpdateReminderRequest.serializer(), UpdateReminderRequest(status = ReminderStatus.DONE)),
        entry("team-add", CreateMemberRequest.serializer(), CreateMemberRequest("Priya Agent", "{{memberEmail}}", "password123", phone = "+919820000003")),
        entry("team-add-no-phone", CreateMemberRequest.serializer(), CreateMemberRequest("Sam Agent", "{{memberEmail2}}", "password123")),
        entry("team-update", UpdateMemberRequest.serializer(), UpdateMemberRequest(active = false)),
        entry("voice-text", TextVoiceNoteRequest.serializer(), TextVoiceNoteRequest("{{clientId}}", "Rahul ko 2 BHK chahiye Andheri West mein, budget 70 hazaar", VoiceLanguage.HINGLISH)),
        entry("voice-transcript", TranscriptRequest.serializer(), TranscriptRequest("Rahul ko 2 BHK chahiye Andheri West mein, budget 75 hazaar")),
        entry("voice-apply", ApplyVoiceNoteRequest.serializer(), ApplyVoiceNoteRequest("{{inquiryId}}", requirement.copy(source = RequirementSource.VOICE_NOTE))),
        entry("note-add", AddNoteRequest.serializer(), AddNoteRequest("Wants to visit on Sunday", NoteSource.CALLER_SCREEN)),
        entry("whatsapp-import", ImportMessageRequest.serializer(), ImportMessageRequest("Hi, I came across your 1 BHK Apartment listed at Housing.com for ₹ 27,000 in Mulund West, Mumbai. Please let me know if it is available.")),
        entry("whatsapp-create-client", CreateClientFromMessage.serializer(), CreateClientFromMessage(name = "Sneha Kulkarni", phone = "98200 55555")),
        entry("whatsapp-link-client", LinkClientRequest.serializer(), LinkClientRequest("{{clientId}}")),
        entry("whatsapp-link-inquiry", LinkInquiryRequest.serializer(), LinkInquiryRequest(null)),
        entry("whatsapp-apply", ApplyVoiceNoteRequest.serializer(), ApplyVoiceNoteRequest(null, requirement.copy(category = PropertyCategory.BHK_1, locations = listOf("Mulund West"), source = RequirementSource.WHATSAPP))),
        entry("whatsapp-import-chat", ImportChatRequest.serializer(), ImportChatRequest("{{clientId}}", "Amit Patil", "26/09/2026, 10:15 am - Amit Patil: 2 BHK chahiye Andheri West mein\n26/09/2026, 10:16 am - Rahul: Budget?\n26/09/2026, 10:17 am - Amit Patil: 70k tak")),
        entry("whatsapp-connect", ConnectWhatsAppRequest.serializer(), ConnectWhatsAppRequest("123456789012345", "EAAG-test-access-token-0123456789", "test-app-secret-123456")),
        entry("call-assistant-settings", CallAssistantSettings.serializer(), CallAssistantSettings(enabled = true, mode = CallMode.AI_RECEPTIONIST, customGreetingEnabled = true, defaultLanguage = GreetingLanguage.HINGLISH, businessHours = BusinessHours(), transferNumber = "98111 22233", businessNumbers = listOf("022 4000 1234"))),
        entry("greeting-script", GreetingScriptRequest.serializer(), GreetingScriptRequest("Hello! Main Sharma Realty ki AI assistant hoon. Boliye, aapko kaisa flat chahiye?")),
        entry("test-call-start", TestCallRequest.serializer(), TestCallRequest("+919800000000")),
        entry("test-call-turn", TestTurnRequest.serializer(), TestTurnRequest("Mujhe Powai mein 2 BHK rent pe chahiye")),
        entry("reminder-callback", CreateReminderRequest.serializer(), CreateReminderRequest("Call back Amit", "{{dueAt}}", clientId = "{{clientId}}", kind = ReminderKind.CALLBACK)),
        entry("portal-import-csv", PortalCsvImportRequest.serializer(), PortalCsvImportRequest(
            Portal.ACRES_99,
            "Lead ID,Name,Mobile,Email,Enquiry Date,Message,Property ID,Property Title,Locality,Price\n" +
                "L-1,Amit Patil,98765 43210,,{{csvDate}},Is it available?,A12345678,2 BHK Apartment for Rent,Andheri West,\"70,000\"\n",
            "leads.csv",
        )),
        entry("portal-import-text", PortalTextImportRequest.serializer(), PortalTextImportRequest(
            "You have a new lead on Housing.com. Name: Neha Rao, Mobile: 9820066666, Property: 1 BHK Apartment in Mulund West, Price: ₹ 27,000",
            Portal.HOUSING_COM,
        )),
        entry("portal-lead-status", UpdatePortalLeadRequest.serializer(), UpdatePortalLeadRequest(PortalLeadStatus.CONTACTED)),
        entry("voice-extract", ExtractRequest.serializer(), ExtractRequest("2 BHK rent pe chahiye Andheri West mein, budget 60 se 70 hazaar", VoiceLanguage.AUTO)),
        entry("assistant-command", AssistantCommandRequest.serializer(), AssistantCommandRequest("Show today's 99acres leads", 330)),
    )

    @Test
    fun `request files match what the app sends`() {
        val update = System.getenv("UPDATE_CONTRACT") == "1"
        dir.mkdirs()
        val stale = mutableListOf<String>()
        for ((name, json) in samples) {
            val file = File(dir, "$name.json")
            val pretty = ApiJson.parseToJsonElement(json).toString()
            if (update) file.writeText(pretty + "\n")
            else if (!file.exists() || file.readText().trim() != pretty) stale += name
        }
        val known = samples.map { "${it.first}.json" }.toSet()
        val extra = dir.listFiles()?.map { it.name }?.filter { it !in known }.orEmpty()
        if (stale.isNotEmpty() || extra.isNotEmpty()) {
            fail("Contract request files are out of date (run UPDATE_CONTRACT=1 ./gradlew :core:test): stale=$stale extra=$extra")
        }
        assertEquals(samples.size, known.size, "duplicate sample names")
    }
}
