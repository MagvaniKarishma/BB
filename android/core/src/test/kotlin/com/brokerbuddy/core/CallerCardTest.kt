package com.brokerbuddy.core

import com.brokerbuddy.core.caller.CallerCard
import com.brokerbuddy.core.caller.CallerCards
import com.brokerbuddy.core.caller.DirectoryIndex
import com.brokerbuddy.core.model.CallerDirectory
import com.brokerbuddy.core.model.CallerLookup
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallerCardTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-09-26T10:00:00Z") // 3:30 PM IST

    // Shape returned by GET /caller/lookup (see backend/test/caller.test.ts).
    private val lookup = """
    {"number":"+919820012345","client":{"id":"c1","name":"Rahul Sharma","primaryPhone":"+919820012345",
     "status":"SITE_VISIT","leadSource":"ACRES_99","assignedTo":{"id":"u1","name":"Priya"},"email":null,
     "inquiries":[
      {"id":"i1","clientId":"c1","transactionType":"RENT","category":"BHK_2","status":"ACTIVE","budgetMin":60000,"budgetMax":70000,
       "locations":["Andheri","Jogeshwari"],"furnishing":[],"mandatory":["BUDGET"],"version":1,"source":"MANUAL",
       "createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z","matchCount":3},
      {"id":"i2","clientId":"c1","transactionType":"BUY","category":"BHK_3","status":"PAUSED","budgetMax":25000000,
       "locations":["Powai"],"furnishing":[],"mandatory":[],"version":2,"source":"VOICE_NOTE",
       "createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z","matchCount":null}],
     "closedInquiries":1,
     "reminders":[{"id":"r1","title":"Call about Lokhandwala","dueAt":"2026-09-26T09:00:00Z","status":"PENDING"}],
     "lastInteraction":{"kind":"NOTE","text":"Visited Lokhandwala flat,\n liked it","at":"2026-09-24T06:00:00Z","by":"Owner"}}}
    """.trimIndent()

    @Test
    fun knownCallerCardShowsEveryInquiryAndContext() {
        val l = json.decodeFromString<CallerLookup>(lookup)
        val card = CallerCards.known(l.client!!, now, zone)
        assertEquals(CallerCard.Kind.KNOWN, card.kind)
        assertEquals("Rahul Sharma", card.title)
        assertEquals("Site visit · 99acres · Agent: Priya", card.subtitle)
        assertEquals(
            listOf(
                "Rent · 2 BHK · ₹60K – ₹70K · Andheri, Jogeshwari · 3 matches",
                "Buy · 3 BHK · Up to ₹2.5 Cr · Powai · paused",
                "Last: “Visited Lokhandwala flat, liked it” · 2 days ago by Owner",
                "Follow-up overdue: Call about Lokhandwala",
            ),
            card.lines,
        )
        assertNull(card.singleInquiryId) // two inquiries → go to profile
    }

    @Test
    fun clientWithoutRequirements() {
        val l = json.decodeFromString<CallerLookup>(
            """{"number":"+919820012345","client":{"id":"c1","name":"A","primaryPhone":"+919820012345","status":"NEW","leadSource":"WALK_IN","closedInquiries":2}}""",
        )
        assertEquals(listOf("No open requirements (2 closed)"), CallerCards.known(l.client!!, now, zone).lines)
    }

    @Test
    fun directoryMatchesIncomingNumberFormats() {
        val dir = json.decodeFromString<CallerDirectory>(
            """{"generatedAt":"x","entries":[{"e164":"+919820012345","clientId":"c1","name":"Rahul"}]}""",
        )
        val index = DirectoryIndex(dir)
        listOf("+919820012345", "09820012345", "9820012345", "+91 98200-12345").forEach {
            assertEquals("c1", index.find(it)?.clientId, it)
        }
        assertNull(index.find("9867000000"))
        assertNull(index.find(null))
        assertNull(index.find("PRIVATE"))
        assertEquals(0, DirectoryIndex(null).size)
        assertEquals("Loading requirements…", CallerCards.cached(dir.entries[0], "09820012345").lines.single())
    }

    @Test
    fun unknownAndHiddenCallers() {
        assertEquals("+91 98670 00000", CallerCards.unknown("+919867000000").title)
        assertEquals("Unknown number", CallerCards.unknown(null).title)
    }

    @Test
    fun relativeTimes() {
        fun rel(iso: String) = CallerCards.relative(iso, now, zone)
        assertEquals("just now", rel("2026-09-26T09:59:40Z"))
        assertEquals("15 min ago", rel("2026-09-26T09:45:00Z"))
        assertEquals("today 11:00 AM", rel("2026-09-26T05:30:00Z"))
        assertEquals("today 6:30 PM", rel("2026-09-26T13:00:00Z"))
        assertEquals("yesterday", rel("2026-09-25T06:00:00Z"))
        assertEquals("tomorrow 10:00 AM", rel("2026-09-27T04:30:00Z"))
        assertEquals("4 days ago", rel("2026-09-22T06:00:00Z"))
        assertEquals("1 Sep", rel("2026-09-01T06:00:00Z"))
    }
}
