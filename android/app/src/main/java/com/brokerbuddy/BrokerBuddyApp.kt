package com.brokerbuddy

import android.app.Application
import com.brokerbuddy.calls.CallerDirectorySyncWorker
import com.brokerbuddy.calls.CallerNotifier
import com.brokerbuddy.data.ApiClient
import com.brokerbuddy.data.DemoInterceptor
import com.brokerbuddy.data.SessionStore
import com.brokerbuddy.notifications.ReminderNotifier
import com.brokerbuddy.notifications.ReminderSyncWorker

/** Manual dependency container; one instance per process. */
class AppContainer(app: Application) {
    val json = ApiClient.defaultJson()
    val sessionStore = SessionStore(app, json)
    val demo = DemoInterceptor(app, sessionStore)
    val api = ApiClient(sessionStore, json, demo)
}

class BrokerBuddyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderNotifier.createChannel(this)
        CallerNotifier.createChannel(this)
        ReminderSyncWorker.schedulePeriodic(this)
        CallerDirectorySyncWorker.schedulePeriodic(this)
    }
}
