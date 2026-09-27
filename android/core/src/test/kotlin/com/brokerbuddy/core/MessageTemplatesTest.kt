package com.brokerbuddy.core

import com.brokerbuddy.core.message.MessageTemplates
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.phone.PhoneNumbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageTemplatesTest {
    private val req = Inquiry("i1", "c1", TransactionType.RENT, PropertyCategory.BHK_2, InquiryStatus.ACTIVE, locations = listOf("Andheri West"), createdAt = "", updatedAt = "")

    @Test
    fun usesOnlyWhatIsKnown() {
        val t = MessageTemplates.forClient("Rahul Sharma", "Riya Desai", req)
        assertEquals(listOf("Introduction", "Share options", "Site visit", "Follow-up"), t.map { it.label })
        assertEquals("Hi Rahul, this is Riya Desai, thanks for your enquiry. When is a good time to talk about what you're looking for?", t[0].text)
        assertTrue("2 BHK for rent in Andheri West" in t[1].text)
        // No requirement → no "share options"; placeholder names aren't used as a name.
        val bare = MessageTemplates.forClient("Caller 98200 11001", null, null)
        assertNull(bare.firstOrNull { it.label == "Share options" })
        assertTrue(bare.all { it.text.startsWith("Hi,") })
    }

    @Test
    fun whatsAppNumbers() {
        // wa.me wants the full international number, digits only.
        assertEquals("919820011001", PhoneNumbers.whatsAppDigits(PhoneNumbers.normalize("098200 11001")!!))
        assertEquals("919820011001", PhoneNumbers.whatsAppDigits(PhoneNumbers.normalize("+91 98200-11001")!!))
        assertEquals("919820011001", PhoneNumbers.whatsAppDigits(PhoneNumbers.normalize("919820011001")!!))
        assertNull(PhoneNumbers.normalize("12345"))
        assertNull(PhoneNumbers.normalize(""))
    }
}
