package com.brokerbuddy.ui.inquiries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad

/** Dashboard drill-down: all active inquiries for one Rent/Buy category. */
@Composable
fun InquiryListScreen(
    type: TransactionType,
    category: PropertyCategory,
    onBack: () -> Unit,
    onInquiry: (String) -> Unit,
) {
    val api = appContainer().api
    val loader = rememberLoad(type, category) { api.call { inquiries(type, category).inquiries } }
    Scaffold(topBar = { BackTopBar("${type.label} · ${category.label}", onBack) }) { padding ->
        LoadContent(loader) { inquiries ->
            if (inquiries.isEmpty()) {
                EmptyMessage("No active ${category.label} ${type.label.lowercase()} inquiries")
            } else {
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    items(inquiries, key = { it.id }) { i ->
                        ListItem(
                            modifier = Modifier.clickable { onInquiry(i.id) },
                            headlineContent = { Text(i.client?.name ?: "Client") },
                            supportingContent = { Text(requirementSummary(i)) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
