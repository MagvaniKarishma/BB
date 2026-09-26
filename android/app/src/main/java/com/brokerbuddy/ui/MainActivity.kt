package com.brokerbuddy.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
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

    private fun routeFrom(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: readSharedFile(intent)
            if (!text.isNullOrBlank()) {
                SharedInbox.text = text
                return "whatsapp/import"
            }
        }
        return intent?.getStringExtra(EXTRA_ROUTE) ?: intent?.getStringExtra(EXTRA_CLIENT_ID)?.let { "client/$it" }
    }

    /** A WhatsApp "Export chat" arrives as a shared .txt file. */
    private fun readSharedFile(intent: Intent): String? {
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return null
        return runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readNBytesCompat(MAX_SHARED_BYTES)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val EXTRA_CLIENT_ID = "com.brokerbuddy.CLIENT_ID"
        const val EXTRA_ROUTE = "com.brokerbuddy.ROUTE"
        private const val MAX_SHARED_BYTES = 2 * 1024 * 1024
    }
}

/** Holds text shared into the app until the import screen reads it (too large for a route argument). */
object SharedInbox {
    @Volatile var text: String? = null

    fun take(): String? = text.also { text = null }
}
