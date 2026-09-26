package com.brokerbuddy.calls

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.brokerbuddy.BrokerBuddyApp
import com.brokerbuddy.R
import com.brokerbuddy.core.caller.CallerCard
import com.brokerbuddy.core.model.AddNoteRequest
import com.brokerbuddy.core.model.NoteSource
import com.brokerbuddy.notifications.ReminderNotifier
import com.brokerbuddy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** In-app routes opened from caller surfaces (see Navigation.kt). */
object CallerRoutes {
    fun caller(number: String?) = "caller?phone=${Uri.encode(number.orEmpty())}"
    fun client(id: String) = "client/$id"
    fun newClient(number: String?) = "client/new?phone=${Uri.encode(number.orEmpty())}&source=PHONE_CALL"
    fun matches(card: CallerCard) = card.singleInquiryId?.let { "inquiry/$it?tab=1" } ?: card.clientId?.let(::client)
}

fun openRouteIntent(context: Context, route: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = "com.brokerbuddy.OPEN_ROUTE"
        data = Uri.parse("brokerbuddy://route/${Uri.encode(route)}") // unique per route for PendingIntents
        putExtra(MainActivity.EXTRA_ROUTE, route)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

/**
 * Heads-up notification for incoming calls — the most reliable surface: it needs only
 * notification permission and works on every supported Android version.
 */
object CallerNotifier {
    const val CHANNEL_ID = "caller_id"
    const val NOTIFICATION_ID = 4242
    const val KEY_NOTE = "note"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Caller screen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows who is calling and what they are looking for"
            setSound(null, null) // the phone is already ringing
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun activity(context: Context, route: String, code: Int) = PendingIntent.getActivity(
        context, code, openRouteIntent(context, route), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission") // checked by ReminderNotifier.canNotify
    fun show(context: Context, card: CallerCard) {
        if (!ReminderNotifier.canNotify(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_caller)
            .setContentTitle(card.title)
            .setContentText(card.lines.firstOrNull() ?: card.subtitle)
            .setSubText(card.subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60 * 1000L)
            .setContentIntent(activity(context, CallerRoutes.caller(card.number), 1))

        when (card.kind) {
            CallerCard.Kind.UNKNOWN ->
                builder.addAction(0, "Create client", activity(context, CallerRoutes.newClient(card.number), 2))
            else -> {
                val clientId = card.clientId!!
                builder.addAction(0, "Profile", activity(context, CallerRoutes.client(clientId), 3))
                CallerRoutes.matches(card)?.let {
                    builder.addAction(0, if (card.singleInquiryId != null) "Matches" else "Requirements", activity(context, it, 4))
                }
                builder.addAction(noteAction(context, clientId, card.title))
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission revoked meanwhile.
        }
    }

    /** Inline "Add note" reply — saved to the client without opening the app. */
    private fun noteAction(context: Context, clientId: String, name: String): NotificationCompat.Action {
        val intent = Intent(context, NoteReplyReceiver::class.java).apply {
            putExtra(NoteReplyReceiver.EXTRA_CLIENT_ID, clientId)
            putExtra(NoteReplyReceiver.EXTRA_NAME, name)
        }
        // RemoteInput needs a mutable PendingIntent; the intent is explicit, so this is safe.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, clientId.hashCode(), intent, flags)
        val input = RemoteInput.Builder(KEY_NOTE).setLabel("Note about this call").build()
        return NotificationCompat.Action.Builder(0, "Add note", pi).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    @SuppressLint("MissingPermission")
    fun replace(context: Context, title: String, text: String, route: String?) {
        if (!ReminderNotifier.canNotify(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_caller)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(if (route == null) 10_000L else 30 * 60 * 1000L)
        route?.let { builder.setContentIntent(activity(context, it, 5)) }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }
}

/** Saves a note typed into the caller notification. */
class NoteReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CallerNotifier.KEY_NOTE)?.toString()?.trim()
        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "client"
        if (text.isNullOrEmpty() || clientId == null) return
        val pending = goAsync()
        val app = context.applicationContext as BrokerBuddyApp
        scope.launch {
            try {
                app.container.api.call { addClientNote(clientId, AddNoteRequest(text, NoteSource.NOTIFICATION)) }
                    .onSuccess { CallerNotifier.replace(context, "Note saved", "Added to $name", null) }
                    .onFailure {
                        CallerNotifier.replace(context, "Couldn't save note", "${it.message} — tap to add it in the app", CallerRoutes.client(clientId))
                    }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CLIENT_ID = "clientId"
        const val EXTRA_NAME = "name"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
