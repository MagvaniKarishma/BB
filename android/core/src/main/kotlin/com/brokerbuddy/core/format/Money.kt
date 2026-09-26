package com.brokerbuddy.core.format

import java.math.BigDecimal
import java.math.RoundingMode

/** Indian-style rupee formatting and parsing (lakh = 1,00,000; crore = 1,00,00,000). */
object Money {
    const val LAKH = 100_000L
    const val CRORE = 10_000_000L

    /** "₹1,25,00,000" – Indian digit grouping. */
    fun full(rupees: Long): String {
        val negative = rupees < 0
        val digits = kotlin.math.abs(rupees).toString()
        val grouped = if (digits.length <= 3) {
            digits
        } else {
            val last3 = digits.takeLast(3)
            val rest = digits.dropLast(3)
            rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
        }
        return (if (negative) "-₹" else "₹") + grouped
    }

    /** "₹1.25 Cr", "₹75 L", "₹65K", "₹900". */
    fun compact(rupees: Long): String = when {
        rupees >= CRORE -> "₹${trim(rupees, CRORE)} Cr"
        rupees >= LAKH -> "₹${trim(rupees, LAKH)} L"
        rupees >= 1_000 -> "₹${trim(rupees, 1_000)}K"
        else -> "₹$rupees"
    }

    private fun trim(value: Long, unit: Long): String =
        BigDecimal(value).divide(BigDecimal(unit), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private val shorthand = Regex(
        """^\s*₹?\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(cr|crore|crores|l|lac|lakh|lakhs|k|thousand)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses what brokers actually type: "65000", "65,000", "65k", "75 L", "1.2 cr", "₹2 crore".
     * Returns null for anything it cannot parse unambiguously.
     */
    fun parse(input: String): Long? {
        val m = shorthand.matchEntire(input) ?: return null
        val number = m.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val multiplier = when (m.groupValues[2].lowercase()) {
            "" -> 1L
            "k", "thousand" -> 1_000L
            "l", "lac", "lakh", "lakhs" -> LAKH
            else -> CRORE
        }
        val result = number.multiply(BigDecimal(multiplier))
        return runCatching { result.setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
    }

    /** "₹50K – ₹70K", "Up to ₹1.5 Cr", "From ₹80 L", or null when no budget given. */
    fun range(min: Long?, max: Long?): String? = when {
        min != null && max != null -> "${compact(min)} – ${compact(max)}"
        max != null -> "Up to ${compact(max)}"
        min != null -> "From ${compact(min)}"
        else -> null
    }
}
