package com.brokerbuddy.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire models for the BrokerBuddy REST API (/api/v1). Money is whole rupees (Long).
// Timestamps are ISO-8601 strings; nullable fields are null when unknown.

@Serializable
data class User(
    val id: String,
    val name: String,
    val email: String,
    val phone: String? = null,
    val role: Role,
    val brokerageId: String,
    val active: Boolean = true,
)

@Serializable
data class AuthResponse(val token: String, val user: User)

@Serializable
data class LoginRequest(val email: String, val password: String)

/** Whether this server offers sign-in with an SMS code. */
@Serializable
data class OtpStatus(val enabled: Boolean = false, val digits: Int = 6, val resendAfterSec: Int = 30)

@Serializable
data class OtpRequest(val phone: String)

@Serializable
data class OtpSent(val retryAfterSec: Int = 30, val expiresInSec: Int = 300, val message: String? = null)

@Serializable
data class OtpVerifyRequest(val phone: String, val code: String)

@Serializable
data class UserEnvelope(val user: User)

@Serializable
data class RegisterRequest(
    val brokerageName: String,
    val name: String,
    val email: String,
    val password: String,
    /** Optional mobile number, for signing in with an SMS code later. */
    val phone: String? = null,
)

@Serializable
data class Brokerage(val id: String, val name: String)

@Serializable
data class MeResponse(val user: User, val brokerage: Brokerage)

@Serializable
data class UserRef(val id: String, val name: String)

@Serializable
data class ClientPhone(val id: String, val e164: String, val label: String? = null)

@Serializable
data class ClientRef(
    val id: String,
    val name: String,
    val primaryPhone: String,
    val status: ClientStatus? = null,
)

@Serializable
data class Client(
    val id: String,
    val name: String,
    val primaryPhone: String,
    val email: String? = null,
    val leadSource: LeadSource,
    val status: ClientStatus,
    val notes: String? = null,
    val assignedTo: UserRef? = null,
    val createdAt: String,
    val updatedAt: String,
    val activeInquiries: Int? = null,
    /** Latest active requirement (client list only). */
    val requirement: ClientRequirement? = null,
    /** A pending follow-up is due today or overdue (client list only). */
    val followUpDue: Boolean = false,
    val nextFollowUpAt: String? = null,
    val phones: List<ClientPhone> = emptyList(),
    val inquiries: List<Inquiry> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    /** 99acres / Housing.com enquiries (client profile only). */
    val portalLeads: List<ClientPortalLead> = emptyList(),
)

@Serializable
data class ClientList(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val clients: List<Client>,
    /** Size of each list tab: all, new, active, followup, lost. */
    val groupCounts: Map<String, Int> = emptyMap(),
)

/** The one-line requirement shown under a client's name. */
@Serializable
data class ClientRequirement(
    val id: String,
    val transactionType: TransactionType,
    val category: PropertyCategory,
    val locations: List<String> = emptyList(),
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
) {
    /** "2 BHK • Rent • Andheri West" */
    fun text(): String = listOfNotNull(category.label, transactionType.label, locations.firstOrNull()).joinToString(" • ")
}

@Serializable
data class ClientEnvelope(val client: Client)

@Serializable
data class NullableClientEnvelope(val client: Client? = null)

@Serializable
data class CreateClientRequest(
    val name: String,
    val phone: String,
    val altPhones: List<String> = emptyList(),
    val email: String? = null,
    val leadSource: LeadSource,
    val status: ClientStatus = ClientStatus.NEW,
    val notes: String? = null,
)

@Serializable
data class UpdateClientRequest(
    val name: String,
    val email: String?,
    val leadSource: LeadSource,
    val status: ClientStatus,
    val notes: String?,
    /** The main number; a different one replaces it (checked against other clients). */
    val phone: String? = null,
)

@Serializable
data class DuplicateCheck(val normalized: String, val duplicate: ClientRef? = null)

@Serializable
data class Inquiry(
    val id: String,
    val clientId: String,
    val transactionType: TransactionType,
    val category: PropertyCategory,
    val status: InquiryStatus,
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val locations: List<String> = emptyList(),
    val furnishing: List<Furnishing> = emptyList(),
    val minParking: Int? = null,
    val floorPreference: List<FloorBand> = emptyList(),
    val propertyTypes: List<PropertyType> = emptyList(),
    val possession: Possession? = null,
    val possessionBy: String? = null,
    val mandatory: List<RequirementField> = emptyList(),
    val notes: String? = null,
    val source: RequirementSource = RequirementSource.MANUAL,
    val version: Int = 1,
    val createdAt: String,
    val updatedAt: String,
    val client: ClientRef? = null,
    /** Available properties meeting the must-haves (caller lookup and requirement list). */
    val matchCount: Int? = null,
)

/** Full requirement body used for both create and update (nulls clear a field). */
@Serializable
data class RequirementRequest(
    val transactionType: TransactionType,
    val category: PropertyCategory,
    val status: InquiryStatus = InquiryStatus.ACTIVE,
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val locations: List<String> = emptyList(),
    val furnishing: List<Furnishing> = emptyList(),
    val minParking: Int? = null,
    val floorPreference: List<FloorBand> = emptyList(),
    val propertyTypes: List<PropertyType> = emptyList(),
    val possession: Possession? = null,
    val possessionBy: String? = null,
    val mandatory: List<RequirementField> = emptyList(),
    val notes: String? = null,
    val source: RequirementSource = RequirementSource.MANUAL,
)

@Serializable
data class InquiryEnvelope(val inquiry: Inquiry)

@Serializable
data class InquiryUpdateResponse(val inquiry: Inquiry, val changed: Boolean)

@Serializable
data class InquiryList(val inquiries: List<Inquiry>, val total: Int? = null)

@Serializable
data class InquiryRevision(
    val id: String,
    val version: Int,
    val source: RequirementSource,
    val changes: JsonObject,
    val snapshot: JsonObject,
    val createdAt: String,
    val changedBy: UserRef? = null,
    val voiceNote: RevisionVoiceNote? = null,
)

/** The voice note a requirement change came from (history view). */
@Serializable
data class RevisionVoiceNote(val id: String, val transcript: String? = null, val language: VoiceLanguage? = null)

@Serializable
data class RevisionList(val revisions: List<InquiryRevision>)

@Serializable
data class Property(
    val id: String,
    val title: String,
    val transactionType: TransactionType,
    val category: PropertyCategory,
    val propertyType: PropertyType? = null,
    val price: Long,
    val deposit: Long? = null,
    val locality: String,
    val building: String? = null,
    val address: String? = null,
    val carpetAreaSqft: Int? = null,
    val builtUpAreaSqft: Int? = null,
    val bathrooms: Int? = null,
    val furnishing: Furnishing? = null,
    val parkingSpots: Int? = null,
    val floor: Int? = null,
    val totalFloors: Int? = null,
    val possession: Possession? = null,
    val possessionDate: String? = null,
    val availability: Availability,
    val amenities: List<String> = emptyList(),
    val ownerName: String? = null,
    val ownerPhone: String? = null,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    /** Photo ids in display order; the first is the cover. */
    val photoIds: List<String> = emptyList(),
) {
    /** Every current value as a request, for updates that change one field: `toRequest().copy(availability = RENTED)`. */
    fun toRequest(): PropertyRequest = PropertyRequest(
        title = title, transactionType = transactionType, category = category, propertyType = propertyType, price = price, deposit = deposit,
        locality = locality, building = building, address = address, carpetAreaSqft = carpetAreaSqft, builtUpAreaSqft = builtUpAreaSqft,
        bathrooms = bathrooms, furnishing = furnishing, parkingSpots = parkingSpots, floor = floor, totalFloors = totalFloors, possession = possession,
        possessionDate = possessionDate?.take(10), availability = availability, amenities = amenities, ownerName = ownerName, ownerPhone = ownerPhone, notes = notes,
    )

    /** Bedrooms implied by the property type (null for studio/commercial/other). */
    val bedrooms: Int?
        get() = when (category) {
            PropertyCategory.BHK_1 -> 1
            PropertyCategory.BHK_2 -> 2
            PropertyCategory.BHK_3 -> 3
            PropertyCategory.BHK_4 -> 4
            PropertyCategory.BHK_5_PLUS -> 5
            else -> null
        }
}

@Serializable
data class PropertyRequest(
    val title: String,
    val transactionType: TransactionType,
    val category: PropertyCategory,
    val propertyType: PropertyType? = null,
    val price: Long,
    val deposit: Long? = null,
    val locality: String,
    val building: String? = null,
    val address: String? = null,
    val carpetAreaSqft: Int? = null,
    val builtUpAreaSqft: Int? = null,
    val bathrooms: Int? = null,
    val furnishing: Furnishing? = null,
    val parkingSpots: Int? = null,
    val floor: Int? = null,
    val totalFloors: Int? = null,
    val possession: Possession? = null,
    val possessionDate: String? = null,
    val availability: Availability = Availability.AVAILABLE,
    val amenities: List<String> = emptyList(),
    val ownerName: String? = null,
    val ownerPhone: String? = null,
    val notes: String? = null,
)

@Serializable
data class PropertyEnvelope(val property: Property)

@Serializable
data class PropertyList(val total: Int, val page: Int, val pageSize: Int, val properties: List<Property>)

@Serializable
data class FieldCheck(
    val field: RequirementField,
    val outcome: String,
    val mandatory: Boolean,
    val detail: String,
)

@Serializable
data class PropertyMatch(
    val property: Property,
    val score: Int,
    val checks: List<FieldCheck>,
    val needsVerification: List<RequirementField> = emptyList(),
)

@Serializable
data class PropertyMatches(val inquiryId: String, val matches: List<PropertyMatch>)

@Serializable
data class InquiryMatch(
    val inquiry: Inquiry,
    val score: Int,
    val checks: List<FieldCheck>,
    val needsVerification: List<RequirementField> = emptyList(),
)

@Serializable
data class InquiryMatches(val propertyId: String, val matches: List<InquiryMatch>)

@Serializable
data class DashboardTile(val category: PropertyCategory, val inquiries: Int, val clients: Int)

@Serializable
data class DashboardBoard(val total: Int, val tiles: List<DashboardTile>)

@Serializable
data class ReminderCounts(val overdue: Int, val dueToday: Int)

@Serializable
data class Dashboard(
    val generatedAt: String,
    val rent: DashboardBoard,
    val buy: DashboardBoard,
    val clientsByStatus: Map<String, Int> = emptyMap(),
    val reminders: ReminderCounts,
    val availableProperties: Int,
    val totals: DashboardTotals? = null,
    val todayWork: TodayWork? = null,
    val todayFollowUps: List<HomeFollowUp> = emptyList(),
    val newLeads: List<HomeLead> = emptyList(),
    val topMatches: List<TopMatch> = emptyList(),
)

@Serializable
data class DashboardTotals(
    val clients: Int = 0,
    val newClientsThisWeek: Int = 0,
    val activeRequirements: Int = 0,
    val newRequirementsThisWeek: Int = 0,
    val availableProperties: Int = 0,
    val newPropertiesThisWeek: Int = 0,
    val pendingFollowUps: Int = 0,
    val followUpsDueToday: Int = 0,
)

/** "2 BHK · Rent · Andheri West" */
@Serializable
data class RequirementSummary(
    val transactionType: TransactionType,
    val category: PropertyCategory,
    val location: String? = null,
) {
    fun text(): String = listOfNotNull(category.label, transactionType.label, location).joinToString(" • ")
}

@Serializable
data class HomeFollowUp(
    val id: String,
    val title: String,
    val dueAt: String,
    val overdue: Boolean = false,
    val client: ClientRef? = null,
    val requirement: RequirementSummary? = null,
)

@Serializable
data class HomeLead(
    /** CLIENT = new client this week; WHATSAPP = WhatsApp/portal lead not yet a client. */
    val kind: String,
    val id: String,
    val clientId: String? = null,
    val messageId: String? = null,
    val name: String? = null,
    val phone: String? = null,
    /** A LeadSource name (ACRES_99, HOUSING_COM, MAGICBRICKS, WHATSAPP, WALK_IN…). */
    val source: String,
    val at: String,
    val requirement: RequirementSummary? = null,
)

@Serializable
data class TopMatch(val property: Property, val matchingRequirements: Int)

@Serializable
data class Reminder(
    val id: String,
    val title: String,
    val note: String? = null,
    val dueAt: String,
    val status: ReminderStatus,
    val kind: ReminderKind = ReminderKind.FOLLOW_UP,
    val clientId: String? = null,
    val inquiryId: String? = null,
    val completedAt: String? = null,
    val client: ClientRef? = null,
    val assignedTo: UserRef? = null,
)

@Serializable
data class ReminderList(val reminders: List<Reminder>)

@Serializable
data class ReminderEnvelope(val reminder: Reminder)

@Serializable
data class CreateReminderRequest(
    val title: String,
    val dueAt: String,
    val note: String? = null,
    val clientId: String? = null,
    val inquiryId: String? = null,
    val kind: ReminderKind? = null,
)

@Serializable
data class UpdateReminderRequest(
    val status: ReminderStatus? = null,
    val dueAt: String? = null,
)

@Serializable
data class TeamList(val members: List<User>)

@Serializable
data class MemberEnvelope(val member: User)

@Serializable
data class CreateMemberRequest(
    val name: String,
    val email: String,
    val password: String,
    val role: Role = Role.AGENT,
    /** Lets BrokerBuddy recognise portal leads this agent forwards on WhatsApp. */
    val phone: String? = null,
)

@Serializable
data class UpdateMemberRequest(val active: Boolean? = null, val role: Role? = null)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(val code: String, val message: String, val details: JsonElement? = null)
