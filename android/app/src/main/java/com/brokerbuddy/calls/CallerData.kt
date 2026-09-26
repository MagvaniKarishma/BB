package com.brokerbuddy.calls

import android.content.Context
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
import com.brokerbuddy.core.caller.DirectoryIndex
import com.brokerbuddy.core.model.CallerDirectory
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** User preferences for the caller screen. */
class CallerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("caller_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Show a "create client" prompt for numbers not in BrokerBuddy (new leads often call first). */
    var showUnknown: Boolean
        get() = prefs.getBoolean("show_unknown", true)
        set(v) = prefs.edit().putBoolean("show_unknown", v).apply()

    /** Use the floating card when "Display over other apps" is granted. */
    var useOverlay: Boolean
        get() = prefs.getBoolean("use_overlay", true)
        set(v) = prefs.edit().putBoolean("use_overlay", v).apply()
}

/**
 * On-device copy of the brokerage's phone → client-name directory, so a known caller's
 * name shows instantly and offline. Stored in app-private storage; cleared on sign-out.
 * Contains names and phone numbers only — requirements are always fetched live.
 */
object CallerDirectoryStore {
    private const val FILE = "caller_directory.json"
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var index: DirectoryIndex? = null

    fun get(context: Context): DirectoryIndex = index ?: synchronized(this) {
        index ?: DirectoryIndex(read(context)).also { index = it }
    }

    private fun read(context: Context): CallerDirectory? = runCatching {
        json.decodeFromString(CallerDirectory.serializer(), File(context.filesDir, FILE).readText())
    }.getOrNull()

    fun save(context: Context, directory: CallerDirectory) {
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(json.encodeToString(CallerDirectory.serializer(), directory))
        tmp.renameTo(File(context.filesDir, FILE))
        index = DirectoryIndex(directory)
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        index = DirectoryIndex(null)
    }
}

/** Refreshes the directory every few hours, and on demand after clients change. */
class CallerDirectorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BrokerBuddyApp).container
        if (!container.sessionStore.current().isRealAccount) {
            CallerDirectoryStore.clear(applicationContext)
            return Result.success()
        }
        val directory = container.api.call { callerDirectory() }.getOrElse { return Result.retry() }
        CallerDirectoryStore.save(applicationContext, directory)
        return Result.success()
    }

    companion object {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CallerDirectorySyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("caller-directory", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CallerDirectorySyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("caller-directory-now", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
