package com.brokerbuddy.core.caller

import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.CallerClient
import com.brokerbuddy.core.model.CallerDirectory
import com.brokerbuddy.core.model.DirectoryEntry
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryStatus
import com.brokerbuddy.core.phone.PhoneNumbers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fast phone → client lookup over the cached directory; tolerant of incoming number formats. */
class DirectoryIndex(directory: CallerDirectory?) {
    private val byNumber: Map<String, DirectoryEntry> = directory?.entries.orEmpty().associateBy { it.e164 }

    val size: Int get() = byNumber.size

    fun find(rawNumber: String?): DirectoryEntry? = rawNumber?.let(PhoneNumbers::normalize)?.let(byNumber::get)
}

/** What the caller notification / overlay / screen shows. Plain text so every surface can render it. */
data class CallerCard(
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val lines: List<String>,
    val number: String?,
    val clientId: String? = null,
    /** Set when exactly one open inquiry exists — "Matches" can jump straight to it. */
    val singleInquiryId: String? = null,
) {
    enum class Kind { KNOWN, KNOWN_CACHED, UNKNOWN }
}

object CallerCards {
    private const val MAX_INQUIRIES = 3

    fun inquiryLine(i: Inquiry): String {
        val parts = mutableListOf("${i.transactionType.label} · ${i.category.label}")
        Money.range(i.budgetMin, i.budgetMax)?.let(parts::add)
        if (i.locations.isNotEmpty()) parts += i.locations.take(3).joinToString(", ") + if (i.locations.size > 3) " +${i.locations.size - 3}" else ""
        if (i.status == InquiryStatus.PAUSED) parts += "paused"
        i.matchCount?.let { parts += if (it == 1) "1 match" else "$it matches" }
        return parts.joinToString(" · ")
    }

    fun known(c: CallerClient, now: Instant, zone: ZoneId): CallerCard {
        val lines = mutableListOf<String>()
        if (c.inquiries.isEmpty()) {
            lines += "No open requirements" + if (c.closedInquiries > 0) " (${c.closedInquiries} closed)" else ""
        } else {
            c.inquiries.take(MAX_INQUIRIES).forEach { lines += inquiryLine(it) }
            if (c.inquiries.size > MAX_INQUIRIES) lines += "+${c.inquiries.size - MAX_INQUIRIES} more requirements"
        }
        c.lastInteraction?.let { li ->
            val text = li.text.replace(Regex("\\s+"), " ").trim()
            val clipped = if (text.length > 90) text.take(87) + "…" else text
            val by = li.by?.let { " by $it" } ?: ""
            lines += "Last: “$clipped” · ${relative(li.at, now, zone)}$by"
        }
        c.reminders.firstOrNull()?.let { r ->
            val due = runCatching { Instant.parse(r.dueAt) }.getOrNull()
            lines += if (due != null && due.isBefore(now)) {
                "Follow-up overdue: ${r.title}"
            } else {
                "Next follow-up: ${r.title} · ${relative(r.dueAt, now, zone)}"
            }
        }
        val subtitle = listOfNotNull(c.status.label, c.leadSource.label, c.assignedTo?.name?.let { "Agent: $it" }).joinToString(" · ")
        return CallerCard(
            kind = CallerCard.Kind.KNOWN,
            title = c.name,
            subtitle = subtitle,
            lines = lines,
            number = c.primaryPhone,
            clientId = c.id,
            singleInquiryId = c.inquiries.singleOrNull()?.id,
        )
    }

    /** Shown instantly from the on-device directory while details load (or when offline). */
    fun cached(entry: DirectoryEntry, number: String?): CallerCard = CallerCard(
        kind = CallerCard.Kind.KNOWN_CACHED,
        title = entry.name,
        subtitle = "BrokerBuddy client",
        lines = listOf("Loading requirements…"),
        number = number ?: entry.e164,
        clientId = entry.clientId,
    )

    fun unknown(number: String?): CallerCard = CallerCard(
        kind = CallerCard.Kind.UNKNOWN,
        title = number?.let(PhoneNumbers::display) ?: "Unknown number",
        subtitle = "Not in BrokerBuddy",
        lines = listOf("Tap “Create client” if this is a new lead"),
        number = number,
    )

    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    // Fixed names: locale data differs between JDKs and Android versions ("Sep" vs "Sept").
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "just now", "5 min ago", "today 4:30 PM", "yesterday", "3 days ago", "tomorrow 10:00 AM", "12 Oct". */
    fun relative(iso: String, now: Instant, zone: ZoneId): String {
        val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
        val d = Duration.between(t, now)
        val today = LocalDate.ofInstant(now, zone)
        val day = LocalDate.ofInstant(t, zone)
        val time = timeFormat.format(t.atZone(zone))
        return when {
            !d.isNegative && d.toMinutes() < 1 -> "just now"
            !d.isNegative && d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            day == today -> "today $time"
            day == today.minusDays(1) -> "yesterday"
            day == today.plusDays(1) -> "tomorrow $time"
            !d.isNegative && d.toDays() < 7 -> "${today.toEpochDay() - day.toEpochDay()} days ago"
            else -> "${day.dayOfMonth} ${months[day.monthValue - 1]}"
        }
    }
}
