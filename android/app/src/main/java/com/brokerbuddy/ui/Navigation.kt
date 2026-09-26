package com.brokerbuddy.ui

import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.brokerbuddy.ui.auth.SplashScreen
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
import com.brokerbuddy.ui.callassistant.CallAssistantScreen
import com.brokerbuddy.ui.callassistant.TestAssistantScreen
import com.brokerbuddy.ui.voice.VoiceNoteScreen
import com.brokerbuddy.ui.caller.CallerScreen
import com.brokerbuddy.ui.dashboard.HomeActions
import com.brokerbuddy.ui.design.BottomItem
import com.brokerbuddy.ui.design.BrandBottomBar
import com.brokerbuddy.ui.theme.brand
import com.brokerbuddy.ui.whatsapp.ClientPickerDialog
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brokerbuddy.ui.whatsapp.WhatsAppConversationScreen
import com.brokerbuddy.ui.whatsapp.WhatsAppImportScreen
import com.brokerbuddy.ui.whatsapp.WhatsAppInboxScreen
import com.brokerbuddy.ui.whatsapp.WhatsAppMessageScreen
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
    const val CALL_ASSISTANT = "settings/call-assistant"
    const val CALL_ASSISTANT_TEST = "settings/call-assistant/test"
    const val CLIENT_NEW = "client/new"
    fun client(id: String) = "client/$id"
    fun clientEdit(id: String) = "client/$id/edit"
    fun inquiryNew(clientId: String) = "inquiry/new/$clientId"
    fun inquiry(id: String) = "inquiry/$id"
    fun inquiryEdit(id: String) = "inquiry/$id/edit"
    fun inquiryList(type: TransactionType, category: PropertyCategory) = "requirements?type=${type.name}&category=${category.name}"
    fun requirements(type: TransactionType? = null) = if (type == null) "requirements" else "requirements?type=${type.name}"
    const val PROPERTY_NEW = "property/new"
    fun property(id: String) = "property/$id"
    fun propertyEdit(id: String) = "property/$id/edit"
    fun voice(clientId: String, inquiryId: String? = null, noteId: String? = null) =
        "voice/$clientId?inquiryId=${inquiryId.orEmpty()}&noteId=${noteId.orEmpty()}"
}

private val tabs = listOf(
    BottomItem(Routes.DASHBOARD, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    BottomItem(Routes.CLIENTS, "Clients", Icons.Outlined.People, Icons.Filled.People),
    BottomItem(Routes.PROPERTIES, "Properties", Icons.Outlined.Apartment, Icons.Filled.Apartment),
    BottomItem(Routes.REMINDERS, "Follow Ups", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
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

    // The splash stays up briefly on a cold start (and until the saved session has loaded).
    var splashDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        splashDone = true
    }
    when (val s: Session? = session) {
        null -> SplashScreen()
        else -> when {
            !splashDone -> SplashScreen()
            !s.isLoggedIn -> LoginScreen()
            else -> MainScaffold(openRoute, onRouteOpened)
        }
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MainScaffold(openRoute: String?, onRouteOpened: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val back: () -> Unit = { nav.popBackStack() }
    RequestNotificationPermissionOnce()
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var pickingVoiceClient by remember { mutableStateOf(false) }
    val isDemo = container.sessionStore.session.collectAsState(initial = null).value?.isDemo == true

    LaunchedEffect(openRoute) {
        if (openRoute != null) {
            runCatching { nav.navigate(openRoute) }
            onRouteOpened()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.brand.background,
        // Each screen draws its own top bar and handles the status-bar inset itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val onTab = tabs.any { it.route == currentRoute }
            Column {
                if (isDemo) DemoBanner(onExit = { scope.launch { container.sessionStore.signOut() } }, aboveTabs = onTab)
                if (onTab) {
                    BrandBottomBar(
                        items = tabs,
                        selectedRoute = currentRoute,
                        onSelect = { nav.navigateTab(it) },
                        onMic = { pickingVoiceClient = true },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    HomeActions(
                        onMenu = { showMenu = true },
                        onSearch = { nav.navigateTab(Routes.CLIENTS) },
                        onProfile = { nav.navigate(Routes.SETTINGS) },
                        onFollowUps = { nav.navigateTab(Routes.REMINDERS) },
                        onClients = { nav.navigateTab(Routes.CLIENTS) },
                        onProperties = { nav.navigateTab(Routes.PROPERTIES) },
                        onLeads = { nav.navigate("whatsapp/inbox") },
                        onTile = { type, category -> nav.navigate(Routes.inquiryList(type, category)) },
                        onRequirements = { type -> nav.navigate(Routes.requirements(type)) },
                        onClient = { nav.navigate(Routes.client(it)) },
                        onProperty = { nav.navigate(Routes.property(it)) },
                        onLeadMessage = { nav.navigate("whatsapp/message/$it") },
                    ),
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
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = back, onCallAssistant = { nav.navigate(Routes.CALL_ASSISTANT) })
            }
            composable(Routes.CALL_ASSISTANT) {
                CallAssistantScreen(
                    onBack = back,
                    onTest = { nav.navigate(Routes.CALL_ASSISTANT_TEST) },
                    onClient = { id -> nav.navigate("client/$id") },
                )
            }
            composable(Routes.CALL_ASSISTANT_TEST) { TestAssistantScreen(onBack = back) }

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
                    onWhatsAppHistory = { nav.navigate("whatsapp/client/$id") },
                    onMatches = { inquiryId -> nav.navigate("inquiry/$inquiryId?tab=1") },
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
            composable(
                "requirements?type={type}&category={category}",
                listOf(
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                InquiryListScreen(
                    type = entry.arguments?.getString("type")?.let { t -> TransactionType.entries.firstOrNull { it.name == t } },
                    category = entry.arguments?.getString("category")?.let { c -> PropertyCategory.entries.firstOrNull { it.name == c } },
                    onBack = back,
                    onInquiry = { nav.navigate(Routes.inquiry(it)) },
                    onMatches = { nav.navigate("inquiry/$it?tab=1") },
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
            composable("whatsapp/inbox") {
                WhatsAppInboxScreen(
                    onBack = back,
                    onMessage = { nav.navigate("whatsapp/message/$it") },
                    onImport = { nav.navigate("whatsapp/import") },
                )
            }
            composable("whatsapp/message/{id}") { entry ->
                WhatsAppMessageScreen(
                    messageId = entry.arguments?.getString("id")!!,
                    onBack = back,
                    onClient = { nav.navigate(Routes.client(it)) },
                    onInquiry = { nav.navigate(Routes.inquiry(it)) },
                    onProperty = { nav.navigate(Routes.property(it)) },
                )
            }
            composable("whatsapp/client/{clientId}") { entry ->
                val clientId = entry.arguments?.getString("clientId")!!
                WhatsAppConversationScreen(
                    clientId = clientId,
                    onBack = back,
                    onMessage = { nav.navigate("whatsapp/message/$it") },
                    onImportChat = { nav.navigate("whatsapp/import?clientId=$clientId") },
                )
            }
            composable(
                "whatsapp/import?clientId={clientId}",
                listOf(navArgument("clientId") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val shared = remember { SharedInbox.take().orEmpty() }
                WhatsAppImportScreen(
                    initialText = shared,
                    presetClientId = entry.arguments?.getString("clientId")?.ifEmpty { null },
                    onBack = back,
                    onMessage = { id ->
                        nav.popBackStack()
                        nav.navigate("whatsapp/message/$id")
                    },
                    onClientHistory = { id ->
                        nav.popBackStack()
                        nav.navigate("whatsapp/client/$id")
                    },
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

    if (showMenu) {
        MoreMenuSheet(
            onDismiss = { showMenu = false },
            onNavigate = { route -> showMenu = false; nav.navigate(route) },
            onSignOut = { showMenu = false; scope.launch { container.sessionStore.signOut() } },
        )
    }
    if (pickingVoiceClient) {
        ClientPickerDialog(showAddNumber = false, onDismiss = { pickingVoiceClient = false }) { client, _ ->
            pickingVoiceClient = false
            nav.navigate(Routes.voice(client.id))
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

/** Shown on every screen while the offline demo is on. */
@Composable
private fun DemoBanner(onExit: () -> Unit, aboveTabs: Boolean) {
    val tint = MaterialTheme.brand.amber
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().background(tint.container)
            .then(if (aboveTabs) Modifier else Modifier.navigationBarsPadding())
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Demo · sample data, changes aren't saved",
            style = MaterialTheme.typography.bodySmall, color = tint.content, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onExit) { Text("Exit demo", color = tint.content) }
    }
}
