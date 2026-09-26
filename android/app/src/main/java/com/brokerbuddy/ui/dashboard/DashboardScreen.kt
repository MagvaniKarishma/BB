package com.brokerbuddy.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhoneCallback
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.TodayWork
import com.brokerbuddy.ui.design.BrandCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.design.CategoryChip
import com.brokerbuddy.ui.design.GreetingHero
import com.brokerbuddy.ui.design.HomeTopBar
import com.brokerbuddy.ui.design.PropertyCard
import com.brokerbuddy.ui.design.RowDivider
import com.brokerbuddy.ui.design.SectionCard
import com.brokerbuddy.ui.design.SegmentedPill
import com.brokerbuddy.ui.design.StatTile
import com.brokerbuddy.ui.theme.Tint
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val REFRESH_MS = 30_000L

/** Navigation callbacks used by the home screen. */
data class HomeActions(
    val onMenu: () -> Unit,
    val onSearch: () -> Unit,
    val onProfile: () -> Unit,
    val onFollowUps: () -> Unit,
    val onClients: () -> Unit,
    val onProperties: () -> Unit,
    val onLeads: () -> Unit,
    val onTile: (TransactionType, PropertyCategory) -> Unit,
    val onRequirements: (TransactionType) -> Unit,
    val onClient: (String) -> Unit,
    val onProperty: (String) -> Unit,
    val onLeadMessage: (String) -> Unit,
    /** Today's Work rows. */
    val onNewLeads: () -> Unit = onClients,
    val onCallbacks: () -> Unit = onFollowUps,
    val onTodayFollowUps: () -> Unit = onFollowUps,
    val onPortal: (Portal) -> Unit = {},
    val onAssistant: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(actions: HomeActions) {
    val container = appContainer()
    val api = container.api
    val zone = ZoneId.systemDefault()
    val tzMinutes = zone.rules.getOffset(Instant.now()).totalSeconds / 60
    val loader = rememberLoad { api.call { dashboard(tzMinutes) } }
    val session by container.sessionStore.session.collectAsState(initial = null)
    var type by rememberSaveable { mutableStateOf(TransactionType.RENT) }

    // Live counts: refresh while the home screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(REFRESH_MS)
                loader.reload()
            }
        }
    }

    val data = (loader.state as? Load.Ready)?.value
    val userName = session?.user?.name
    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = {
            HomeTopBar(
                userName = userName,
                notificationCount = data?.totals?.followUpsDueToday ?: 0,
                onMenu = actions.onMenu,
                onSearch = actions.onSearch,
                onNotifications = actions.onFollowUps,
                onProfile = actions.onProfile,
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.padding(padding).fillMaxSize()) {
            LoadContent(loader) { d ->
                HomeContent(d, userName?.substringBefore(' ') ?: "there", type, { type = it }, actions)
            }
        }
    }
}

private fun greeting(now: LocalTime) = when (now.hour) {
    in 4..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    else -> "Good Evening"
}

@Composable
private fun HomeContent(
    d: Dashboard,
    firstName: String,
    type: TransactionType,
    onType: (TransactionType) -> Unit,
    actions: HomeActions,
) {
    val b = MaterialTheme.brand
    val t = d.totals
    val leadsToday = d.newLeads.count { runCatching { Instant.parse(it.at).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }.getOrDefault(false) }
    val dueToday = t?.followUpsDueToday ?: (d.reminders.overdue + d.reminders.dueToday)
    val subtitle = when {
        leadsToday > 0 -> "$leadsToday new lead${if (leadsToday == 1) "" else "s"} today. Let's find their perfect homes."
        dueToday > 0 -> "$dueToday follow-up${if (dueToday == 1) "" else "s"} today. Let's close some deals."
        else -> "Let's find your clients their perfect homes."
    }
    val board = if (type == TransactionType.RENT) d.rent else d.buy

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GreetingHero(
                greeting = greeting(LocalTime.now()),
                name = firstName,
                subtitle = subtitle,
                badge = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH).format(LocalDate.now()),
            )
        }
        item {
            TodayWorkCard(d.todayWork, actions)
        }
        item {
            // Trends count what was added in the last 7 days (real data, not percentages).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val m = Modifier.weight(1f)
                StatTile(Icons.Outlined.People, b.info, "${t?.clients ?: 0}", "Clients",
                    t?.newClientsThisWeek?.takeIf { it > 0 }?.let { "↑ $it new" }, b.success.content, m, actions.onClients)
                StatTile(Icons.Outlined.Description, b.info, "${t?.activeRequirements ?: board.total}", "Enquiries",
                    t?.newRequirementsThisWeek?.takeIf { it > 0 }?.let { "↑ $it new" }, b.success.content, m) { actions.onRequirements(type) }
                StatTile(Icons.Outlined.Home, b.success, "${t?.availableProperties ?: d.availableProperties}", "Properties",
                    t?.newPropertiesThisWeek?.takeIf { it > 0 }?.let { "↑ $it new" }, b.success.content, m, actions.onProperties)
                StatTile(Icons.Outlined.CalendarMonth, b.danger, "${t?.pendingFollowUps ?: 0}", "Follow-ups",
                    dueToday.takeIf { it > 0 }?.let { "↑ $it today" }, b.danger.content, m, actions.onFollowUps)
            }
        }
        item {
            SegmentedPill(TransactionType.entries, type, { it.label }, onType)
        }
        item {
            // All categories fit on one row, as in the design; "Other" only appears when used.
            val shown = board.tiles.filter { it.category != PropertyCategory.OTHER || it.inquiries > 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { tile ->
                    CategoryChip(tile.category, tile.inquiries, onClick = { actions.onTile(type, tile.category) }, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            SectionCard("Top Property Matches", onViewAll = actions.onProperties) {
                if (d.topMatches.isEmpty()) {
                    EmptyMessage("Listings that fit your clients' requirements appear here")
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(d.topMatches, key = { it.property.id }) { m ->
                            PropertyCard(m.property, onClick = { actions.onProperty(m.property.id) }, matchCount = m.matchingRequirements, modifier = Modifier.width(230.dp))
                        }
                    }
                }
            }
        }
    }
}

/** One row of Today's Work: icon, label, what it counts, and the count. */
@Composable
private fun WorkRow(icon: ImageVector, tint: Tint, label: String, caption: String, count: Int?, onClick: () -> Unit) {
    val b = MaterialTheme.brand
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint.content, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = b.muted)
        }
        Text(
            count?.toString() ?: "–",
            style = MaterialTheme.typography.titleLarge,
            color = if ((count ?: 0) > 0) b.navy else b.muted,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = b.muted)
    }
}

/**
 * Today's Work: the five things a solo broker acts on today. Counts come from the server;
 * "–" means the server didn't send them (older server).
 */
@Composable
private fun TodayWorkCard(w: TodayWork?, actions: HomeActions) {
    val b = MaterialTheme.brand
    BrandCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Today's Work", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onAssistant) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = b.link, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ask", color = b.link)
            }
        }
        WorkRow(Icons.Outlined.PersonAdd, b.danger, "New Leads", "Clients not contacted yet", w?.newLeads, actions.onNewLeads)
        RowDivider()
        WorkRow(Icons.Outlined.PhoneCallback, b.info, "Callbacks", "Due today or overdue", w?.callbacks, actions.onCallbacks)
        RowDivider()
        WorkRow(Icons.Outlined.CalendarMonth, b.purple, "Follow-ups", "Due today or overdue", w?.followUps, actions.onTodayFollowUps)
        RowDivider()
        WorkRow(Icons.Outlined.Apartment, b.amber, "99acres Leads", "Enquiries received today", w?.acres99Leads) { actions.onPortal(Portal.ACRES_99) }
        RowDivider()
        WorkRow(Icons.Outlined.HomeWork, b.success, "Housing.com Leads", "Enquiries received today", w?.housingLeads) { actions.onPortal(Portal.HOUSING_COM) }
        Spacer(Modifier.height(4.dp))
    }
}
