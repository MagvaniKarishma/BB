package com.brokerbuddy.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.brokerbuddy.ui.theme.BrokerBuddyTheme

class MainActivity : ComponentActivity() {
    /** Client to open when launched from a reminder notification. */
    private val pendingClientId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingClientId.value = intent.getStringExtra(EXTRA_CLIENT_ID)
        setContent {
            BrokerBuddyTheme {
                BrokerBuddyNavHost(
                    openClientId = pendingClientId.value,
                    onClientOpened = { pendingClientId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CLIENT_ID)?.let { pendingClientId.value = it }
    }

    companion object {
        const val EXTRA_CLIENT_ID = "com.brokerbuddy.CLIENT_ID"
    }
}
