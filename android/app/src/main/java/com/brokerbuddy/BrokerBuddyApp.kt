package com.brokerbuddy

import android.app.Application
import com.brokerbuddy.data.ApiClient
import com.brokerbuddy.data.SessionStore
import com.brokerbuddy.notifications.ReminderNotifier
import com.brokerbuddy.notifications.ReminderSyncWorker

/** Manual dependency container; one instance per process. */
class AppContainer(app: Application) {
    val json = ApiClient.defaultJson()
    val sessionStore = SessionStore(app, json)
    val api = ApiClient(sessionStore, json)
}

class BrokerBuddyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderNotifier.createChannel(this)
        ReminderSyncWorker.schedulePeriodic(this)
    }
}
