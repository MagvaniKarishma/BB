package com.brokerbuddy.core.message

import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.TransactionType

/** A ready-made WhatsApp message. The app only types it into WhatsApp; the broker sends it. */
data class MessageTemplate(val label: String, val text: String)

/**
 * WhatsApp message templates for a client. They use only what BrokerBuddy knows (the client's first
 * name, the broker's name and, when there is one, the client's active requirement) — nothing is
 * made up, and a template that needs something missing isn't offered.
 */
object MessageTemplates {
    fun forClient(clientName: String, brokerName: String?, requirement: Inquiry?): List<MessageTemplate> {
        val first = clientName.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("Caller") }
        val hi = if (first != null) "Hi $first" else "Hi"
        val me = brokerName?.trim()?.takeIf { it.isNotEmpty() }?.let { " this is $it," } ?: ""
        val wants = requirement?.let(::describe)
        return listOfNotNull(
            MessageTemplate("Introduction", "$hi,$me thanks for your enquiry. When is a good time to talk about what you're looking for?"),
            wants?.let { MessageTemplate("Share options", "$hi, I have a few $it options that fit what you're looking for. Shall I send you the details?") },
            MessageTemplate("Site visit", "$hi, would you like to see the property this week? Let me know a day and time that suits you."),
            MessageTemplate("Follow-up", "$hi, just following up on your property search. Is there anything else you'd like me to look for?"),
        )
    }

    /** "2 BHK for rent in Andheri West" — only the parts that are recorded. */
    fun describe(i: Inquiry): String = listOfNotNull(
        i.category.label,
        if (i.transactionType == TransactionType.RENT) "for rent" else "to buy",
        i.locations.firstOrNull()?.let { "in $it" },
    ).joinToString(" ")
}
