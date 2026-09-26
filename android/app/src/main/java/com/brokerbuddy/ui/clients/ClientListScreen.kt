package com.brokerbuddy.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.Client
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.common.BrandTopBar
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SearchField
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.dial
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.design.Avatar
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.design.RoundIconButton
import com.brokerbuddy.ui.design.whatsAppIcon
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.Tint
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientListScreen(onClient: (String) -> Unit, onAdd: () -> Unit) {
    val api = appContainer().api
    var query by rememberText()
    val loader = rememberLoad(query) {
        if (query.isNotBlank()) delay(300) // debounce typing
        api.call { clients(q = query.ifBlank { null }) }
    }

    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = { BrandTopBar("Clients") },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = MaterialTheme.brand.link, contentColor = Brand.Surface) {
                Icon(Icons.Filled.PersonAdd, contentDescription = "Add client")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(query, { query = it }, "Search name or phone", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.fillMaxSize()) {
                LoadContent(loader) { list ->
                    if (list.clients.isEmpty()) {
                        EmptyMessage(if (query.isBlank()) "No clients yet. Tap + to add one." else "No clients match \"$query\"")
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(list.clients, key = { it.id }) { client -> ClientRow(client) { onClient(client.id) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun statusTint(status: ClientStatus): Tint {
    val b = MaterialTheme.brand
    return when (status) {
        ClientStatus.NEW -> b.danger
        ClientStatus.CONTACTED -> b.info
        ClientStatus.SITE_VISIT -> b.purple
        ClientStatus.NEGOTIATION -> b.amber
        ClientStatus.CLOSED_WON -> b.success
        ClientStatus.CLOSED_LOST, ClientStatus.ON_HOLD -> b.neutral
    }
}

@Composable
private fun ClientRow(client: Client, onClick: () -> Unit) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    BrandCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(client.name, size = 48.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(client.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${PhoneNumbers.display(client.primaryPhone)} • ${client.leadSource.label}",
                    style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(client.status.label, statusTint(client.status))
                    client.activeInquiries?.takeIf { it > 0 }?.let {
                        Text(if (it == 1) "1 requirement" else "$it requirements", style = MaterialTheme.typography.labelMedium, color = b.muted)
                    }
                }
            }
            RoundIconButton(Icons.Filled.Call, "Call ${client.name}", b.info, { dial(context, client.primaryPhone) }, size = 40.dp)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(whatsAppIcon(), "WhatsApp ${client.name}", Tint(b.success.container, Brand.WhatsApp), { openWhatsApp(context, client.primaryPhone) }, size = 40.dp)
        }
    }
}
