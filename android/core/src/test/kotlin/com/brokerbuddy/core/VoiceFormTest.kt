package com.brokerbuddy.core

import com.brokerbuddy.core.form.FormField
import com.brokerbuddy.core.form.RequirementForm
import com.brokerbuddy.core.form.applyDraft
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.RequirementField
import com.brokerbuddy.core.model.RequirementSource
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.model.VoiceNoteResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceFormTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Shape produced by the backend for the Hinglish note in voiceNotes.test.ts.
    private val response = """
        {"voiceNote":{"id":"v1","clientId":"c1","inquiryId":null,"language":"HINGLISH","status":"READY",
          "hasAudio":true,"audioSize":1234,"transcript":"Rahul ji ko Andheri West ya Jogeshwari mein 2 BHK chahiye rent pe, budget 60 se 70 hazaar tak",
          "extraction":{"draft":{
             "transactionType":{"value":"RENT","evidence":"rent"},
             "category":{"value":"BHK_2","evidence":"2 BHK"},
             "budgetMin":{"value":60000,"evidence":"60 se 70 hazaar"},
             "budgetMax":{"value":70000,"evidence":"60 se 70 hazaar"},
             "locations":[{"value":"Andheri West","evidence":"Andheri West"},{"value":"Jogeshwari","evidence":"Jogeshwari"}],
             "furnishing":{"value":["SEMI_FURNISHED"],"evidence":"semi furnished"}},
           "warnings":["Read x as max"],"extractor":"rules"},
          "createdAt":"2026-09-26T08:00:00.000Z","createdBy":{"id":"u1","name":"Owner"}},
         "suggestedInquiryId":null,"targetWarnings":[],"inquiries":[]}
    """.trimIndent()

    @Test
    fun decodesBackendVoiceNote() {
        val r = json.decodeFromString<VoiceNoteResponse>(response)
        val d = r.voiceNote.extraction!!.draft
        assertEquals(PropertyCategory.BHK_2, d.category!!.value)
        assertEquals(70_000L, d.budgetMax!!.value)
        assertEquals(listOf("Andheri West", "Jogeshwari"), d.locations!!.map { it.value })
        assertNull(d.possession)
    }

    @Test
    fun newRequirementGetsOnlyStatedFields() {
        val draft = json.decodeFromString<VoiceNoteResponse>(response).voiceNote.extraction!!.draft
        val merge = RequirementForm().applyDraft(draft)
        val f = merge.form
        assertEquals(TransactionType.RENT, f.transactionType)
        assertEquals("70000", f.budgetMax)
        assertEquals("Andheri West, Jogeshwari", f.locations)
        assertEquals(setOf(Furnishing.SEMI_FURNISHED), f.furnishing)
        assertEquals("", f.minParking) // not said → left blank
        assertTrue(f.floorPreference.isEmpty())
        assertNull(f.possession)
        assertEquals("60 se 70 hazaar", merge.evidence[FormField.BUDGET_MAX])
        assertTrue(merge.previous.isEmpty())
        val req = f.validate().forVoice()!!
        assertEquals(RequirementSource.VOICE_NOTE, req.source)
        assertNull(req.minParking)
    }

    @Test
    fun updateKeepsExistingValuesAndReportsChanges() {
        val existing = Inquiry(
            id = "i1", clientId = "c1", transactionType = TransactionType.RENT, category = PropertyCategory.BHK_2,
            status = InquiryStatus.ACTIVE, budgetMax = 55_000, locations = listOf("Andheri"), minParking = 1,
            mandatory = listOf(RequirementField.BUDGET), createdAt = "", updatedAt = "",
        )
        val draft = json.decodeFromString<VoiceNoteResponse>(response).voiceNote.extraction!!.draft
        val merge = RequirementForm.fromInquiry(existing).applyDraft(draft)
        assertEquals("1", merge.form.minParking) // untouched by the note
        assertEquals("Andheri, Andheri West, Jogeshwari", merge.form.locations) // added, not replaced
        assertEquals("₹55,000", merge.previous[FormField.BUDGET_MAX])
        assertFalse(FormField.CATEGORY in merge.previous) // same value → not a change
        assertEquals(setOf(RequirementField.BUDGET), merge.form.mandatory)
    }

    @Test
    fun validationReportsErrorsInsteadOfDropping() {
        val v = RequirementForm(budgetMin = "80k", budgetMax = "abc", possessionBy = "June").validate()
        assertFalse(v.isValid)
        assertEquals(
            setOf(FormField.BUDGET_MAX, FormField.POSSESSION_BY, FormField.TRANSACTION, FormField.CATEGORY),
            v.errors.keys,
        )
        val ok = RequirementForm(TransactionType.BUY, PropertyCategory.BHK_3, budgetMax = "1.5 cr").validate()
        assertEquals(15_000_000L, ok.request!!.budgetMax)
    }

    @Test
    fun formSurvivesSerialization() {
        val f = RequirementForm(TransactionType.BUY, PropertyCategory.BHK_3, furnishing = setOf(Furnishing.FULLY_FURNISHED))
        assertEquals(f, json.decodeFromString<RequirementForm>(json.encodeToString(RequirementForm.serializer(), f)))
    }
}
