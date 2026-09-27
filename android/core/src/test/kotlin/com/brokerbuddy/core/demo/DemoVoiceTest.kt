package com.brokerbuddy.core.demo

import com.brokerbuddy.core.form.RequirementForm
import com.brokerbuddy.core.form.applyDraft
import com.brokerbuddy.core.model.ApiJson
import com.brokerbuddy.core.model.ApplyVoiceNoteRequest
import com.brokerbuddy.core.model.ApplyVoiceNoteResponse
import com.brokerbuddy.core.model.ClientList
import com.brokerbuddy.core.model.ExtractRequest
import com.brokerbuddy.core.model.Extraction
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.RequirementSource
import com.brokerbuddy.core.model.TextVoiceNoteRequest
import com.brokerbuddy.core.model.TranscriptRequest
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.model.VoiceLanguage
import com.brokerbuddy.core.model.VoiceNoteList
import com.brokerbuddy.core.model.VoiceNoteResponse
import com.brokerbuddy.core.model.VoiceNoteStatus
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice fill and voice notes in the demo: read on the phone, saved on the phone, reviewed before saving. */
class DemoVoiceTest {
    private val snapshot = File("../app/src/main/assets/demo/responses.json").readText()
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    private var saved: String? = null
    private fun phone() = DemoApi(snapshot, today, DemoChanges.fromJson(saved), onChange = { saved = it.toJson() })

    private fun DemoApi.post(path: String, body: String, method: String = "POST") = handle(method, "/api/v1/$path", emptyList(), body)
    private fun rahul(api: DemoApi) = ApiJson.decodeFromString(ClientList.serializer(), api.handle("GET", "/api/v1/clients", listOf("q" to "Rahul Sharma", "group" to "all")).body).clients.single()

    @Test
    fun voiceFillReadsOnlyWhatWasSaid() {
        val api = phone()
        val r = api.post("voice-notes/extract", ApiJson.encodeToString(ExtractRequest.serializer(), ExtractRequest("Rahul ko Andheri West mein 2 BHK kiraye pe chahiye, 60 hazaar tak")))
        assertEquals(200, r.status, r.body)
        val x = ApiJson.decodeFromString(Extraction.serializer(), r.body)
        assertEquals(TransactionType.RENT, x.draft.transactionType?.value)
        assertEquals(PropertyCategory.BHK_2, x.draft.category?.value)
        assertEquals(60_000L, x.draft.budgetMax?.value)
        assertEquals(listOf("Andheri West"), x.draft.locations?.map { it.value })
        assertNull(x.draft.furnishing)
        assertNull(x.draft.minParking)
        // Hindi words + the phone's English translation: the translation fills what the rules missed.
        val hi = ApiJson.decodeFromString(Extraction.serializer(), api.post("voice-notes/extract",
            ApiJson.encodeToString(ExtractRequest.serializer(), ExtractRequest("पवई में तीन बीएचके खरीदना है", VoiceLanguage.HINDI, "Want to buy a 3 BHK in Powai"))).body)
        assertEquals(TransactionType.BUY, hi.draft.transactionType?.value)
        assertEquals(PropertyCategory.BHK_3, hi.draft.category?.value)
        assertEquals(listOf("Powai"), hi.draft.locations?.map { it.value })
        assertTrue(hi.draft.locations!!.none { Regex("[\\u0900-\\u097F]").containsMatchIn(it.value) }) // areas in English only
        // The form keeps everything else blank.
        val form = RequirementForm(transactionType = null).applyDraft(x.draft).form
        assertEquals("", form.minParking)
        assertTrue(form.floorPreference.isEmpty())
    }

    @Test
    fun typedNote_reviewSaveRestart() {
        var api = phone()
        val client = rahul(api)
        val created = api.post("voice-notes/text", ApiJson.encodeToString(TextVoiceNoteRequest.serializer(),
            TextVoiceNoteRequest(client.id, "Rahul wants a 3 BHK to buy in Powai, budget 2 to 2.5 crore, higher floor", VoiceLanguage.ENGLISH)))
        assertEquals(201, created.status, created.body)
        val note = ApiJson.decodeFromString(VoiceNoteResponse.serializer(), created.body).voiceNote
        assertEquals(VoiceNoteStatus.READY, note.status)
        val draft = note.extraction!!.draft
        assertEquals(PropertyCategory.BHK_3, draft.category?.value)
        assertEquals(25_000_000L, draft.budgetMax?.value)

        // On the client's profile.
        val list = ApiJson.decodeFromString(VoiceNoteList.serializer(), api.handle("GET", "/api/v1/voice-notes", listOf("clientId" to client.id)).body)
        assertEquals(note.id, list.voiceNotes.first().id)

        // Reviewed and saved as a new requirement.
        val request = RequirementForm(transactionType = null).applyDraft(draft).form.validate().forVoice()!!
        val applied = api.post("voice-notes/${note.id}/apply", ApiJson.encodeToString(ApplyVoiceNoteRequest.serializer(), ApplyVoiceNoteRequest(null, request)))
        assertEquals(200, applied.status, applied.body)
        val res = ApiJson.decodeFromString(ApplyVoiceNoteResponse.serializer(), applied.body)
        assertEquals(RequirementSource.VOICE_NOTE, res.inquiry.source)
        assertEquals(VoiceNoteStatus.APPLIED, res.voiceNote.status)
        assertEquals(409, api.post("voice-notes/${note.id}/apply", ApiJson.encodeToString(ApplyVoiceNoteRequest.serializer(), ApplyVoiceNoteRequest(null, request))).status)

        // Restart.
        api = phone()
        val again = ApiJson.decodeFromString(VoiceNoteResponse.serializer(), api.handle("GET", "/api/v1/voice-notes/${note.id}", emptyList()).body)
        assertEquals(VoiceNoteStatus.APPLIED, again.voiceNote.status)
        assertTrue(again.inquiries.any { it.id == res.inquiry.id && it.source == RequirementSource.VOICE_NOTE })
    }

    @Test
    fun recordedNote_needsTranscriptInTheDemo() {
        var api = phone()
        val client = rahul(api)
        val (rec, _) = api.addRecording(client.id, null, "HINGLISH", 12_000)
        assertEquals(201, rec.status, rec.body)
        val note = ApiJson.decodeFromString(VoiceNoteResponse.serializer(), rec.body).voiceNote
        assertEquals(VoiceNoteStatus.NEEDS_TRANSCRIPT, note.status)
        assertTrue(note.hasAudio)
        assertEquals(12_000L, note.durationMs)
        assertTrue("type what was said" in note.error!!)
        assertTrue(api.hasRecording(note.id))
        // No speech-to-text without a server.
        assertEquals(400, api.post("voice-notes/${note.id}/retry", "{}").status)

        // Restart: the recording is still listed; the broker types the transcript.
        api = phone()
        val typed = api.post("voice-notes/${note.id}/transcript", ApiJson.encodeToString(TranscriptRequest.serializer(), TranscriptRequest("1 BHK rent Malad West 25k tak")), method = "PUT")
        assertEquals(200, typed.status, typed.body)
        val ready = ApiJson.decodeFromString(VoiceNoteResponse.serializer(), typed.body).voiceNote
        assertEquals(VoiceNoteStatus.READY, ready.status)
        assertEquals(PropertyCategory.BHK_1, ready.extraction!!.draft.category?.value)
        assertEquals(25_000L, ready.extraction!!.draft.budgetMax?.value)
        // Discard.
        assertEquals(200, api.post("voice-notes/${note.id}/discard", "{}").status)
        assertEquals(409, api.post("voice-notes/${note.id}/discard", "{}").status)
        // Reset removes it.
        api.reset()
        assertEquals(404, api.handle("GET", "/api/v1/voice-notes/${note.id}", emptyList()).status)
    }

    @Test
    fun sampleNoteWaitingForReviewCanBeSaved() {
        val api = phone()
        val all = ApiJson.decodeFromString(VoiceNoteList.serializer(), api.handle("GET", "/api/v1/voice-notes", emptyList()).body).voiceNotes
        val pending = all.single { it.status == VoiceNoteStatus.READY }
        val shown = ApiJson.decodeFromString(VoiceNoteResponse.serializer(), api.handle("GET", "/api/v1/voice-notes/${pending.id}", emptyList()).body)
        val request = RequirementForm(transactionType = TransactionType.BUY).applyDraft(shown.voiceNote.extraction!!.draft).form.validate().forVoice()!!
        val target = shown.suggestedInquiryId
        val r = api.post("voice-notes/${pending.id}/apply", ApiJson.encodeToString(ApplyVoiceNoteRequest.serializer(), ApplyVoiceNoteRequest(target, request)))
        assertEquals(200, r.status, r.body)
    }
}
