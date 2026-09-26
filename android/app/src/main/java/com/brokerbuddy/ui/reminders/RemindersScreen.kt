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

@Composable
fun RemindersScreen(onClient: (String) -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val status = if (tab == 0) ReminderStatus.PENDING else ReminderStatus.DONE
    val loader = rememberLoad(status) { api.call { reminders(status = status).reminders } }

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

    Scaffold(topBar = { TopAppBar(title = { Text("Follow-ups") }) }) { padding ->
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
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pending") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Done") })
            }
            LoadContent(loader) { reminders ->
                if (reminders.isEmpty()) {
                    EmptyMessage(if (tab == 0) "Nothing pending. Add follow-ups from a client's page." else "No completed follow-ups yet")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(reminders, key = { it.id }) { r ->
                            ReminderRow(
                                r,
                                onClient = onClient,
                                onDone = { update(r, UpdateReminderRequest(status = ReminderStatus.DONE)) },
                                onSnooze = {
                                    val next = Instant.now().plus(1, ChronoUnit.DAYS).toString()
                                    update(r, UpdateReminderRequest(dueAt = next))
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(r: Reminder, onClient: (String) -> Unit, onDone: () -> Unit, onSnooze: () -> Unit) {
    val overdue = r.status == ReminderStatus.PENDING &&
        runCatching { Instant.parse(r.dueAt).isBefore(Instant.now()) }.getOrDefault(false)
    val client = r.client
    ListItem(
        headlineContent = { Text(r.title) },
        overlineContent = {
            Text(
                (if (overdue) "Overdue · " else "") + formatDateTime(r.dueAt),
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = if (client != null) {
            { TextButton(onClick = { onClient(client.id) }) { Text(client.name) } }
        } else {
            null
        },
        trailingContent = if (r.status == ReminderStatus.PENDING) {
            {
                FlowRow {
                    IconButton(onClick = onSnooze) { Icon(Icons.Filled.Snooze, contentDescription = "Snooze 1 day") }
                    IconButton(onClick = onDone) { Icon(Icons.Filled.CheckCircle, contentDescription = "Mark done") }
                }
            }
        } else {
            null
        },
    )
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
