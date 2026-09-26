package com.brokerbuddy.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.brokerbuddy.ui.theme.BrokerBuddyTheme

class MainActivity : ComponentActivity() {
    /** In-app route to open when launched from a notification or the caller card. */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingRoute.value = routeFrom(intent)
        setContent {
            BrokerBuddyTheme {
                BrokerBuddyNavHost(
                    openRoute = pendingRoute.value,
                    onRouteOpened = { pendingRoute.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)?.let { pendingRoute.value = it }
    }

    private fun routeFrom(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_ROUTE) ?: intent?.getStringExtra(EXTRA_CLIENT_ID)?.let { "client/$it" }

    companion object {
        const val EXTRA_CLIENT_ID = "com.brokerbuddy.CLIENT_ID"
        const val EXTRA_ROUTE = "com.brokerbuddy.ROUTE"
    }
}
