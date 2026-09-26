package com.brokerbuddy.ui.dashboard

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.brokerbuddy.core.caller.CallerCards
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.HomeFollowUp
import com.brokerbuddy.core.model.HomeLead
import com.brokerbuddy.core.model.LeadSource
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.ReminderStatus
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.model.UpdateReminderRequest
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.dial
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.design.Avatar
import com.brokerbuddy.ui.design.CategoryChip
import com.brokerbuddy.ui.design.GreetingHero
import com.brokerbuddy.ui.design.HomeTopBar
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.design.PropertyCard
import com.brokerbuddy.ui.design.RoundIconButton
import com.brokerbuddy.ui.design.RowDivider
import com.brokerbuddy.ui.design.SectionCard
import com.brokerbuddy.ui.design.SegmentedPill
import com.brokerbuddy.ui.design.SourceBadge
import com.brokerbuddy.ui.design.StatTile
import com.brokerbuddy.ui.design.whatsAppIcon
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.Tint
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                HomeContent(d, userName?.substringBefore(' ') ?: "there", type, { type = it }, actions, onChanged = loader.reload)
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
    onChanged: () -> Unit,
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
            SectionCard("Today's Follow Ups", onViewAll = actions.onFollowUps) {
                if (d.todayFollowUps.isEmpty()) EmptyMessage("No follow-ups due today 🎉")
                d.todayFollowUps.take(5).forEachIndexed { i, f ->
                    if (i > 0) RowDivider()
                    FollowUpRow(f, actions, onChanged)
                }
            }
        }
        item {
            SectionCard("New Leads", onViewAll = actions.onLeads) {
                if (d.newLeads.isEmpty()) EmptyMessage("No new leads this week")
                d.newLeads.forEachIndexed { i, lead ->
                    if (i > 0) RowDivider()
                    LeadRow(lead, actions)
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

/** What kind of follow-up this is, from its title (the agent writes these). */
private enum class FollowUpKind { CALL, SEND, VISIT, OTHER }

private fun kindOf(title: String): FollowUpKind {
    val t = title.lowercase()
    return when {
        listOf("visit", "site", "dikhana", "show flat").any { it in t } -> FollowUpKind.VISIT
        listOf("send", "share", "propert", "options", "whatsapp", "bhej").any { it in t } -> FollowUpKind.SEND
        listOf("call", "phone", "ring", "baat").any { it in t } -> FollowUpKind.CALL
        else -> FollowUpKind.OTHER
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

@Composable
private fun FollowUpRow(f: HomeFollowUp, actions: HomeActions, onChanged: () -> Unit) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val kind = kindOf(f.title)
    val (chip, tint) = when (kind) {
        FollowUpKind.CALL -> "Call" to b.info
        FollowUpKind.SEND -> "Send Properties" to b.success
        FollowUpKind.VISIT -> "Site Visit" to b.purple
        FollowUpKind.OTHER -> f.title to b.neutral
    }
    val name = f.client?.name ?: f.title
    Row(
        Modifier.fillMaxWidth().clickable { f.client?.let { actions.onClient(it.id) } }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, size = 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                f.requirement?.text() ?: f.title,
                style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(chip.take(22), tint)
                val due = runCatching { Instant.parse(f.dueAt).atZone(ZoneId.systemDefault()) }.getOrNull()
                Text(
                    if (f.overdue) "Overdue" else due?.let(timeFormat::format) ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (f.overdue) b.danger.content else b.muted,
                )
            }
        }
        val phone = f.client?.primaryPhone
        if (phone != null) {
            if (kind == FollowUpKind.SEND) {
                RoundIconButton(whatsAppIcon(), "WhatsApp", Tint(b.success.container, Brand.WhatsApp), { openWhatsApp(context, phone) })
            } else {
                RoundIconButton(Icons.Filled.Call, "Call", b.info, { dial(context, phone) })
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = b.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Mark done") }, onClick = {
                    menu = false
                    scope.launch {
                        api.call { updateReminder(f.id, UpdateReminderRequest(status = ReminderStatus.DONE)) }
                            .onSuccess { onChanged() }.onFailure { toast(context, it.message ?: "Failed") }
                    }
                })
                DropdownMenuItem(text = { Text("Snooze 1 day") }, onClick = {
                    menu = false
                    scope.launch {
                        val next = Instant.parse(f.dueAt).plusSeconds(86_400).coerceAtLeast(Instant.now().plusSeconds(3600)).toString()
                        api.call { updateReminder(f.id, UpdateReminderRequest(dueAt = next)) }
                            .onSuccess { onChanged() }.onFailure { toast(context, it.message ?: "Failed") }
                    }
                })
                f.client?.let { c -> DropdownMenuItem(text = { Text("Open ${c.name}") }, onClick = { menu = false; actions.onClient(c.id) }) }
            }
        }
    }
}

private fun sourceLabel(source: String) =
    LeadSource.entries.firstOrNull { it.name == source }?.label ?: "WhatsApp"

@Composable
private fun LeadRow(lead: HomeLead, actions: HomeActions) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    val open: () -> Unit = { lead.clientId?.let(actions.onClient) ?: lead.messageId?.let(actions.onLeadMessage) }
    Row(
        Modifier.fillMaxWidth().clickable { open() }.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceBadge(lead.source, size = 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lead.name ?: "New enquiry", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Pill(if (lead.clientId == null) "New Lead" else "New", b.danger)
            }
            lead.requirement?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1) }
            Text(
                "From ${sourceLabel(lead.source)} • ${CallerCards.relative(lead.at, Instant.now(), ZoneId.systemDefault())}",
                style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1,
            )
        }
        lead.phone?.let { phone ->
            RoundIconButton(Icons.Filled.Call, "Call", b.info, { dial(context, phone) }, size = 40.dp)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(whatsAppIcon(), "WhatsApp", Tint(b.success.container, Brand.WhatsApp), { openWhatsApp(context, phone) }, size = 40.dp)
        }
    }
}
