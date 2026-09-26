package com.brokerbuddy.core.model

import kotlinx.serialization.Serializable

/** A value extracted from a voice note plus the exact words it came from. */
@Serializable
data class Evidence<T>(val value: T, val evidence: String)

/** AI/rules-extracted draft. A null field means the speaker did not state it. */
@Serializable
data class RequirementDraft(
    val transactionType: Evidence<TransactionType>? = null,
    val category: Evidence<PropertyCategory>? = null,
    val budgetMin: Evidence<Long>? = null,
    val budgetMax: Evidence<Long>? = null,
    val locations: List<Evidence<String>>? = null,
    val furnishing: Evidence<List<Furnishing>>? = null,
    val minParking: Evidence<Int>? = null,
    val floorMin: Evidence<Int>? = null,
    val floorMax: Evidence<Int>? = null,
    val possession: Evidence<Possession>? = null,
    val possessionBy: Evidence<String>? = null,
)

@Serializable
data class Extraction(
    val draft: RequirementDraft = RequirementDraft(),
    /** Prices of specific properties mentioned (e.g. a portal listing) — never the client's budget. */
    val advertisedPrices: List<Evidence<Long>> = emptyList(),
    val warnings: List<String> = emptyList(),
    val extractor: String = "",
)

@Serializable
data class VoiceNote(
    val id: String,
    val clientId: String,
    val inquiryId: String? = null,
    val language: VoiceLanguage = VoiceLanguage.AUTO,
    val status: VoiceNoteStatus,
    val hasAudio: Boolean = false,
    val durationMs: Long? = null,
    val originalTranscript: String? = null,
    val transcript: String? = null,
    val transcriptSource: String? = null,
    val extraction: Extraction? = null,
    val extractor: String? = null,
    val error: String? = null,
    val appliedVersion: Int? = null,
    val createdAt: String,
    val createdBy: UserRef? = null,
)

@Serializable
data class VoiceNoteResponse(
    val voiceNote: VoiceNote,
    val suggestedInquiryId: String? = null,
    val targetWarnings: List<String> = emptyList(),
    val inquiries: List<Inquiry> = emptyList(),
)

@Serializable
data class VoiceNoteList(val voiceNotes: List<VoiceNote>)

@Serializable
data class TextVoiceNoteRequest(
    val clientId: String,
    val transcript: String,
    val language: VoiceLanguage = VoiceLanguage.AUTO,
    val inquiryId: String? = null,
)

@Serializable
data class TranscriptRequest(val transcript: String)

/** inquiryId = null creates a new inquiry for the note's client. */
@Serializable
data class ApplyVoiceNoteRequest(val inquiryId: String?, val requirement: RequirementRequest)

@Serializable
data class ApplyVoiceNoteResponse(val inquiry: Inquiry, val changed: Boolean, val voiceNote: VoiceNote)

@Serializable
data class VoiceNoteEnvelope(val voiceNote: VoiceNote)

/** Words to read a requirement from (voice fill on the requirement form). Nothing is stored. */
@Serializable
data class ExtractRequest(
    val text: String,
    val language: VoiceLanguage = VoiceLanguage.AUTO,
    /** English translation made on the phone (Hindi/Marathi speech). */
    val english: String? = null,
)
