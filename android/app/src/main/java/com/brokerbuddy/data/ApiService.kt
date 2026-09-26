package com.brokerbuddy.data

import com.brokerbuddy.core.model.AiCallList
import com.brokerbuddy.core.model.AuthResponse
import com.brokerbuddy.core.model.CallAssistantInfo
import com.brokerbuddy.core.model.CallAssistantSettings
import com.brokerbuddy.core.model.CallAssistantSettingsResponse
import com.brokerbuddy.core.model.GreetingResponse
import com.brokerbuddy.core.model.GreetingScriptRequest
import com.brokerbuddy.core.model.TestCallRequest
import com.brokerbuddy.core.model.TestCallResponse
import com.brokerbuddy.core.model.TestTurnRequest
import com.brokerbuddy.core.model.Availability
import com.brokerbuddy.core.model.ClientEnvelope
import com.brokerbuddy.core.model.ClientList
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.CreateClientRequest
import com.brokerbuddy.core.model.CreateMemberRequest
import com.brokerbuddy.core.model.CreateReminderRequest
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.DuplicateCheck
import com.brokerbuddy.core.model.InquiryEnvelope
import com.brokerbuddy.core.model.InquiryList
import com.brokerbuddy.core.model.InquiryMatches
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.InquiryUpdateResponse
import com.brokerbuddy.core.model.LoginRequest
import com.brokerbuddy.core.model.OtpRequest
import com.brokerbuddy.core.model.OtpSent
import com.brokerbuddy.core.model.OtpStatus
import com.brokerbuddy.core.model.OtpVerifyRequest
import com.brokerbuddy.core.model.UserEnvelope
import com.brokerbuddy.core.model.MeResponse
import com.brokerbuddy.core.model.MemberEnvelope
import com.brokerbuddy.core.model.NullableClientEnvelope
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.PropertyEnvelope
import com.brokerbuddy.core.model.PropertyList
import com.brokerbuddy.core.model.PropertyMatches
import com.brokerbuddy.core.model.PropertyRequest
import com.brokerbuddy.core.model.RegisterRequest
import com.brokerbuddy.core.model.ReminderEnvelope
import com.brokerbuddy.core.model.ReminderList
import com.brokerbuddy.core.model.ReminderStatus
import com.brokerbuddy.core.model.RequirementRequest
import com.brokerbuddy.core.model.RevisionList
import com.brokerbuddy.core.model.TeamList
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.model.UpdateClientRequest
import com.brokerbuddy.core.model.UpdateMemberRequest
import com.brokerbuddy.core.model.UpdateReminderRequest
import com.brokerbuddy.core.model.AddNoteRequest
import com.brokerbuddy.core.model.ConnectWhatsAppRequest
import com.brokerbuddy.core.model.CreateClientFromMessage
import com.brokerbuddy.core.model.ImportChatRequest
import com.brokerbuddy.core.model.ImportChatResponse
import com.brokerbuddy.core.model.ImportMessageRequest
import com.brokerbuddy.core.model.LinkClientRequest
import com.brokerbuddy.core.model.LinkInquiryRequest
import com.brokerbuddy.core.model.SendMessageRequest
import com.brokerbuddy.core.model.WaAccountEnvelope
import com.brokerbuddy.core.model.WaAccountStatus
import com.brokerbuddy.core.model.WaApplyResponse
import com.brokerbuddy.core.model.WaConversation
import com.brokerbuddy.core.model.WaInbox
import com.brokerbuddy.core.model.WaMessageDetail
import com.brokerbuddy.core.model.WaSendResponse
import com.brokerbuddy.core.model.CallerDirectory
import com.brokerbuddy.core.model.CallerLookup
import com.brokerbuddy.core.model.ClientNoteEnvelope
import com.brokerbuddy.core.model.ClientNoteList
import com.brokerbuddy.core.model.ApplyVoiceNoteRequest
import com.brokerbuddy.core.model.ApplyVoiceNoteResponse
import com.brokerbuddy.core.model.TextVoiceNoteRequest
import com.brokerbuddy.core.model.TranscriptRequest
import com.brokerbuddy.core.model.VoiceNoteEnvelope
import com.brokerbuddy.core.model.VoiceNoteList
import com.brokerbuddy.core.model.VoiceNoteResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** BrokerBuddy REST API, relative to `<server>/api/v1/`. */
interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @GET("auth/otp")
    suspend fun otpStatus(): OtpStatus

    @POST("auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequest): OtpSent

    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyRequest): AuthResponse

    @POST("auth/phone/request")
    suspend fun requestPhoneOtp(@Body body: OtpRequest): OtpSent

    @POST("auth/phone/verify")
    suspend fun verifyPhoneOtp(@Body body: OtpVerifyRequest): UserEnvelope

    @GET("auth/me")
    suspend fun me(): MeResponse

    /** @param tz device UTC offset in minutes, so "today" is the agent's today. */
    @GET("dashboard")
    suspend fun dashboard(@Query("tz") tz: Int): Dashboard

    // Clients
    @GET("clients")
    suspend fun clients(
        @Query("q") q: String? = null,
        @Query("status") status: ClientStatus? = null,
        /** all | new | active | followup | lost */
        @Query("group") group: String? = null,
        /** Minutes east of UTC, for "follow-up due today". */
        @Query("tz") tz: Int? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): ClientList

    @GET("clients/check-duplicate")
    suspend fun checkDuplicate(@Query("phone") phone: String): DuplicateCheck

    @GET("clients/lookup")
    suspend fun lookupCaller(@Query("phone") phone: String): NullableClientEnvelope

    @POST("clients")
    suspend fun createClient(@Body body: CreateClientRequest): ClientEnvelope

    @GET("clients/{id}")
    suspend fun client(@Path("id") id: String): ClientEnvelope

    @PATCH("clients/{id}")
    suspend fun updateClient(@Path("id") id: String, @Body body: UpdateClientRequest): ClientEnvelope

    @DELETE("clients/{id}")
    suspend fun deleteClient(@Path("id") id: String): Response<Unit>

    // Inquiries (client requirements)
    @POST("clients/{id}/inquiries")
    suspend fun createInquiry(@Path("id") clientId: String, @Body body: RequirementRequest): InquiryEnvelope

    @GET("inquiries")
    suspend fun inquiries(
        @Query("q") q: String? = null,
        @Query("transactionType") transactionType: TransactionType? = null,
        @Query("category") category: PropertyCategory? = null,
        @Query("status") status: InquiryStatus? = null,
    ): InquiryList

    @GET("inquiries/{id}")
    suspend fun inquiry(@Path("id") id: String): InquiryEnvelope

    @PATCH("inquiries/{id}")
    suspend fun updateInquiry(@Path("id") id: String, @Body body: RequirementRequest): InquiryUpdateResponse

    @GET("inquiries/{id}/history")
    suspend fun inquiryHistory(@Path("id") id: String): RevisionList

    @GET("inquiries/{id}/matches")
    suspend fun inquiryMatches(@Path("id") id: String): PropertyMatches

    // Properties (inventory)
    @GET("properties")
    suspend fun properties(
        @Query("q") q: String? = null,
        @Query("transactionType") transactionType: TransactionType? = null,
        @Query("availability") availability: Availability? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): PropertyList

    @POST("properties")
    suspend fun createProperty(@Body body: PropertyRequest): PropertyEnvelope

    @GET("properties/{id}")
    suspend fun property(@Path("id") id: String): PropertyEnvelope

    @PATCH("properties/{id}")
    suspend fun updateProperty(@Path("id") id: String, @Body body: PropertyRequest): PropertyEnvelope

    @DELETE("properties/{id}")
    suspend fun deleteProperty(@Path("id") id: String): Response<Unit>

    @GET("properties/{id}/photos/{photoId}")
    suspend fun propertyPhoto(@Path("id") id: String, @Path("photoId") photoId: String): ResponseBody

    @Multipart
    @POST("properties/{id}/photos")
    suspend fun uploadPropertyPhoto(@Path("id") id: String, @Part photo: MultipartBody.Part): PropertyEnvelope

    @DELETE("properties/{id}/photos/{photoId}")
    suspend fun deletePropertyPhoto(@Path("id") id: String, @Path("photoId") photoId: String): Response<Unit>

    @GET("properties/{id}/matches")
    suspend fun propertyMatches(@Path("id") id: String): InquiryMatches

    // Reminders
    @GET("reminders")
    suspend fun reminders(
        @Query("status") status: ReminderStatus? = null,
        @Query("clientId") clientId: String? = null,
        @Query("kind") kind: com.brokerbuddy.core.model.ReminderKind? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): ReminderList

    @POST("reminders")
    suspend fun createReminder(@Body body: CreateReminderRequest): ReminderEnvelope

    @PATCH("reminders/{id}")
    suspend fun updateReminder(@Path("id") id: String, @Body body: UpdateReminderRequest): ReminderEnvelope

    // Team
    @GET("team")
    suspend fun team(): TeamList

    @POST("team")
    suspend fun addMember(@Body body: CreateMemberRequest): MemberEnvelope

    @PATCH("team/{id}")
    suspend fun updateMember(@Path("id") id: String, @Body body: UpdateMemberRequest): MemberEnvelope

    // Voice notes (Phase 2)
    @Multipart
    @POST("voice-notes")
    suspend fun uploadVoiceNote(
        @Part("clientId") clientId: RequestBody,
        @Part("inquiryId") inquiryId: RequestBody?,
        @Part("language") language: RequestBody,
        @Part("durationMs") durationMs: RequestBody,
        @Part audio: MultipartBody.Part,
    ): VoiceNoteResponse

    @POST("voice-notes/text")
    suspend fun createTextVoiceNote(@Body body: TextVoiceNoteRequest): VoiceNoteResponse

    @GET("voice-notes")
    suspend fun voiceNotes(@Query("clientId") clientId: String? = null): VoiceNoteList

    @GET("voice-notes/{id}/audio")
    suspend fun voiceNoteAudio(@Path("id") id: String): ResponseBody

    @GET("voice-notes/{id}")
    suspend fun voiceNote(@Path("id") id: String): VoiceNoteResponse

    @PUT("voice-notes/{id}/transcript")
    suspend fun updateTranscript(@Path("id") id: String, @Body body: TranscriptRequest): VoiceNoteResponse

    @POST("voice-notes/{id}/retry")
    suspend fun retryTranscription(@Path("id") id: String): VoiceNoteResponse

    @POST("voice-notes/{id}/apply")
    suspend fun applyVoiceNote(@Path("id") id: String, @Body body: ApplyVoiceNoteRequest): ApplyVoiceNoteResponse

    @POST("voice-notes/{id}/discard")
    suspend fun discardVoiceNote(@Path("id") id: String): VoiceNoteEnvelope

    // Caller screen (Phase 3)
    @GET("caller/lookup")
    suspend fun callerLookup(@Query("phone") phone: String): CallerLookup

    @GET("caller/directory")
    suspend fun callerDirectory(): CallerDirectory

    @GET("clients/{id}/notes")
    suspend fun clientNotes(@Path("id") clientId: String): ClientNoteList

    @POST("clients/{id}/notes")
    suspend fun addClientNote(@Path("id") clientId: String, @Body body: AddNoteRequest): ClientNoteEnvelope

    // WhatsApp (Phase 4)
    @GET("whatsapp/inbox")
    suspend fun whatsappInbox(@Query("filter") filter: String = "attention"): WaInbox

    @GET("whatsapp/clients/{id}/messages")
    suspend fun whatsappConversation(@Path("id") clientId: String): WaConversation

    @GET("whatsapp/messages/{id}")
    suspend fun whatsappMessage(@Path("id") id: String): WaMessageDetail

    @POST("whatsapp/messages/{id}/create-client")
    suspend fun whatsappCreateClient(@Path("id") id: String, @Body body: CreateClientFromMessage): WaMessageDetail

    @POST("whatsapp/messages/{id}/link-client")
    suspend fun whatsappLinkClient(@Path("id") id: String, @Body body: LinkClientRequest): WaMessageDetail

    @PATCH("whatsapp/messages/{id}")
    suspend fun whatsappLinkInquiry(@Path("id") id: String, @Body body: LinkInquiryRequest): WaMessageDetail

    @POST("whatsapp/messages/{id}/extract")
    suspend fun whatsappExtract(@Path("id") id: String): WaMessageDetail

    @POST("whatsapp/messages/{id}/apply")
    suspend fun whatsappApply(@Path("id") id: String, @Body body: ApplyVoiceNoteRequest): WaApplyResponse

    @POST("whatsapp/messages/{id}/dismiss")
    suspend fun whatsappDismiss(@Path("id") id: String): WaMessageDetail

    @POST("whatsapp/import")
    suspend fun whatsappImport(@Body body: ImportMessageRequest): WaMessageDetail

    @POST("whatsapp/import-chat")
    suspend fun whatsappImportChat(@Body body: ImportChatRequest): ImportChatResponse

    @POST("whatsapp/send")
    suspend fun whatsappSend(@Body body: SendMessageRequest): WaSendResponse

    @GET("whatsapp/account")
    suspend fun whatsappAccount(): WaAccountStatus

    @PUT("whatsapp/account")
    suspend fun connectWhatsapp(@Body body: ConnectWhatsAppRequest): WaAccountEnvelope

    // ---------- AI Call Assistant ----------

    @GET("call-assistant")
    suspend fun callAssistant(): CallAssistantInfo

    @PUT("call-assistant/settings")
    suspend fun updateCallAssistant(@Body body: CallAssistantSettings): CallAssistantSettingsResponse

    @PUT("call-assistant/greetings/{language}")
    suspend fun updateGreetingScript(@Path("language") language: String, @Body body: GreetingScriptRequest): GreetingResponse

    @DELETE("call-assistant/greetings/{language}")
    suspend fun resetGreeting(@Path("language") language: String): Response<Unit>

    @Multipart
    @POST("call-assistant/greetings/{language}/audio")
    suspend fun uploadGreetingAudio(
        @Path("language") language: String,
        @Part("durationMs") durationMs: RequestBody?,
        @Part audio: MultipartBody.Part,
    ): GreetingResponse

    @GET("call-assistant/greetings/{language}/audio")
    suspend fun greetingAudio(@Path("language") language: String): ResponseBody

    @DELETE("call-assistant/greetings/{language}/audio")
    suspend fun deleteGreetingAudio(@Path("language") language: String): Response<Unit>

    @GET("call-assistant/calls")
    suspend fun aiCalls(): AiCallList

    @POST("call-assistant/test-calls")
    suspend fun startTestCall(@Body body: TestCallRequest): TestCallResponse

    @POST("call-assistant/test-calls/{callId}/turns")
    suspend fun testCallTurn(@Path("callId") callId: String, @Body body: TestTurnRequest): TestCallResponse

    // Portal leads (99acres / Housing.com)
    @GET("portal-leads/listings")
    suspend fun portalListings(
        @Query("portal") portal: com.brokerbuddy.core.model.Portal,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): com.brokerbuddy.core.model.PortalListingList

    @GET("portal-leads/listings/{id}")
    suspend fun portalListing(
        @Path("id") id: String,
        @Query("portal") portal: com.brokerbuddy.core.model.Portal,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): com.brokerbuddy.core.model.PortalListingDetail

    @PATCH("portal-leads/{id}")
    suspend fun updatePortalLead(@Path("id") id: String, @Body body: com.brokerbuddy.core.model.UpdatePortalLeadRequest): com.brokerbuddy.core.model.PortalLeadEnvelope

    @POST("portal-leads/import")
    suspend fun importPortalCsv(@Body body: com.brokerbuddy.core.model.PortalCsvImportRequest): com.brokerbuddy.core.model.PortalCsvImportResult

    @POST("portal-leads/import-text")
    suspend fun importPortalText(@Body body: com.brokerbuddy.core.model.PortalTextImportRequest): com.brokerbuddy.core.model.PortalRecordResult

    @GET("portal-leads/integrations")
    suspend fun portalIntegrations(): com.brokerbuddy.core.model.PortalIntegrations

    @POST("portal-leads/integrations/{portal}/key")
    suspend fun createInboundKey(@Path("portal") portal: com.brokerbuddy.core.model.Portal): com.brokerbuddy.core.model.InboundKeyResponse

    // Voice / typed commands
    @POST("assistant/command")
    suspend fun assistantCommand(@Body body: com.brokerbuddy.core.model.AssistantCommandRequest): com.brokerbuddy.core.model.AssistantCommandResult

    // Voice fill for the requirement form (reads only; nothing saved)
    @POST("voice-notes/extract")
    suspend fun extractRequirement(@Body body: com.brokerbuddy.core.model.ExtractRequest): com.brokerbuddy.core.model.Extraction
}
