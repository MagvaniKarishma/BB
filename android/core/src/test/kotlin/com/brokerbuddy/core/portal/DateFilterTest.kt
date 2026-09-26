package com.brokerbuddy.core.portal

import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.PortalListingSummary
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateFilterTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.parse("2026-09-26")

    @Test
    fun windowsAreWholeLocalDays() {
        assertEquals(
            DateWindow(Instant.parse("2026-09-25T18:30:00Z"), Instant.parse("2026-09-26T18:30:00Z")),
            DateWindows.of(DateFilter.TODAY, today, ist),
        )
        assertEquals(Instant.parse("2026-09-24T18:30:00Z"), DateWindows.of(DateFilter.YESTERDAY, today, ist)!!.from)
        assertEquals(Instant.parse("2026-09-19T18:30:00Z"), DateWindows.of(DateFilter.LAST_7_DAYS, today, ist)!!.from)
        assertNull(DateWindows.of(DateFilter.CUSTOM, today, ist))
        // Custom includes both end days, in either order.
        val w = DateWindows.of(DateFilter.CUSTOM, today, ist, LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-18"))!!
        assertEquals(Instant.parse("2026-09-17T18:30:00Z"), w.from)
        assertEquals(Instant.parse("2026-09-20T18:30:00Z"), w.to)
    }

    @Test
    fun rangeFromVoiceCommandDefaultsToToday() {
        assertEquals(DateFilter.YESTERDAY, DateFilter.of("YESTERDAY"))
        assertEquals(DateFilter.TODAY, DateFilter.of(null))
        assertEquals(DateFilter.TODAY, DateFilter.of("CUSTOM"))
    }

    @Test
    fun listingCardTextShowsOnlyWhatIsKnown() {
        val base = PortalListingSummary(
            id = "x", portal = Portal.ACRES_99, interestedClients = 2, newLeads = 1, totalLeads = 3, lastEnquiryAt = "2026-09-26T05:00:00Z",
        )
        assertEquals("Listing not identified", PortalListingText.title(base.copy(identified = false)))
        assertEquals("2 BHK", PortalListingText.title(base.copy(category = PropertyCategory.BHK_2)))
        assertEquals("2 BHK Apartment", PortalListingText.title(base.copy(title = "2 BHK Apartment")))
        assertNull(PortalListingText.price(base))
        assertEquals("₹70K/month", PortalListingText.price(base.copy(price = 70_000, transactionType = TransactionType.RENT)))
        assertEquals("2 interested · 1 new", PortalListingText.counts(base))
        assertEquals("2 interested", PortalListingText.counts(base.copy(newLeads = 0)))
    }
}
