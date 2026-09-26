package com.brokerbuddy.core.phone

/**
 * Lightweight Indian phone normalisation used on-device (caller lookup, dialer and
 * WhatsApp links). The backend performs authoritative validation with libphonenumber.
 */
object PhoneNumbers {
    /** Returns +91XXXXXXXXXX for Indian mobiles/landlines, +<digits> for other international numbers, or null. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        var digits = trimmed.filter { it.isDigit() }
        val hadPlus = trimmed.startsWith("+")
        if (!hadPlus && digits.startsWith("00")) {
            return international(digits.drop(2))
        }
        if (hadPlus) return international(digits)
        if (digits.length == 12 && digits.startsWith("91")) digits = digits.drop(2)
        if (digits.length == 11 && digits.startsWith("0")) digits = digits.drop(1)
        return indian(digits)
    }

    private fun international(digits: String): String? = when {
        digits.startsWith("91") -> indian(digits.drop(2))
        // Country codes never start with 0.
        digits.length in 8..15 && digits.first() != '0' -> "+$digits"
        else -> null
    }

    private fun indian(national: String): String? =
        if (national.length == 10 && national.first() != '0') "+91$national" else null

    /**
     * An Indian mobile number typed next to a fixed "+91" prefix ("98200 12345",
     * "098200 12345", "919820012345") as E.164, or null. Mobiles start with 6–9.
     */
    fun indianMobile(typed: String): String? {
        var d = typed.filter { it.isDigit() }
        if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        return if (d.length == 10 && d.first() in '6'..'9') "+91$d" else null
    }

    /** Digits only, as required by wa.me links (e.g. 919820012345). */
    fun whatsAppDigits(e164: String): String = e164.filter { it.isDigit() }

    /** "+91 98200 12345" for display; other numbers are returned unchanged. */
    fun display(e164: String): String =
        if (e164.startsWith("+91") && e164.length == 13) {
            "+91 ${e164.substring(3, 8)} ${e164.substring(8)}"
        } else {
            e164
        }
}
