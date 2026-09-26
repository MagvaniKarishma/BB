package com.brokerbuddy.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MessageDirection { INBOUND, OUTBOUND }

@Serializable
enum class MessageChannel { API, MANUAL }

@Serializable
enum class DraftReview { NONE, PENDING, APPLIED, DISMISSED }

@Serializable
data class PortalLeadInfo(
    val portal: LeadSource,
    val leadName: String? = null,
    val leadPhone: String? = null,
    val leadEmail: String? = null,
    val listingRef: String? = null,
    val listingUrl: String? = null,
    /** The listing's advertised price — not the client's budget. */
    val listingPrice: Evidence<Long>? = null,
    val leadMessage: String? = null,
)

@Serializable
data class WaContactRef(val id: String, val waId: String, val profileName: String? = null, val clientId: String? = null)

@Serializable
data class WaMessage(
    val id: String,
    val channel: MessageChannel,
    val direction: MessageDirection,
    val externalId: String,
    val type: String,
    val text: String? = null,
    val senderName: String? = null,
    val sentAt: String,
    val status: String,
    val errorReason: String? = null,
    val clientId: String? = null,
    val inquiryId: String? = null,
    val leadPhone: String? = null,
    val leadName: String? = null,
    val portal: LeadSource? = null,
    val portalLead: PortalLeadInfo? = null,
    val extraction: Extraction? = null,
    val review: DraftReview = DraftReview.NONE,
    val processError: String? = null,
    val contact: WaContactRef? = null,
    val client: ClientRef? = null,
)

@Serializable
data class WaInbox(val messages: List<WaMessage>)

@Serializable
data class WaConversation(val messages: List<WaMessage>, val serviceWindowOpen: Boolean = false, val lastInboundAt: String? = null)

@Serializable
data class ExistingClientRef(val id: String, val name: String)

@Serializable
data class WaMessageDetail(
    val message: WaMessage,
    val inquiries: List<Inquiry> = emptyList(),
    val suggestedInquiryId: String? = null,
    /** Set when creating a client would duplicate this existing profile. */
    val existingClient: ExistingClientRef? = null,
    /** The agent's own listings matching a portal enquiry (same type, area and price). */
    val enquiredProperties: List<Property> = emptyList(),
    val duplicate: Boolean = false,
    val linkedExisting: Boolean = false,
)

@Serializable
data class WaApplyResponse(val inquiry: Inquiry, val changed: Boolean)

@Serializable
data class CreateClientFromMessage(val name: String? = null, val phone: String? = null)

@Serializable
data class LinkClientRequest(val clientId: String, val addNumber: Boolean = false)

@Serializable
data class LinkInquiryRequest(val inquiryId: String?)

@Serializable
data class ImportMessageRequest(val text: String, val clientId: String? = null)

@Serializable
data class ImportChatRequest(val clientId: String, val clientSenderName: String, val exportText: String)

@Serializable
data class ImportChatResponse(val imported: Int, val skippedDuplicates: Int, val participants: List<String> = emptyList())

@Serializable
data class SendMessageRequest(val clientId: String, val text: String)

@Serializable
data class WaSendResponse(val message: WaMessage)

@Serializable
data class WaAccount(
    val id: String,
    val phoneNumberId: String,
    val wabaId: String? = null,
    val displayPhone: String? = null,
    val webhookPath: String,
    val verifyToken: String,
    val verifiedAt: String? = null,
    val lastEventAt: String? = null,
)

@Serializable
data class WaAccountStatus(val connected: Boolean, val account: WaAccount? = null, val encryptionConfigured: Boolean = true)

@Serializable
data class WaAccountEnvelope(val account: WaAccount)

@Serializable
data class ConnectWhatsAppRequest(
    val phoneNumberId: String,
    val accessToken: String,
    val appSecret: String,
    val wabaId: String? = null,
    val displayPhone: String? = null,
)
