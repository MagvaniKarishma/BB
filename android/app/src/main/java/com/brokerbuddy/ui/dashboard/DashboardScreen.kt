@file:OptIn(ExperimentalMaterial3Api::class)

package com.brokerbuddy.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.brokerbuddy.core.model.Dashboard
import com.brokerbuddy.core.model.DashboardTile
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad
import kotlinx.coroutines.delay

private const val REFRESH_MS = 30_000L

@Composable
fun DashboardScreen(
    onTile: (TransactionType, PropertyCategory) -> Unit,
    onSettings: () -> Unit,
    onReminders: () -> Unit,
) {
    val api = appContainer().api
    val loader = rememberLoad { api.call { dashboard() } }
    var type by rememberSaveable { mutableStateOf(TransactionType.RENT) }

    // Live counts: refresh while the dashboard is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(REFRESH_MS)
                loader.reload()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BrokerBuddy") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = loader.reload,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LoadContent(loader) { data ->
                DashboardContent(data, type, onTypeChange = { type = it }, onTile = onTile, onReminders = onReminders)
            }
        }
    }
}

@Composable
private fun DashboardContent(
    data: Dashboard,
    type: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    onTile: (TransactionType, PropertyCategory) -> Unit,
    onReminders: () -> Unit,
) {
    val board = if (type == TransactionType.RENT) data.rent else data.buy
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TransactionType.entries.forEachIndexed { i, t ->
                    val total = if (t == TransactionType.RENT) data.rent.total else data.buy.total
                    SegmentedButton(
                        selected = t == type,
                        onClick = { onTypeChange(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, TransactionType.entries.size),
                    ) { Text("${t.label} · $total") }
                }
            }
        }
        items(board.tiles, key = { it.category.name }) { tile ->
            CategoryTile(tile) { onTile(type, tile.category) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card(Modifier.fillMaxWidth().clickable(onClick = onReminders)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Follow-ups", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${data.reminders.overdue} overdue · ${data.reminders.dueToday} due today",
                            color = if (data.reminders.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        Text("${data.availableProperties}", style = MaterialTheme.typography.titleMedium)
                        Text("listings", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(tile: DashboardTile, onClick: () -> Unit) {
    val active = tile.inquiries > 0
    Card(
        onClick = onClick,
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tile.category.label, style = MaterialTheme.typography.titleMedium)
            Text("${tile.inquiries}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                if (tile.clients == 1) "1 client" else "${tile.clients} clients",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
