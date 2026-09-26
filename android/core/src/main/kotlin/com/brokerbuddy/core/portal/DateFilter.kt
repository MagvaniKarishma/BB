package com.brokerbuddy.core.portal

import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.PortalListingSummary
import com.brokerbuddy.core.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Date filter on the portal lead screens (default Today). */
enum class DateFilter(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 Days"),
    CUSTOM("Custom"),
    ;

    companion object {
        /** From a voice command's range ("TODAY", "YESTERDAY", "LAST_7_DAYS"); Today otherwise. */
        fun of(name: String?): DateFilter = entries.firstOrNull { it.name == name && it != CUSTOM } ?: TODAY
    }
}

/** [from, to) in instants. */
data class DateWindow(val from: Instant, val to: Instant)

object DateWindows {
    /** Calendar days in [zone]; a custom range includes both end days. Null for Custom without dates. */
    fun of(filter: DateFilter, today: LocalDate, zone: ZoneId, customStart: LocalDate? = null, customEnd: LocalDate? = null): DateWindow? {
        fun day(d: LocalDate) = d.atStartOfDay(zone).toInstant()
        return when (filter) {
            DateFilter.TODAY -> DateWindow(day(today), day(today.plusDays(1)))
            DateFilter.YESTERDAY -> DateWindow(day(today.minusDays(1)), day(today))
            DateFilter.LAST_7_DAYS -> DateWindow(day(today.minusDays(6)), day(today.plusDays(1)))
            DateFilter.CUSTOM -> {
                if (customStart == null || customEnd == null) return null
                val (a, b) = if (customEnd.isBefore(customStart)) customEnd to customStart else customStart to customEnd
                DateWindow(day(a), day(b.plusDays(1)))
            }
        }
    }
}

/** Card text for a portal listing; only fields the source gave. */
object PortalListingText {
    fun title(l: PortalListingSummary): String =
        l.title ?: if (!l.identified) "Listing not identified" else l.category?.label ?: "Listing ${l.externalId ?: ""}".trim()

    fun location(l: PortalListingSummary): String? = l.locality

    fun price(l: PortalListingSummary): String? = l.price?.let {
        if (l.transactionType == TransactionType.RENT) "${Money.compact(it)}/month" else Money.compact(it)
    }

    fun counts(l: PortalListingSummary): String {
        val people = "${l.interestedClients} interested"
        return if (l.newLeads > 0) "$people · ${l.newLeads} new" else people
    }
}
