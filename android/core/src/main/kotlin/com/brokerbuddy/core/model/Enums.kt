package com.brokerbuddy.core.model

import kotlinx.serialization.Serializable

// Names mirror the backend Prisma enums exactly; they are the wire format.

@Serializable
enum class TransactionType(val label: String) { RENT("Rent"), BUY("Buy") }

@Serializable
enum class PropertyCategory(val label: String) {
    STUDIO("Studio"),
    BHK_1("1 BHK"),
    BHK_2("2 BHK"),
    BHK_3("3 BHK"),
    BHK_4("4 BHK"),
    BHK_5_PLUS("5+ BHK"),
    COMMERCIAL("Commercial"),
    OTHER("Other"),
}

@Serializable
enum class Furnishing(val label: String) {
    UNFURNISHED("Unfurnished"),
    SEMI_FURNISHED("Semi-furnished"),
    FULLY_FURNISHED("Fully furnished"),
}

@Serializable
enum class Possession(val label: String) {
    READY_TO_MOVE("Ready to move"),
    UNDER_CONSTRUCTION("Under construction"),
}

@Serializable
enum class ClientStatus(val label: String) {
    NEW("New"),
    CONTACTED("Contacted"),
    SITE_VISIT("Site visit"),
    NEGOTIATION("Negotiation"),
    CLOSED_WON("Closed – won"),
    CLOSED_LOST("Closed – lost"),
    ON_HOLD("On hold"),
}

@Serializable
enum class LeadSource(val label: String) {
    WALK_IN("Walk-in"),
    REFERRAL("Referral"),
    PHONE_CALL("Phone call"),
    WHATSAPP("WhatsApp"),
    ACRES_99("99acres"),
    HOUSING_COM("Housing.com"),
    MAGICBRICKS("Magicbricks"),
    WEBSITE("Website"),
    SOCIAL_MEDIA("Social media"),
    OTHER("Other"),
}

@Serializable
enum class InquiryStatus(val label: String) {
    ACTIVE("Active"),
    PAUSED("Paused"),
    FULFILLED("Fulfilled"),
    DROPPED("Dropped"),
}

@Serializable
enum class RequirementField(val label: String) {
    BUDGET("Budget"),
    LOCATION("Location"),
    FURNISHING("Furnishing"),
    PARKING("Parking"),
    FLOOR("Floor"),
    POSSESSION("Possession"),
}

@Serializable
enum class RequirementSource { MANUAL, VOICE_NOTE, PORTAL_LEAD, WHATSAPP }

@Serializable
enum class Availability(val label: String) {
    AVAILABLE("Available"),
    ON_HOLD("On hold"),
    RENTED("Rented"),
    SOLD("Sold"),
    WITHDRAWN("Withdrawn"),
}

@Serializable
enum class ReminderStatus { PENDING, DONE, CANCELLED }

@Serializable
enum class Role { OWNER, ADMIN, AGENT }
