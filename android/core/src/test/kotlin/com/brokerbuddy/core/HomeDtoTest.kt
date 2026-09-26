package com.brokerbuddy.core

import com.brokerbuddy.core.model.Dashboard
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeDtoTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun decodesHomeScreenData() {
        // Shape from backend test "home screen data".
        val body = """
        {"generatedAt":"2026-09-26T05:00:00Z","rent":{"total":1,"tiles":[]},"buy":{"total":0,"tiles":[]},
         "clientsByStatus":{"NEW":1},"reminders":{"overdue":1,"dueToday":0},"availableProperties":2,
         "totals":{"clients":1,"newClientsThisWeek":1,"activeRequirements":1,"newRequirementsThisWeek":1,
                   "availableProperties":2,"newPropertiesThisWeek":2,"pendingFollowUps":2,"followUpsDueToday":1},
         "todayFollowUps":[{"id":"r1","title":"Send properties","dueAt":"2026-09-26T04:59:00Z","overdue":true,
            "client":{"id":"c1","name":"Rahul Sharma","primaryPhone":"+919820012345"},
            "requirement":{"transactionType":"RENT","category":"BHK_2","location":"Andheri"}}],
         "newLeads":[{"kind":"WHATSAPP","id":"m1","clientId":null,"messageId":"m1","name":"Vikram Singh","phone":"+919876543210",
            "source":"ACRES_99","at":"2026-09-26T04:58:00Z","requirement":{"transactionType":"RENT","category":"BHK_1","location":"Ghatkopar"}}],
         "topMatches":[{"property":{"id":"p1","title":"Lokhandwala 2BHK","transactionType":"RENT","category":"BHK_2","price":65000,
            "locality":"Andheri West","availability":"AVAILABLE","createdAt":"x","updatedAt":"x"},"matchingRequirements":3}]}
        """.trimIndent()
        val d = json.decodeFromString<Dashboard>(body)
        assertEquals(1, d.totals!!.followUpsDueToday)
        assertEquals("2 BHK • Rent • Andheri", d.todayFollowUps.single().requirement!!.text())
        assertEquals("1 BHK • Rent • Ghatkopar", d.newLeads.single().requirement!!.text())
        assertEquals(3, d.topMatches.single().matchingRequirements)
    }
}
