package com.brokerbuddy.core.model

import kotlinx.serialization.Serializable

/** Home → Today's Work counts (all from the server's data). */
@Serializable
data class TodayWork(
    /** Clients still marked New (not contacted yet). */
    val newLeads: Int,
    /** Pending callbacks due today or overdue. */
    val callbacks: Int,
    /** Pending follow-ups due today or overdue. */
    val followUps: Int,
    /** 99acres enquiries received today. */
    val acres99Leads: Int,
    /** Housing.com enquiries received today. */
    val housingLeads: Int,
)

/** The two portals with their own lead screens. Kept apart even for the same flat. */
@Serializable
enum class Portal(val label: String) {
    ACRES_99("99acres"),
    HOUSING_COM("Housing.com"),
}

@Serializable
enum class PortalLeadStatus(val label: String) {
    NEW("New"),
    CONTACTED("Contacted"),
    FOLLOW_UP("Follow-up"),
    CONVERTED("Converted"),
    NOT_INTERESTED("Not interested"),
    CLOSED("Closed"),
}

@Serializable
enum class PortalLeadChannel(val label: String) {
    WHATSAPP("WhatsApp"),
    CSV("Lead export (CSV)"),
    EMAIL("Lead email"),
    WEBHOOK("Inbound link"),
}

/** A portal listing that received enquiries, with counts for the selected dates. */
@Serializable
data class PortalListingSummary(
    /** "unidentified" groups enquiries whose listing the source didn't identify. */
    val id: String,
    val portal: Portal,
    val identified: Boolean = true,
    val title: String? = null,
    val transactionType: TransactionType? = null,
    val category: PropertyCategory? = null,
    val locality: String? = null,
    val price: Long? = null,
    val carpetAreaSqft: Int? = null,
    val url: String? = null,
    val externalId: String? = null,
    val photoUrl: String? = null,
    val propertyId: String? = null,
    val propertyPhotoId: String? = null,
    val interestedClients: Int,
    val newLeads: Int,
    val totalLeads: Int,
    val lastEnquiryAt: String,
)

@Serializable
data class PortalListingList(val listings: List<PortalListingSummary>)

@Serializable
data class PortalLeadClient(val id: String, val name: String, val primaryPhone: String, val status: ClientStatus)

@Serializable
data class PortalLeadListingRef(
    val id: String,
    val title: String? = null,
    val locality: String? = null,
    val portal: Portal,
    val url: String? = null,
)

/** One enquiry. Only what the source provided is filled in. */
@Serializable
data class PortalLeadItem(
    val id: String,
    val portal: Portal,
    val listingId: String? = null,
    val clientId: String? = null,
    val client: PortalLeadClient? = null,
    val listing: PortalLeadListingRef? = null,
    val externalLeadId: String? = null,
    val enquiredAt: String,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val message: String? = null,
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val requirement: String? = null,
    val status: PortalLeadStatus,
    val channel: PortalLeadChannel,
)

/** A portal enquiry on a client's profile. */
@Serializable
data class ClientPortalLead(
    val id: String,
    val portal: Portal,
    val enquiredAt: String,
    val status: PortalLeadStatus,
    val message: String? = null,
    val listing: PortalLeadListingRef? = null,
)

@Serializable
data class PortalListingDetail(
    val listing: PortalListingSummary? = null,
    val totalInterestedClients: Int = 0,
    val leads: List<PortalLeadItem>,
)

@Serializable
data class PortalLeadList(val leads: List<PortalLeadItem>)

@Serializable
data class PortalLeadEnvelope(val lead: PortalLeadItem)

@Serializable
data class UpdatePortalLeadRequest(val status: PortalLeadStatus)

@Serializable
data class PortalCsvImportRequest(val portal: Portal, val csv: String, val fileName: String? = null)

@Serializable
data class SkippedRow(val row: Int, val reason: String)

@Serializable
data class PortalCsvImportResult(
    val rows: Int,
    val created: Int,
    val duplicates: Int,
    val clientsCreated: Int,
    val skipped: List<SkippedRow> = emptyList(),
)

@Serializable
data class PortalTextImportRequest(val text: String, val portal: Portal? = null)

@Serializable
data class PortalRecordResult(val created: Boolean, val clientCreated: Boolean = false)

@Serializable
enum class IntegrationStatus(val label: String) {
    CONNECTED("Connected"),
    NOT_CONNECTED("Not Connected"),
    IMPORT_REQUIRED("Import Required"),
}

@Serializable
data class PortalIntegration(
    val portal: Portal,
    val status: IntegrationStatus,
    val inboundKeyPrefix: String? = null,
    val keyCreatedAt: String? = null,
    val lastReceivedAt: String? = null,
    val leadsByChannel: Map<String, Int> = emptyMap(),
    val lastLeadAt: String? = null,
)

@Serializable
data class PortalIntegrations(val portals: List<PortalIntegration>)

@Serializable
data class InboundKeyResponse(val portal: Portal, val key: String, val path: String)

// ---------- voice / typed commands ----------

@Serializable
data class AssistantCommandRequest(val text: String, val tz: Int)

/** Where a command wants the app to go. */
@Serializable
data class AssistantNavigate(
    val screen: String,
    val portal: Portal? = null,
    val range: String? = null,
    val listingId: String? = null,
    val clientId: String? = null,
)

@Serializable
data class AssistantOption(val label: String, val navigate: AssistantNavigate)

@Serializable
data class AssistantCommandResult(
    val intent: String,
    val message: String,
    val done: Boolean = false,
    val navigate: AssistantNavigate? = null,
    val options: List<AssistantOption> = emptyList(),
)
