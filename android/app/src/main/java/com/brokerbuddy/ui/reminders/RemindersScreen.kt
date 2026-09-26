@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.reminders

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.CreateReminderRequest
import com.brokerbuddy.core.model.Reminder
import com.brokerbuddy.core.model.ReminderStatus
import com.brokerbuddy.core.model.UpdateReminderRequest
import com.brokerbuddy.notifications.ReminderNotifier
import com.brokerbuddy.notifications.ReminderScheduler
import com.brokerbuddy.notifications.ReminderSyncWorker
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.BrandTopBar
import com.brokerbuddy.ui.design.BrandBackBar
import com.brokerbuddy.core.model.ReminderKind
import com.brokerbuddy.ui.design.Avatar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ListItemDefaults
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.theme.brand
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.formatDateTime
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.dial
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.design.FilterTabs
import com.brokerbuddy.ui.design.TabItem
import com.brokerbuddy.ui.design.RoundIconButton
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class FollowUpTab(val label: String) { TODAY("Today"), UPCOMING("Upcoming"), OVERDUE("Overdue"), DONE("Done") }

private fun dueDate(r: Reminder, zone: ZoneId): LocalDate? =
    runCatching { Instant.parse(r.dueAt).atZone(zone).toLocalDate() }.getOrNull()

/** Follow Ups (screen 14): Today / Upcoming / Overdue from pending follow-ups, plus Done. */
@Composable
fun RemindersScreen(onClient: (String) -> Unit, kind: ReminderKind? = null, onBack: (() -> Unit)? = null) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(FollowUpTab.TODAY) }
    val status = if (tab == FollowUpTab.DONE) ReminderStatus.DONE else ReminderStatus.PENDING
    val loader = rememberLoad(status, kind) { api.call { reminders(status = status, kind = kind).reminders } }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    // By calendar day: earlier days are overdue; anything due today (even an hour ago) is "Today".
    fun bucket(r: Reminder): FollowUpTab {
        val day = dueDate(r, zone)
        return when {
            day != null && day.isBefore(today) -> FollowUpTab.OVERDUE
            day == today -> FollowUpTab.TODAY
            else -> FollowUpTab.UPCOMING
        }
    }
    val pending = when (val st = loader.state) {
        is Load.Ready -> if (status == ReminderStatus.PENDING) st.value else null
        else -> null
    }
    val counts = pending?.groupingBy { bucket(it) }?.eachCount()

    fun update(r: Reminder, body: UpdateReminderRequest) {
        scope.launch {
            api.call { updateReminder(r.id, body) }
                .onSuccess {
                    ReminderScheduler.cancel(context, r)
                    ReminderSyncWorker.syncNow(context)
                    loader.reload()
                }
                .onFailure { toast(context, it.message ?: "Update failed") }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = {
            // Opened from Today's Work: callbacks or follow-ups only, with a back arrow.
            val name = when (kind) {
                ReminderKind.CALLBACK -> "Callbacks"
                ReminderKind.FOLLOW_UP -> "Follow-ups"
                null -> "Follow Ups"
            }
            val title = pending?.let { "$name (${it.size})" } ?: name
            if (onBack != null) BrandBackBar(title, onBack) else BrandTopBar(title)
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!ReminderNotifier.canNotify(context)) {
                // Fallback when notifications are blocked: reminders remain usable in this list.
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Notifications are off. Due follow-ups will only show here.")
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("Turn on notifications") }
                    }
                }
            }
            FilterTabs(
                FollowUpTab.entries.map { TabItem(it, it.label, if (it == FollowUpTab.DONE) null else counts?.get(it) ?: 0) },
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LoadContent(loader) { all ->
                val shown = if (tab == FollowUpTab.DONE) all else all.filter { bucket(it) == tab }
                if (shown.isEmpty()) {
                    EmptyMessage(
                        when (tab) {
                            FollowUpTab.TODAY -> "Nothing due today."
                            FollowUpTab.UPCOMING -> "No upcoming follow-ups. Add them from a client's page."
                            FollowUpTab.OVERDUE -> "Nothing overdue. 👍"
                            FollowUpTab.DONE -> "No completed follow-ups yet"
                        },
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(shown, key = { it.id }) { r ->
                            ReminderRow(
                                r,
                                showDay = tab != FollowUpTab.TODAY,
                                onClient = onClient,
                                onDone = { update(r, UpdateReminderRequest(status = ReminderStatus.DONE)) },
                                onSnooze = {
                                    val next = Instant.now().plus(1, ChronoUnit.DAYS).toString()
                                    update(r, UpdateReminderRequest(dueAt = next))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
private fun ReminderRow(r: Reminder, showDay: Boolean, onClient: (String) -> Unit, onDone: () -> Unit, onSnooze: () -> Unit) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    val client = r.client
    var menu by remember { mutableStateOf(false) }
    val due = runCatching { Instant.parse(r.dueAt).atZone(ZoneId.systemDefault()) }.getOrNull()
    val overdue = r.status == ReminderStatus.PENDING && due != null && due.toInstant().isBefore(Instant.now())
    BrandCard(Modifier.fillMaxWidth(), onClick = client?.let { c -> { onClient(c.id) } }, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(client?.name ?: r.title, size = 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(client?.name ?: r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (client != null) Text(r.title, style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (r.status == ReminderStatus.PENDING && client != null) {
                    RoundIconButton(Icons.Filled.Call, "Call ${client.name}", b.info, { dial(context, client.primaryPhone) }, size = 36.dp)
                }
                if (due != null) {
                    Text(
                        (if (showDay) due.format(DAY_FMT) + " · " else "") + due.format(TIME_FMT),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) MaterialTheme.colorScheme.error else b.muted,
                    )
                }
            }
            if (r.status == ReminderStatus.PENDING) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (client != null) {
                            DropdownMenuItem(text = { Text("WhatsApp") }, onClick = { menu = false; openWhatsApp(context, client.primaryPhone) })
                        }
                        DropdownMenuItem(text = { Text("Mark done") }, onClick = { menu = false; onDone() })
                        DropdownMenuItem(text = { Text("Snooze 1 day") }, onClick = { menu = false; onSnooze() })
                    }
                }
            }
        }
    }
}

private enum class QuickTime(val label: String) { HOUR("In 1 hour"), TOMORROW("Tomorrow 10 AM"), THREE_DAYS("In 3 days"), CUSTOM("Pick…") }

@Composable
fun AddReminderDialog(
    clientId: String?,
    defaultTitle: String,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
    inquiryId: String? = null,
) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberText(defaultTitle)
    var note by rememberText()
    var quick by remember { mutableStateOf(QuickTime.TOMORROW) }
    var customDue by remember { mutableStateOf<Instant?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var busy by remember { mutableStateOf(false) }

    val zone = ZoneId.systemDefault()
    val due: Instant? = when (quick) {
        QuickTime.HOUR -> Instant.now().plus(1, ChronoUnit.HOURS)
        QuickTime.TOMORROW -> LocalDate.now(zone).plusDays(1).atTime(10, 0).atZone(zone).toInstant()
        QuickTime.THREE_DAYS -> LocalDate.now(zone).plusDays(3).atTime(10, 0).atZone(zone).toInstant()
        QuickTime.CUSTOM -> customDue
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New follow-up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("What to do") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Note") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickTime.entries.forEach { q ->
                        FilterChip(
                            selected = quick == q,
                            onClick = { quick = q; if (q == QuickTime.CUSTOM) pickingDate = true },
                            label = { Text(q.label) },
                        )
                    }
                }
                Text(due?.let { "Due ${formatDateTime(it.toString())}" } ?: "Pick a date and time")
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && due != null && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        api.call {
                            createReminder(
                                CreateReminderRequest(
                                    title = title.trim(),
                                    dueAt = due!!.toString(),
                                    note = note.trim().ifEmpty { null },
                                    clientId = clientId,
                                    inquiryId = inquiryId,
                                ),
                            )
                        }.onSuccess {
                            ReminderScheduler.schedule(context, it.reminder)
                            onCreated()
                        }.onFailure { toast(context, it.message ?: "Could not save") }
                        busy = false
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingDate) {
        val dateState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    // The date picker reports midnight UTC of the chosen day.
                    pickedDate = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(dateState) }
    }
    pickedDate?.let { date ->
        val timeState = rememberTimePickerState(initialHour = 10, initialMinute = 0)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            title = { Text("Time") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    customDue = date.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant()
                    pickedDate = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text("Cancel") } },
        )
    }
}
