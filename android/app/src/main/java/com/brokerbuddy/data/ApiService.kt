package com.brokerbuddy.data

import com.brokerbuddy.core.model.AuthResponse
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

    @GET("auth/me")
    suspend fun me(): MeResponse

    @GET("dashboard")
    suspend fun dashboard(): Dashboard

    // Clients
    @GET("clients")
    suspend fun clients(
        @Query("q") q: String? = null,
        @Query("status") status: ClientStatus? = null,
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

    @GET("properties/{id}/matches")
    suspend fun propertyMatches(@Path("id") id: String): InquiryMatches

    // Reminders
    @GET("reminders")
    suspend fun reminders(
        @Query("status") status: ReminderStatus? = null,
        @Query("clientId") clientId: String? = null,
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
}
