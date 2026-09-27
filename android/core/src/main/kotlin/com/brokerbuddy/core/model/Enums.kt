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
    AI_CALL_ASSISTANT("AI Call Assistant"),
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
    PROPERTY_TYPE("Property type"),
}

/** Which part of the building a client wants: never an exact floor. */
@Serializable
enum class FloorBand(val label: String) {
    LOWER("Lower floor"),
    MIDDLE("Middle floor"),
    HIGHER("Higher floor"),
}

@Serializable
enum class PropertyType(val label: String) {
    APARTMENT("Apartment"),
    INDEPENDENT_HOUSE("Independent house"),
    VILLA("Villa"),
    PENTHOUSE("Penthouse"),
    BUILDER_FLOOR("Builder floor"),
    COMMERCIAL("Commercial"),
    PLOT("Plot"),
    OTHER("Other"),
}

@Serializable
enum class RequirementSource { MANUAL, VOICE_NOTE, PORTAL_LEAD, WHATSAPP, AI_CALL }

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

/** A follow-up the broker set, or a callback someone is waiting for. */
@Serializable
enum class ReminderKind { FOLLOW_UP, CALLBACK }

@Serializable
enum class Role { OWNER, ADMIN, AGENT }

@Serializable
enum class VoiceLanguage(val label: String) {
    AUTO("Auto-detect"),
    HINDI("हिन्दी Hindi"),
    HINGLISH("Hinglish"),
    MARATHI("मराठी Marathi"),
    ENGLISH("English"),
}

@Serializable
enum class VoiceNoteStatus { NEEDS_TRANSCRIPT, READY, APPLIED, DISCARDED }

@Serializable
enum class NoteSource { MANUAL, CALLER_SCREEN, NOTIFICATION, AI_CALL }
