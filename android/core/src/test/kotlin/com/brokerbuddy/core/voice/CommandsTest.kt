package com.brokerbuddy.core.voice

import com.brokerbuddy.core.model.FloorBand
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.PortalLeadStatus
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.portal.DateFilter
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandsTest {
    private val clients = listOf(
        ClientChoice("c1", "Rahul Sharma", "+919820011001"),
        ClientChoice("c2", "Priya Mehta", "+919820011002"),
        ClientChoice("c3", "Amit Patil", "+919820011003"),
        ClientChoice("c4", "Amit Shah", "+919820011004"),
    )
    // Sunday 27 Sep 2026, 11:00 in Mumbai.
    private val now = ZonedDateTime.of(2026, 9, 27, 11, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private fun parse(t: String) = Commands.parse(t, clients, now)

    @Test
    fun theSixCommands() {
        val add = assertIs<VoiceCommand.AddClient>(parse("Add a new client named Rahul"))
        assertEquals("Rahul", add.name)
        assertNull(add.phone) // not said → not filled

        val req = assertIs<VoiceCommand.AddRequirement>(parse("Rahul is looking for a 2 BHK in Andheri under 1 crore"))
        assertEquals("c1", (req.who as Who.One).client.id)
        val d = req.extraction.draft
        assertEquals(PropertyCategory.BHK_2, d.category?.value)
        assertEquals(listOf("Andheri"), d.locations?.map { it.value })
        assertEquals(10_000_000L, d.budgetMax?.value)
        assertEquals("under 1 crore", d.budgetMax?.evidence)
        assertNull(d.transactionType) // rent or buy wasn't said
        assertNull(d.budgetMin)
        assertNull(d.furnishing)

        val find = assertIs<VoiceCommand.FindClients>(parse("Show me clients looking for a 2 BHK in Bandra"))
        assertEquals("2 BHK Bandra", find.search)
        assertEquals(PropertyCategory.BHK_2, find.category)

        val fu = assertIs<VoiceCommand.FollowUp>(parse("Schedule a follow-up with Rahul tomorrow"))
        assertEquals("c1", (fu.who as Who.One).client.id)
        assertEquals(now.toLocalDate().plusDays(1).atTime(10, 0), fu.at)
        assertEquals("tomorrow at 10:00", fu.whenLabel)

        val leads = assertIs<VoiceCommand.PortalLeads>(parse("Show me today's Housing.com leads"))
        assertEquals(Portal.HOUSING_COM, leads.portal)
        assertEquals(DateFilter.TODAY, leads.range)

        val props = assertIs<VoiceCommand.FindProperties>(parse("Find properties under 80 lakhs in Powai"))
        assertEquals(8_000_000L, props.maxPrice)
        assertEquals("Powai", props.locality)
        assertNull(props.transactionType)
        assertNull(props.category)
    }

    @Test
    fun whoTheCommandIsAbout() {
        // Two Amits: nothing is picked.
        val amb = assertIs<VoiceCommand.FollowUp>(parse("Follow up with Amit tomorrow"))
        assertEquals(listOf("c3", "c4"), (amb.who as Who.Several).candidates.map { it.id })
        // The full name settles it.
        assertEquals("c4", ((parse("Follow up with Amit Shah tomorrow") as VoiceCommand.FollowUp).who as Who.One).client.id)
        // Unknown name.
        assertIs<Who.Nobody>((parse("Follow up with Kavya tomorrow") as VoiceCommand.FollowUp).who)
        // Hindi / Marathi suffixes.
        assertEquals("c2", ((parse("Priya को कल follow up") as VoiceCommand.FollowUp).who as Who.One).client.id)
        // A requirement for someone who isn't a client: nobody is picked; the name said is kept.
        val r = assertIs<VoiceCommand.AddRequirement>(parse("Kavya wants a 1 BHK on rent in Malad"))
        assertIs<Who.Nobody>(r.who)
        assertEquals("Kavya", r.nameSaid)
        assertEquals(TransactionType.RENT, r.extraction.draft.transactionType?.value)
    }

    @Test
    fun followUpTimes() {
        assertEquals(now.toLocalDate().plusDays(1).atTime(17, 0), (parse("follow up with Rahul tomorrow at 5 pm") as VoiceCommand.FollowUp).at)
        assertEquals(now.toLocalDate().plusDays(1).atTime(17, 30), (parse("remind me to call Rahul tomorrow at 5:30") as VoiceCommand.FollowUp).at)
        assertEquals(now.toLocalDate().plusDays(2).atTime(11, 0), (parse("Priya follow-up parso 11 am") as VoiceCommand.FollowUp).at)
        // Today at a time already past → in an hour.
        assertEquals(now.toLocalDateTime().plusHours(1), (parse("follow up with Rahul today at 9 am") as VoiceCommand.FollowUp).at)
    }

    @Test
    fun otherCommands() {
        val st = assertIs<VoiceCommand.LeadStatus>(parse("Mark Rahul as contacted"))
        assertEquals(PortalLeadStatus.CONTACTED, st.status)
        assertEquals("c1", (st.who as Who.One).client.id)
        assertEquals(DateFilter.YESTERDAY, (parse("99acres leads from yesterday") as VoiceCommand.PortalLeads).range)
        assertNull((parse("show me the leads") as VoiceCommand.PortalLeads).portal) // which portal? asked, not guessed
        val listings = listOf(ListingChoice("l1", Portal.ACRES_99, "2 BHK Apartment", "Andheri West", "A1"), ListingChoice("l2", Portal.HOUSING_COM, "1 BHK", "Mulund West", "H1"))
        val who = Commands.parse("Show everyone interested in the Andheri property", clients, now, listings)
        assertEquals(listOf("l1"), (who as VoiceCommand.ListingClients).listings.map { it.id })
        assertIs<VoiceCommand.Unknown>(parse("what's the weather"))
        val phone = assertIs<VoiceCommand.AddClient>(parse("Add new client Sameer Desai 98200 33001"))
        assertEquals("Sameer Desai", phone.name)
        assertEquals("9820033001", phone.phone)
    }

    @Test
    fun requirementRules() {
        val d = PhoneRules.extract("Looking to buy a 3 BHK in Powai or Andheri East, budget 2 to 2.5 crore, semi furnished, 2 car parking, higher floor, ready to move").draft
        assertEquals(TransactionType.BUY, d.transactionType?.value)
        assertEquals(PropertyCategory.BHK_3, d.category?.value)
        assertEquals(20_000_000L, d.budgetMin?.value)
        assertEquals(25_000_000L, d.budgetMax?.value)
        assertEquals(listOf("Powai", "Andheri East"), d.locations?.map { it.value })
        assertEquals(listOf(Furnishing.SEMI_FURNISHED), d.furnishing?.value)
        assertEquals(2, d.minParking?.value)
        assertEquals(listOf(FloorBand.HIGHER), d.floorPreference?.value)

        val h = PhoneRules.extract("Rahul ko Bandra mein 2 bhk kiraye pe chahiye, 60 hazaar tak").draft
        assertEquals(TransactionType.RENT, h.transactionType?.value)
        assertEquals(60_000L, h.budgetMax?.value)
        assertEquals(listOf("Bandra"), h.locations?.map { it.value })

        val bkc = PhoneRules.extract("office in Bandra Kurla Complex").draft
        assertEquals(listOf("Bandra Kurla Complex"), bkc.locations?.map { it.value })
        assertEquals(PropertyCategory.COMMERCIAL, bkc.category?.value)

        // Exact floors aren't saved; unclear things are warnings; nothing extra is filled.
        val x = PhoneRules.extract("2 or 3 BHK, 5th floor ke upar, not in Kurla, Chembur")
        assertNull(x.draft.category)
        assertNull(x.draft.floorPreference)
        assertEquals(listOf("Chembur"), x.draft.locations?.map { it.value })
        assertTrue(x.warnings.any { "5th floor" in it } && x.warnings.any { "Kurla" in it } && x.warnings.any { "size" in it }, x.warnings.toString())
        assertEquals(com.brokerbuddy.core.model.RequirementDraft(), PhoneRules.extract("call him back later").draft)
        // Every value's evidence is in the words said.
        val words = "Rahul is looking for a 2 BHK in Andheri under 1 crore"
        val e = PhoneRules.extract(words).draft
        listOfNotNull(e.category?.evidence, e.budgetMax?.evidence, *e.locations.orEmpty().map { it.evidence }.toTypedArray()).forEach { assertTrue(it in words, it) }
    }
}
