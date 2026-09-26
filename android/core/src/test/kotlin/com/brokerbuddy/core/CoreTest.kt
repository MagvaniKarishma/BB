package com.brokerbuddy.core

import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.RequirementRequest
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.phone.PhoneNumbers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun fullUsesIndianGrouping() {
        assertEquals("₹999", Money.full(999))
        assertEquals("₹65,000", Money.full(65_000))
        assertEquals("₹1,25,00,000", Money.full(12_500_000))
        assertEquals("₹10,00,00,00,000", Money.full(10_000_000_000))
    }

    @Test
    fun compactUsesLakhAndCrore() {
        assertEquals("₹65K", Money.compact(65_000))
        assertEquals("₹75 L", Money.compact(7_500_000))
        assertEquals("₹1.25 Cr", Money.compact(12_500_000))
        assertEquals("₹2 Cr", Money.compact(20_000_000))
        assertEquals("₹500", Money.compact(500))
    }

    @Test
    fun parsesBrokerShorthand() {
        assertEquals(65_000L, Money.parse("65000"))
        assertEquals(65_000L, Money.parse("65,000"))
        assertEquals(65_000L, Money.parse("65k"))
        assertEquals(7_500_000L, Money.parse("75 L"))
        assertEquals(7_500_000L, Money.parse("75 lakh"))
        assertEquals(12_000_000L, Money.parse("1.2 cr"))
        assertEquals(20_000_000L, Money.parse("₹2 crore"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("about 50"))
        assertNull(Money.parse("1.2 million"))
    }

    @Test
    fun rangeText() {
        assertEquals("₹50K – ₹70K", Money.range(50_000, 70_000))
        assertEquals("Up to ₹1.5 Cr", Money.range(null, 15_000_000))
        assertNull(Money.range(null, null))
    }
}

class PhoneNumbersTest {
    @Test
    fun normalizesIndianFormats() {
        listOf("9820012345", "98200 12345", "098200-12345", "+91 98200 12345", "919820012345", "0091 98200 12345")
            .forEach { assertEquals("+919820012345", PhoneNumbers.normalize(it), it) }
    }

    @Test
    fun keepsInternationalAndRejectsJunk() {
        assertEquals("+14155552671", PhoneNumbers.normalize("+1 415 555 2671"))
        assertNull(PhoneNumbers.normalize("12345"))
        assertNull(PhoneNumbers.normalize(""))
        assertNull(PhoneNumbers.normalize("0000000000"))
    }

    @Test
    fun displayAndWhatsApp() {
        assertEquals("+91 98200 12345", PhoneNumbers.display("+919820012345"))
        assertEquals("919820012345", PhoneNumbers.whatsAppDigits("+919820012345"))
    }
}

class SerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun decodesDashboardFromBackend() {
        val body = """
            {"generatedAt":"2026-09-26T07:19:01.101Z",
             "rent":{"total":1,"tiles":[{"category":"BHK_2","inquiries":1,"clients":1}]},
             "buy":{"total":0,"tiles":[]},
             "clientsByStatus":{"NEW":1},
             "reminders":{"overdue":0,"dueToday":1},
             "availableProperties":3,
             "someFutureField":true}
        """.trimIndent()
        val d = json.decodeFromString<Dashboard>(body)
        assertEquals(PropertyCategory.BHK_2, d.rent.tiles.single().category)
        assertEquals(3, d.availableProperties)
    }

    @Test
    fun requirementRequestSendsExplicitNullsSoFieldsCanBeCleared() {
        val encoded = json.encodeToString(
            RequirementRequest.serializer(),
            RequirementRequest(TransactionType.RENT, PropertyCategory.BHK_1),
        )
        assertTrue("\"budgetMax\":null" in encoded, encoded)
        assertTrue("\"transactionType\":\"RENT\"" in encoded)
    }
}
