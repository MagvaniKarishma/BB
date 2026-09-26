package com.brokerbuddy.ui.inquiries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.InquiryList
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.clients.statusTint
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.SearchField
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.design.Avatar
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.design.SegmentedPill
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.delay

/**
 * Requirements (screen 7): every active client requirement, Rent/Buy, searchable by client
 * or area, with how many available properties match. Opened from the dashboard (optionally
 * narrowed to one category) and the menu.
 */
@Composable
fun InquiryListScreen(
    type: TransactionType?,
    category: PropertyCategory?,
    onBack: () -> Unit,
    onInquiry: (String) -> Unit,
    onMatches: (String) -> Unit,
) {
    val api = appContainer().api
    var tx by rememberSaveable { mutableStateOf(type ?: TransactionType.RENT) }
    var query by rememberText()
    val loader = rememberLoad(tx, category, query) {
        if (query.isNotBlank()) delay(300)
        api.call { inquiries(q = query.ifBlank { null }, transactionType = tx, category = category) }
    }
    val total = when (val st = loader.state) {
        is Load.Ready<InquiryList> -> st.value.total ?: st.value.inquiries.size
        else -> null
    }
    val title = listOfNotNull("Requirements", category?.label).joinToString(" · ") + (total?.let { " ($it)" } ?: "")

    Scaffold(containerColor = MaterialTheme.brand.background, topBar = { BackTopBar(title, onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SegmentedPill(TransactionType.entries, tx, { it.label }, { tx = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SearchField(query, { query = it }, "Search requirements...", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            LoadContent(loader) { list ->
                if (list.inquiries.isEmpty()) {
                    EmptyMessage(if (query.isBlank()) "No active ${tx.label.lowercase()} requirements" else "Nothing matches \"$query\"")
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list.inquiries, key = { it.id }) { i -> RequirementCard(i, { onInquiry(i.id) }, { onMatches(i.id) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequirementCard(i: Inquiry, onClick: () -> Unit, onMatches: () -> Unit) {
    val b = MaterialTheme.brand
    val name = i.client?.name ?: "Client"
    BrandCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(name, size = 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    i.client?.status?.let { s -> Pill(if (s == ClientStatus.NEW) "New Lead" else s.label, statusTint(s)) }
                }
                Text(
                    listOfNotNull(i.category.label, i.locations.firstOrNull()).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Money.range(i.budgetMin, i.budgetMax)?.let {
                    Text(it + if (i.transactionType == TransactionType.RENT) " / month" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = b.navy)
                }
                val details = listOfNotNull(
                    i.furnishing.takeIf { it.isNotEmpty() }?.joinToString("/") { it.label },
                    i.minParking?.takeIf { it > 0 }?.let { "Parking" },
                ).joinToString(" • ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = b.muted)
                i.possession?.let { Text(it.label, style = MaterialTheme.typography.bodySmall, color = b.muted) }
            }
        }
        i.matchCount?.let { n ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(if (n == 1) "1 Match" else "$n Matches", if (n > 0) b.success else b.neutral, Modifier.clickable(onClick = onMatches))
            }
        }
    }
}
