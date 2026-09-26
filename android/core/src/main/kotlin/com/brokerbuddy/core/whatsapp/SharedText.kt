package com.brokerbuddy.core.whatsapp

/** Helpers for text shared into the app from WhatsApp (share sheet). */
object SharedText {
    private val exportLine = Regex("""^‎?\[?\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4},?\s+\d{1,2}:\d{2}.*?[-\]]\s*[^:]{1,80}:\s""")

    /** True when the text looks like a WhatsApp "Export chat" transcript rather than one message. */
    fun looksLikeChatExport(text: String): Boolean =
        text.lineSequence().take(50).count { exportLine.containsMatchIn(it) } >= 2

    /** Participant names in an export, in order of first appearance. */
    fun participants(export: String): List<String> {
        val re = Regex("""^‎?\[?\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4},?\s+\d{1,2}:\d{2}(?::\d{2})?[\s  ]*(?:[ap]\.?\s?m\.?)?\]?\s*(?:[-–]\s*)?([^:]{1,80}?):\s""", RegexOption.IGNORE_CASE)
        return export.lineSequence().mapNotNull { re.find(it)?.groupValues?.get(1)?.replace("‎", "")?.trim() }.distinct().toList()
    }
}
