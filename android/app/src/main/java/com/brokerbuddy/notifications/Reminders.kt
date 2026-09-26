package com.brokerbuddy.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brokerbuddy.BrokerBuddyApp
import com.brokerbuddy.R
import com.brokerbuddy.core.model.Reminder
import com.brokerbuddy.core.model.ReminderStatus
import com.brokerbuddy.ui.MainActivity
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

object ReminderNotifier {
    const val CHANNEL_ID = "reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.reminder_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Permission is checked by canNotify(); lint can't see through the helper.
    @SuppressLint("MissingPermission")
    fun show(context: Context, reminderId: String, title: String, text: String?, clientId: String?) {
        if (!canNotify(context)) return // Fallback: the reminder remains visible in the Reminders tab.
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (clientId != null) putExtra(MainActivity.EXTRA_CLIENT_ID, clientId)
        }
        val pi = PendingIntent.getActivity(
            context, reminderId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(reminderId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between check and notify; nothing else to do.
        }
    }
}

/** Fires at a reminder's due time and posts the notification. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        ReminderNotifier.show(
            context,
            id,
            intent.getStringExtra(EXTRA_TITLE) ?: "Follow-up due",
            intent.getStringExtra(EXTRA_TEXT),
            intent.getStringExtra(EXTRA_CLIENT_ID),
        )
        NotifiedStore(context).markNotified(id)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CLIENT_ID = "clientId"
    }
}

/** Remembers which reminders were already notified so overdue ones don't repeat on every sync. */
private class NotifiedStore(context: Context) {
    private val prefs = context.getSharedPreferences("notified_reminders", Context.MODE_PRIVATE)
    fun wasNotified(id: String) = prefs.contains(id)
    fun markNotified(id: String) = prefs.edit().putLong(id, System.currentTimeMillis()).apply()
    fun prune(keep: Set<String>) {
        val editor = prefs.edit()
        prefs.all.keys.filterNot { it in keep }.forEach { editor.remove(it) }
        editor.apply()
    }
}

object ReminderScheduler {
    private fun pendingIntent(context: Context, r: Reminder, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = "com.brokerbuddy.REMINDER.${r.id}"
            putExtra(ReminderAlarmReceiver.EXTRA_ID, r.id)
            putExtra(ReminderAlarmReceiver.EXTRA_TITLE, r.title)
            putExtra(ReminderAlarmReceiver.EXTRA_TEXT, r.client?.name?.let { "Client: $it" } ?: r.note)
            putExtra(ReminderAlarmReceiver.EXTRA_CLIENT_ID, r.clientId)
        }
        return PendingIntent.getBroadcast(context, r.id.hashCode(), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Schedules a notification at the reminder's due time. Uses an inexact
     * allow-while-idle alarm, which needs no special permission; Android may
     * delay it by a few minutes in Doze, which is acceptable for follow-ups.
     */
    fun schedule(context: Context, r: Reminder) {
        val due = runCatching { Instant.parse(r.dueAt).toEpochMilli() }.getOrNull() ?: return
        val pi = pendingIntent(context, r, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
    }

    fun cancel(context: Context, r: Reminder) {
        val pi = pendingIntent(context, r, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }
}

/**
 * Periodically pulls pending reminders from the server and (re)arms alarms for
 * the next two days. Overdue reminders not yet notified are shown immediately.
 * Also covers device reboots, since WorkManager persists its schedule.
 */
class ReminderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BrokerBuddyApp).container
        if (!container.sessionStore.current().isLoggedIn) return Result.success()
        val now = Instant.now()
        val reminders = container.api.call {
            reminders(
                status = ReminderStatus.PENDING,
                from = now.minus(7, ChronoUnit.DAYS).toString(),
                to = now.plus(2, ChronoUnit.DAYS).toString(),
            )
        }.getOrElse { return Result.retry() }.reminders

        val notified = NotifiedStore(applicationContext)
        for (r in reminders) {
            val due = runCatching { Instant.parse(r.dueAt) }.getOrNull() ?: continue
            if (due.isAfter(now)) {
                ReminderScheduler.schedule(applicationContext, r)
            } else if (!notified.wasNotified(r.id)) {
                ReminderNotifier.show(applicationContext, r.id, r.title, r.client?.name?.let { "Overdue · $it" }, r.clientId)
                notified.markNotified(r.id)
            }
        }
        notified.prune(reminders.map { it.id }.toSet())
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "reminder-sync"
        private const val NOW = "reminder-sync-now"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReminderSyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
