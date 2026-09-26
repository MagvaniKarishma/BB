package com.brokerbuddy.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LastInteraction(val kind: String, val text: String, val at: String, val by: String? = null)

@Serializable
data class CallerClient(
    val id: String,
    val name: String,
    val primaryPhone: String,
    val status: ClientStatus,
    val leadSource: LeadSource,
    val assignedTo: UserRef? = null,
    /** Open (active + paused) inquiries, most recently updated first. */
    val inquiries: List<Inquiry> = emptyList(),
    val closedInquiries: Int = 0,
    val reminders: List<Reminder> = emptyList(),
    val lastInteraction: LastInteraction? = null,
)

/** number = null: hidden or invalid number. client = null: not a known client. */
@Serializable
data class CallerLookup(val number: String? = null, val client: CallerClient? = null)

@Serializable
data class DirectoryEntry(val e164: String, val clientId: String, val name: String)

@Serializable
data class CallerDirectory(val generatedAt: String, val entries: List<DirectoryEntry>)

@Serializable
data class ClientNote(
    val id: String,
    val body: String,
    val source: NoteSource = NoteSource.MANUAL,
    val createdAt: String,
    val author: UserRef? = null,
)

@Serializable
data class ClientNoteList(val notes: List<ClientNote>)

@Serializable
data class ClientNoteEnvelope(val note: ClientNote)

@Serializable
data class AddNoteRequest(val body: String, val source: NoteSource = NoteSource.MANUAL)
