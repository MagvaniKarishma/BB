@file:OptIn(ExperimentalMaterial3Api::class)

package com.brokerbuddy.ui.properties

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.Availability
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.InquiryMatch
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.Property
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.PropertyRequest
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.DropdownField
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.LabeledValue
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.NumberField
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.dial
import com.brokerbuddy.ui.common.formatDate
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.inquiries.CheckLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun PropertyListScreen(onProperty: (String) -> Unit, onAdd: () -> Unit) {
    val api = appContainer().api
    var query by rememberText()
    var type by rememberSaveable { mutableStateOf<TransactionType?>(null) }
    var availableOnly by rememberSaveable { mutableStateOf(true) }
    val loader = rememberLoad(query, type, availableOnly) {
        if (query.isNotBlank()) delay(300)
        api.call {
            properties(
                q = query.ifBlank { null },
                transactionType = type,
                availability = if (availableOnly) Availability.AVAILABLE else null,
            )
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Properties") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add property") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search title, area or building") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransactionType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = if (type == t) null else t }, label = { Text(t.label) })
                }
                FilterChip(selected = availableOnly, onClick = { availableOnly = !availableOnly }, label = { Text("Available only") })
            }
            PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.fillMaxSize()) {
                LoadContent(loader) { list ->
                    if (list.properties.isEmpty()) {
                        EmptyMessage("No properties found. Tap + to add a listing.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list.properties, key = { it.id }) { p ->
                                ListItem(
                                    modifier = Modifier.clickable { onProperty(p.id) },
                                    headlineContent = { Text(p.title) },
                                    supportingContent = {
                                        Text("${p.transactionType.label} · ${p.category.label} · ${p.locality}")
                                    },
                                    trailingContent = {
                                        Column {
                                            Text(Money.compact(p.price), style = MaterialTheme.typography.titleSmall)
                                            if (p.availability != Availability.AVAILABLE) Text(p.availability.label)
                                        }
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
}

@Composable
fun PropertyDetailScreen(propertyId: String, onBack: () -> Unit, onEdit: () -> Unit, onInquiry: (String) -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val loader = rememberLoad(propertyId) { api.call { property(propertyId).property } }
    val matches = rememberLoad(propertyId) { api.call { propertyMatches(propertyId).matches } }
    Scaffold(
        topBar = {
            BackTopBar("Property", onBack) {
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
            }
        },
    ) { padding ->
        LoadContent(loader) { p ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(p.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${Money.full(p.price)}${if (p.transactionType == TransactionType.RENT) " / month" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                PropertyFacts(p)
                p.ownerPhone?.let { phone ->
                    Button(onClick = { dial(context, phone) }, modifier = Modifier.padding(top = 8.dp)) { Text("Call owner") }
                }
                SectionTitle("Interested clients")
                LoadContent(matches) { list ->
                    Column {
                        if (list.isEmpty()) EmptyMessage("No active client requirements match this listing")
                        list.forEach { ClientMatchCard(it) { onInquiry(it.inquiry.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyFacts(p: Property) {
    LabeledValue("Type", "${p.transactionType.label} · ${p.category.label}")
    LabeledValue("Availability", p.availability.label)
    LabeledValue("Locality", p.locality)
    LabeledValue("Building", p.building)
    LabeledValue("Address", p.address)
    LabeledValue("Carpet area", p.carpetAreaSqft?.let { "$it sq ft" })
    LabeledValue("Furnishing", p.furnishing?.label)
    LabeledValue("Parking", p.parkingSpots?.toString())
    LabeledValue("Floor", p.floor?.let { f -> p.totalFloors?.let { "$f of $it" } ?: "$f" })
    LabeledValue("Possession", p.possession?.label)
    LabeledValue("Possession date", p.possessionDate?.let(::formatDate))
    LabeledValue("Deposit", p.deposit?.let(Money::full))
    LabeledValue("Owner", p.ownerName)
    LabeledValue("Owner phone", p.ownerPhone?.let(PhoneNumbers::display))
    LabeledValue("Notes", p.notes)
}

@Composable
private fun ClientMatchCard(m: InquiryMatch, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(m.inquiry.client?.name ?: "Client", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${m.score}%", color = MaterialTheme.colorScheme.primary)
            }
            m.checks.filter { it.outcome != "n/a" }.forEach { CheckLine(it) }
        }
    }
}

@Composable
fun PropertyFormScreen(propertyId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    var title by rememberText()
    var type by rememberSaveable { mutableStateOf(TransactionType.RENT) }
    var category by rememberSaveable { mutableStateOf<PropertyCategory?>(null) }
    var price by rememberText()
    var deposit by rememberText()
    var locality by rememberText()
    var building by rememberText()
    var address by rememberText()
    var area by rememberText()
    var furnishing by rememberSaveable { mutableStateOf<Furnishing?>(null) }
    var parking by rememberText()
    var floor by rememberText()
    var totalFloors by rememberText()
    var possession by rememberSaveable { mutableStateOf<Possession?>(null) }
    var possessionDate by rememberText()
    var availability by rememberSaveable { mutableStateOf(Availability.AVAILABLE) }
    var ownerName by rememberText()
    var ownerPhone by rememberText()
    var notes by rememberText()
    var loaded by rememberSaveable { mutableStateOf(propertyId == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(propertyId) {
        if (propertyId == null || loaded) return@LaunchedEffect
        api.call { property(propertyId).property }.onSuccess { p ->
            title = p.title; type = p.transactionType; category = p.category
            price = p.price.toString(); deposit = p.deposit?.toString() ?: ""
            locality = p.locality; building = p.building ?: ""; address = p.address ?: ""
            area = p.carpetAreaSqft?.toString() ?: ""; furnishing = p.furnishing
            parking = p.parkingSpots?.toString() ?: ""; floor = p.floor?.toString() ?: ""
            totalFloors = p.totalFloors?.toString() ?: ""; possession = p.possession
            possessionDate = p.possessionDate?.take(10) ?: ""; availability = p.availability
            ownerName = p.ownerName ?: ""; ownerPhone = p.ownerPhone ?: ""; notes = p.notes ?: ""
            loaded = true
        }.onFailure { error = it.message }
    }

    // null = blank; Result.failure = invalid.
    fun <T> opt(s: String, parse: (String) -> T?): Result<T?> =
        if (s.isBlank()) Result.success(null) else parse(s.trim())?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException())
    val pPrice = Money.parse(price)
    val pDeposit = opt(deposit, Money::parse)
    val pArea = opt(area) { it.toIntOrNull()?.takeIf { n -> n > 0 } }
    val pParking = opt(parking) { it.toIntOrNull()?.takeIf { n -> n in 0..50 } }
    val pFloor = opt(floor) { it.toIntOrNull()?.takeIf { n -> n in -5..200 } }
    val pTotal = opt(totalFloors) { it.toIntOrNull()?.takeIf { n -> n in 0..200 } }
    val pDate = opt(possessionDate) { runCatching { LocalDate.parse(it) }.getOrNull() }
    val valid = loaded && title.isNotBlank() && category != null && locality.isNotBlank() && pPrice != null && pPrice > 0 &&
        listOf(pDeposit, pArea, pParking, pFloor, pTotal, pDate).all { it.isSuccess }

    fun save() {
        val body = PropertyRequest(
            title = title.trim(), transactionType = type, category = category ?: return, price = pPrice ?: return,
            deposit = pDeposit.getOrNull(), locality = locality.trim(),
            building = building.trim().ifEmpty { null }, address = address.trim().ifEmpty { null },
            carpetAreaSqft = pArea.getOrNull(), furnishing = furnishing, parkingSpots = pParking.getOrNull(),
            floor = pFloor.getOrNull(), totalFloors = pTotal.getOrNull(), possession = possession,
            possessionDate = pDate.getOrNull()?.toString(), availability = availability,
            ownerName = ownerName.trim().ifEmpty { null }, ownerPhone = ownerPhone.trim().ifEmpty { null },
            notes = notes.trim().ifEmpty { null },
        )
        error = null
        busy = true
        scope.launch {
            val result = if (propertyId == null) {
                api.call { createProperty(body).property }
            } else {
                api.call { updateProperty(propertyId, body).property }
            }
            result.onSuccess { onSaved(it.id) }.onFailure { error = it.message }
            busy = false
        }
    }

    Scaffold(topBar = { BackTopBar(if (propertyId == null) "New property" else "Edit property", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("Title *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TransactionType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = t == type, onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, TransactionType.entries.size),
                    ) { Text(if (t == TransactionType.RENT) "For rent" else "For sale") }
                }
            }
            DropdownField("Property type *", PropertyCategory.entries, category, { it.label }, { category = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    if (type == TransactionType.RENT) "Rent / month *" else "Price *", price, { price = it }, Modifier.weight(1f),
                    pPrice?.let(Money::full) ?: "e.g. 65k, 1.2 Cr", price.isNotBlank() && pPrice == null, KeyboardType.Text,
                )
                NumberField("Deposit", deposit, { deposit = it }, Modifier.weight(1f), isError = pDeposit.isFailure, keyboardType = KeyboardType.Text)
            }
            OutlinedTextField(locality, { locality = it }, label = { Text("Locality *") }, supportingText = { Text("e.g. Andheri West") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(building, { building = it }, label = { Text("Building / society") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            SectionTitle("Features")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Carpet sq ft", area, { area = it }, Modifier.weight(1f), isError = pArea.isFailure)
                NumberField("Parking", parking, { parking = it }, Modifier.weight(1f), isError = pParking.isFailure)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Floor", floor, { floor = it }, Modifier.weight(1f), isError = pFloor.isFailure)
                NumberField("Total floors", totalFloors, { totalFloors = it }, Modifier.weight(1f), isError = pTotal.isFailure)
            }
            DropdownField("Furnishing", Furnishing.entries, furnishing, { it.label }, { furnishing = it }, Modifier.fillMaxWidth(), allowNone = true)
            DropdownField("Possession", Possession.entries, possession, { it.label }, { possession = it }, Modifier.fillMaxWidth(), allowNone = true)
            if (possession == Possession.UNDER_CONSTRUCTION) {
                NumberField(
                    "Possession date (YYYY-MM-DD)", possessionDate, { possessionDate = it }, Modifier.fillMaxWidth(),
                    isError = pDate.isFailure, keyboardType = KeyboardType.Text,
                )
            }
            DropdownField("Availability", Availability.entries, availability, { it.label }, { if (it != null) availability = it }, Modifier.fillMaxWidth())
            SectionTitle("Owner")
            OutlinedTextField(ownerName, { ownerName = it }, label = { Text("Owner name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            NumberField("Owner phone", ownerPhone, { ownerPhone = it }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Phone)
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Saving…" else "Save property")
            }
        }
    }
}
