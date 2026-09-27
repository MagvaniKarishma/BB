package com.brokerbuddy.ui.voice

import com.brokerbuddy.core.model.Extraction
import java.util.UUID

/**
 * Hands what a voice command understood (a requirement draft) to the form that reviews it.
 * Kept in memory only, and taken once: nothing is saved until the broker taps Save on the form.
 */
object VoiceHandoff {
    private val pending = mutableMapOf<String, Extraction>()

    @Synchronized
    fun put(extraction: Extraction): String = UUID.randomUUID().toString().also { pending[it] = extraction }

    @Synchronized
    fun take(token: String?): Extraction? = token?.let { pending.remove(it) }
}
