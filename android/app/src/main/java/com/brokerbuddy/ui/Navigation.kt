package com.brokerbuddy.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.data.Session
import com.brokerbuddy.ui.auth.LoginScreen
import com.brokerbuddy.ui.clients.ClientDetailScreen
import com.brokerbuddy.ui.clients.ClientFormScreen
import com.brokerbuddy.ui.clients.ClientListScreen
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.dashboard.DashboardScreen
import com.brokerbuddy.ui.inquiries.InquiryDetailScreen
import com.brokerbuddy.ui.inquiries.InquiryFormScreen
import com.brokerbuddy.ui.inquiries.InquiryListScreen
import com.brokerbuddy.ui.properties.PropertyDetailScreen
import com.brokerbuddy.ui.properties.PropertyFormScreen
import com.brokerbuddy.ui.properties.PropertyListScreen
import com.brokerbuddy.ui.reminders.RemindersScreen
import com.brokerbuddy.ui.settings.SettingsScreen
import com.brokerbuddy.ui.voice.VoiceNoteScreen
import com.brokerbuddy.ui.caller.CallerScreen
import com.brokerbuddy.calls.CallerDirectoryStore
import com.brokerbuddy.calls.CallerDirectorySyncWorker
import com.brokerbuddy.calls.CallerRoutes
import com.brokerbuddy.core.model.LeadSource
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

object Routes {
    const val DASHBOARD = "dashboard"
    const val CLIENTS = "clients"
    const val PROPERTIES = "properties"
    const val REMINDERS = "reminders"
    const val SETTINGS = "settings"
    const val CLIENT_NEW = "client/new"
    fun client(id: String) = "client/$id"
    fun clientEdit(id: String) = "client/$id/edit"
    fun inquiryNew(clientId: String) = "inquiry/new/$clientId"
    fun inquiry(id: String) = "inquiry/$id"
    fun inquiryEdit(id: String) = "inquiry/$id/edit"
    fun inquiryList(type: TransactionType, category: PropertyCategory) = "inquiries/${type.name}/${category.name}"
    const val PROPERTY_NEW = "property/new"
    fun property(id: String) = "property/$id"
    fun propertyEdit(id: String) = "property/$id/edit"
    fun voice(clientId: String, inquiryId: String? = null, noteId: String? = null) =
        "voice/$clientId?inquiryId=${inquiryId.orEmpty()}&noteId=${noteId.orEmpty()}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Dashboard", Icons.Filled.Dashboard),
    Tab(Routes.CLIENTS, "Clients", Icons.Filled.People),
    Tab(Routes.PROPERTIES, "Properties", Icons.Filled.Apartment),
    Tab(Routes.REMINDERS, "Follow-ups", Icons.Filled.Alarm),
)

@Composable
fun BrokerBuddyNavHost(openRoute: String?, onRouteOpened: () -> Unit) {
    val container = appContainer()
    val session by container.sessionStore.session.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // A revoked/expired token signs the user out instead of failing every request.
        container.api.onUnauthorized = { scope.launch { container.sessionStore.signOut() } }
    }

    when (val s: Session? = session) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> if (!s.isLoggedIn) LoginScreen() else MainScaffold(openRoute, onRouteOpened)
    }

    // Keep the on-device caller directory in step with the signed-in account.
    val context = LocalContext.current
    val loggedIn = session?.isLoggedIn
    LaunchedEffect(loggedIn) {
        when (loggedIn) {
            true -> CallerDirectorySyncWorker.syncNow(context)
            false -> CallerDirectoryStore.clear(context)
            null -> Unit
        }
    }
}

@Composable
private fun MainScaffold(openRoute: String?, onRouteOpened: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val back: () -> Unit = { nav.popBackStack() }
    RequestNotificationPermissionOnce()

    LaunchedEffect(openRoute) {
        if (openRoute != null) {
            runCatching { nav.navigate(openRoute) }
            onRouteOpened()
        }
    }

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(padding)) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onTile = { type, category -> nav.navigate(Routes.inquiryList(type, category)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onReminders = { nav.navigateTab(Routes.REMINDERS) },
                )
            }
            composable(Routes.CLIENTS) {
                ClientListScreen(
                    onClient = { nav.navigate(Routes.client(it)) },
                    onAdd = { nav.navigate(Routes.CLIENT_NEW) },
                )
            }
            composable(Routes.PROPERTIES) {
                PropertyListScreen(
                    onProperty = { nav.navigate(Routes.property(it)) },
                    onAdd = { nav.navigate(Routes.PROPERTY_NEW) },
                )
            }
            composable(Routes.REMINDERS) {
                RemindersScreen(onClient = { nav.navigate(Routes.client(it)) })
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = back) }

            composable(
                "client/new?phone={phone}&source={source}",
                listOf(
                    navArgument("phone") { type = NavType.StringType; defaultValue = "" },
                    navArgument("source") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                ClientFormScreen(
                    clientId = null,
                    initialPhone = entry.arguments?.getString("phone")?.ifEmpty { null },
                    initialLeadSource = entry.arguments?.getString("source")?.let { s -> LeadSource.entries.firstOrNull { it.name == s } },
                    onBack = back,
                    onSaved = { id ->
                        nav.popBackStack()
                        nav.navigate(Routes.client(id))
                    },
                    onOpenExisting = { id ->
                        nav.popBackStack()
                        nav.navigate(Routes.client(id))
                    },
                )
            }
            composable("client/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id")!!
                ClientDetailScreen(
                    clientId = id,
                    onBack = back,
                    onEdit = { nav.navigate(Routes.clientEdit(id)) },
                    onAddInquiry = { nav.navigate(Routes.inquiryNew(id)) },
                    onInquiry = { nav.navigate(Routes.inquiry(it)) },
                    onVoiceNote = { noteId -> nav.navigate(Routes.voice(id, noteId = noteId)) },
                )
            }
            composable("client/{id}/edit") { entry ->
                val id = entry.arguments?.getString("id")!!
                ClientFormScreen(
                    clientId = id,
                    onBack = back,
                    onSaved = { nav.popBackStack() },
                    onOpenExisting = { nav.navigate(Routes.client(it)) },
                )
            }
            composable("inquiry/new/{clientId}") { entry ->
                val clientId = entry.arguments?.getString("clientId")!!
                InquiryFormScreen(
                    clientId = clientId,
                    inquiryId = null,
                    onBack = back,
                    onSaved = { id ->
                        nav.popBackStack()
                        nav.navigate(Routes.inquiry(id))
                    },
                )
            }
            composable(
                "inquiry/{id}?tab={tab}",
                listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 }),
            ) { entry ->
                val id = entry.arguments?.getString("id")!!
                InquiryDetailScreen(
                    inquiryId = id,
                    initialTab = entry.arguments?.getInt("tab") ?: 0,
                    onBack = back,
                    onEdit = { nav.navigate(Routes.inquiryEdit(id)) },
                    onClient = { nav.navigate(Routes.client(it)) },
                    onProperty = { nav.navigate(Routes.property(it)) },
                    onVoiceNote = { clientId -> nav.navigate(Routes.voice(clientId, inquiryId = id)) },
                )
            }
            composable("inquiry/{id}/edit") { entry ->
                val id = entry.arguments?.getString("id")!!
                InquiryFormScreen(clientId = null, inquiryId = id, onBack = back, onSaved = { nav.popBackStack() })
            }
            composable("inquiries/{type}/{category}") { entry ->
                val type = TransactionType.valueOf(entry.arguments?.getString("type")!!)
                val category = PropertyCategory.valueOf(entry.arguments?.getString("category")!!)
                InquiryListScreen(
                    type = type,
                    category = category,
                    onBack = back,
                    onInquiry = { nav.navigate(Routes.inquiry(it)) },
                )
            }
            composable(
                "voice/{clientId}?inquiryId={inquiryId}&noteId={noteId}",
                listOf(
                    navArgument("clientId") { type = NavType.StringType },
                    navArgument("inquiryId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("noteId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments!!
                VoiceNoteScreen(
                    clientId = args.getString("clientId")!!,
                    inquiryId = args.getString("inquiryId")?.ifEmpty { null },
                    noteId = args.getString("noteId")?.ifEmpty { null },
                    onBack = back,
                    onSaved = { inquiryId ->
                        nav.popBackStack()
                        nav.navigate(Routes.inquiry(inquiryId))
                    },
                )
            }
            composable(
                "caller?phone={phone}&note={note}",
                listOf(
                    navArgument("phone") { type = NavType.StringType; defaultValue = "" },
                    navArgument("note") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                CallerScreen(
                    number = entry.arguments?.getString("phone").orEmpty(),
                    startWithNote = entry.arguments?.getString("note") == "1",
                    onBack = back,
                    onClient = { nav.navigate(Routes.client(it)) },
                    onInquiryMatches = { nav.navigate("inquiry/$it?tab=1") },
                    onCreateClient = { nav.navigate(CallerRoutes.newClient(it)) },
                    onVoiceNote = { clientId -> nav.navigate(Routes.voice(clientId)) },
                )
            }
            composable(Routes.PROPERTY_NEW) {
                PropertyFormScreen(
                    propertyId = null,
                    onBack = back,
                    onSaved = { id ->
                        nav.popBackStack()
                        nav.navigate(Routes.property(id))
                    },
                )
            }
            composable("property/{id}") { entry ->
                val id = entry.arguments?.getString("id")!!
                PropertyDetailScreen(
                    propertyId = id,
                    onBack = back,
                    onEdit = { nav.navigate(Routes.propertyEdit(id)) },
                    onInquiry = { nav.navigate(Routes.inquiry(it)) },
                )
            }
            composable("property/{id}/edit") { entry ->
                val id = entry.arguments?.getString("id")!!
                PropertyFormScreen(propertyId = id, onBack = back, onSaved = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Asks for notification permission (Android 13+) once; reminders still work in-app if denied. */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
