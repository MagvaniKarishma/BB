@file:OptIn(ExperimentalMaterial3Api::class)

package com.brokerbuddy.ui.clients

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.Client
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import kotlinx.coroutines.delay

@Composable
fun ClientListScreen(onClient: (String) -> Unit, onAdd: () -> Unit) {
    val api = appContainer().api
    var query by rememberText()
    val loader = rememberLoad(query) {
        if (query.isNotBlank()) delay(300) // debounce typing
        api.call { clients(q = query.ifBlank { null }) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Clients") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.PersonAdd, contentDescription = "Add client") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search name or phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.fillMaxSize()) {
                LoadContent(loader) { list ->
                    if (list.clients.isEmpty()) {
                        EmptyMessage(if (query.isBlank()) "No clients yet. Tap + to add one." else "No clients match \"$query\"")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list.clients, key = { it.id }) { client ->
                                ClientRow(client) { onClient(client.id) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientRow(client: Client, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(client.name) },
        supportingContent = {
            Text("${PhoneNumbers.display(client.primaryPhone)} · ${client.leadSource.label}")
        },
        trailingContent = {
            Column {
                AssistChip(onClick = onClick, label = { Text(client.status.label) })
                client.activeInquiries?.takeIf { it > 0 }?.let { Text("$it active") }
            }
        },
    )
}
