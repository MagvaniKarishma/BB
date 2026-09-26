package com.brokerbuddy.core

import com.brokerbuddy.core.model.DraftReview
import com.brokerbuddy.core.model.LeadSource
import com.brokerbuddy.core.model.WaMessageDetail
import com.brokerbuddy.core.whatsapp.SharedText
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsAppDtoTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Shape returned by GET /whatsapp/messages/:id for a forwarded 99acres lead (backend whatsappApi.test.ts).
    private val detail = """
    {"message":{"id":"m1","brokerageId":"b1","accountId":"a1","contactId":"c1","clientId":null,"inquiryId":null,
      "channel":"API","direction":"INBOUND","externalId":"wamid.1","type":"text",
      "text":"Dear Customer, You have received a response on your property ID A12345678 (2 BHK Apartment for Rent in Andheri West, Mumbai, ₹65,000). Name: Amit Verma, Mobile: +91-9876543210 - 99acres",
      "mediaId":null,"mediaMimeType":null,"senderName":"Field Agent","sentAt":"2026-09-26T05:00:00.000Z","status":"RECEIVED",
      "errorReason":null,"leadPhone":"+919876543210","leadName":"Amit Verma","portal":"ACRES_99",
      "portalLead":{"portal":"ACRES_99","priceSpans":[{"start":125,"end":132}],"leadPhone":"+919876543210","leadName":"Amit Verma",
        "listingRef":"A12345678","listingPrice":{"value":65000,"evidence":"₹65,000"}},
      "extraction":{"draft":{"transactionType":{"value":"RENT","evidence":"Rent"},"category":{"value":"BHK_2","evidence":"2 BHK"},
        "locations":[{"value":"Andheri West","evidence":"Andheri West"}]},
        "advertisedPrices":[{"value":65000,"evidence":"₹65,000"}],
        "warnings":["Advertised price ₹65,000 of the 99acres listing kept separate — it is not the client's budget"],"extractor":"rules"},
      "review":"PENDING","processedAt":"2026-09-26T05:00:01.000Z","processError":null,"createdAt":"2026-09-26T05:00:00.000Z",
      "contact":{"id":"c1","waId":"+919811122233","profileName":"Field Agent","clientId":null},"client":null},
     "inquiries":[],"suggestedInquiryId":null,"existingClient":null}
    """.trimIndent()

    @Test
    fun decodesForwardedPortalLead() {
        val d = json.decodeFromString<WaMessageDetail>(detail)
        val m = d.message
        assertEquals(LeadSource.ACRES_99, m.portal)
        assertEquals("Amit Verma", m.portalLead!!.leadName)
        assertEquals(65_000L, m.portalLead!!.listingPrice!!.value)
        assertNull(m.extraction!!.draft.budgetMax) // advertised price is not a budget
        assertEquals(65_000L, m.extraction!!.advertisedPrices.single().value)
        assertEquals(DraftReview.PENDING, m.review)
        assertNull(m.clientId)
    }

    @Test
    fun recognisesChatExports() {
        val export = "26/09/2026, 10:15 am - Rahul Sharma: 2 BHK chahiye\n26/09/2026, 10:17 am - Priya: Sure\n26/09/2026, 10:18 am - Rahul Sharma: 70k tak"
        assertTrue(SharedText.looksLikeChatExport(export))
        assertEquals(listOf("Rahul Sharma", "Priya"), SharedText.participants(export))
        assertEquals(listOf("Rahul Sharma"), SharedText.participants("[26/09/26, 10:15:32 AM] Rahul Sharma: Hi"))
        assertFalse(SharedText.looksLikeChatExport("Hi, 2 BHK chahiye Andheri mein, 60k tak"))
    }
}
