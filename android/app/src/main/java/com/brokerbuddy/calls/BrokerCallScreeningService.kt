package com.brokerbuddy.calls

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * Receives incoming calls through Android's supported call-screening API. The system
 * binds this service only after the user makes BrokerBuddy the "call screening app"
 * (RoleManager.ROLE_CALL_SCREENING, Android 10+).
 *
 * BrokerBuddy never blocks, silences or rejects calls: it immediately tells Android to
 * let the call through unchanged, then looks the number up in the background.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class BrokerCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        // Respond first: Telecom waits (up to a few seconds) for this before ringing.
        respondToCall(callDetails, CallResponse.Builder().build())

        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return
        // null/empty for private or withheld numbers — we show nothing in that case.
        val number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        CallerIdController.onIncomingCall(applicationContext, number)
    }
}
