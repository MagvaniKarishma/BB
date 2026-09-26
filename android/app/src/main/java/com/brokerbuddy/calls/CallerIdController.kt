package com.brokerbuddy.calls

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.brokerbuddy.BrokerBuddyApp
import com.brokerbuddy.core.caller.CallerCard
import com.brokerbuddy.core.caller.CallerCards
import com.brokerbuddy.core.phone.PhoneNumbers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Turns an incoming number into a caller card:
 *  1. instantly from the on-device directory (name only, works offline),
 *  2. then the full card from the server (requirements, last conversation, follow-ups).
 * Shown as a heads-up notification, plus a floating card when the user allowed it.
 */
object CallerIdController {
    private const val TAG = "CallerId"
    private const val LOOKUP_TIMEOUT_MS = 8_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun onIncomingCall(context: Context, rawNumber: String?) {
        val app = context.applicationContext as BrokerBuddyApp
        val settings = CallerSettings(app)
        if (!settings.enabled) return
        scope.launch {
            if (!app.container.sessionStore.current().isRealAccount) return@launch
            val number = rawNumber?.let(PhoneNumbers::normalize)
            if (rawNumber == null) return@launch // withheld number: nothing to identify

            val cached = CallerDirectoryStore.get(app).find(rawNumber)
            if (cached != null) show(app, settings, CallerCards.cached(cached, number))

            val lookup = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                app.container.api.call { callerLookup(rawNumber) }
            }?.getOrNull()

            val card: CallerCard? = when {
                lookup?.client != null -> CallerCards.known(lookup.client!!, Instant.now(), ZoneId.systemDefault())
                lookup != null -> if (lookup.number != null && settings.showUnknown) CallerCards.unknown(lookup.number) else null
                // Server unreachable:
                cached != null -> CallerCards.cached(cached, number).copy(lines = listOf("Offline — details will load when you open the profile"))
                settings.showUnknown && number != null ->
                    CallerCards.unknown(number).copy(subtitle = "Couldn't reach BrokerBuddy · not in offline list")
                else -> null
            }
            if (card != null) show(app, settings, card) else if (cached != null) CallerOverlay.hide()
            Log.d(TAG, "caller card: ${card?.kind}")
        }
    }

    private fun show(context: Context, settings: CallerSettings, card: CallerCard) {
        CallerNotifier.show(context, card)
        if (settings.useOverlay && Settings.canDrawOverlays(context)) CallerOverlay.show(context, card)
    }
}
