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
import androidx.compose.runtime.mutableIntStateOf
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
import com.brokerbuddy.ui.common.BrandTopBar
import com.brokerbuddy.ui.common.SearchField
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.design.PropertyCard
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.brand
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Bathtub
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.SquareFoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.PropertyPhoto
import com.brokerbuddy.ui.design.PropertyPhotos
import com.brokerbuddy.ui.design.PropertyRow
import com.brokerbuddy.ui.design.RoundIconButton
import com.brokerbuddy.ui.design.SegmentedPill
import com.brokerbuddy.ui.design.chipLabel
import com.brokerbuddy.ui.design.priceText
import com.brokerbuddy.ui.design.whatsAppIcon
import com.brokerbuddy.ui.theme.Tint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

@Composable
fun PropertyListScreen(onProperty: (String) -> Unit, onAdd: () -> Unit) {
    val api = appContainer().api
    var query by rememberText()
    var type by rememberSaveable { mutableStateOf(TransactionType.RENT) }
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
    val total = when (val st = loader.state) {
        is Load.Ready -> st.value.total
        else -> null
    }
    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = { BrandTopBar(total?.let { "Properties ($it)" } ?: "Properties") },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = MaterialTheme.brand.link, contentColor = Brand.Surface, shape = CircleShape) {
                Icon(Icons.Filled.Add, contentDescription = "Add property")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SegmentedPill(TransactionType.entries, type, { it.label }, { type = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, "Search properties...", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = availableOnly, onClick = { availableOnly = !availableOnly }, label = { Text("Available") })
            }
            PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.fillMaxSize()) {
                LoadContent(loader) { list ->
                    if (list.properties.isEmpty()) {
                        EmptyMessage("No ${type.label.lowercase()} properties found. Tap + to add a listing.")
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(list.properties, key = { it.id }) { p ->
                                PropertyRow(
                                    p,
                                    onClick = { onProperty(p.id) },
                                    badge = p.availability.takeIf { it != Availability.AVAILABLE }?.label,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Property detail (screen 9): photos, key facts, owner, Share and Mark as Rented/Sold. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PropertyDetailScreen(propertyId: String, onBack: () -> Unit, onEdit: () -> Unit, onInquiry: (String) -> Unit) {
    val container = appContainer()
    val api = container.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val b = MaterialTheme.brand
    val loader = rememberLoad(propertyId) { api.call { property(propertyId).property } }
    val matches = rememberLoad(propertyId) { api.call { propertyMatches(propertyId).matches } }
    var uploading by remember { mutableIntStateOf(0) }
    var confirmStatus by remember { mutableStateOf<Availability?>(null) }
    var deletePhoto by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = uris.size
            for (uri in uris) {
                val bytes = PropertyPhotos.prepareUpload(context, uri)
                if (bytes == null) {
                    toast(context, "Couldn't read one of the photos")
                } else {
                    val part = MultipartBody.Part.createFormData("photo", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                    api.call { uploadPropertyPhoto(propertyId, part) }.onFailure { toast(context, it.message ?: "Upload failed") }
                }
                uploading--
            }
            loader.reload()
        }
    }

    fun setAvailability(p: Property, a: Availability) {
        scope.launch {
            api.call { updateProperty(p.id, p.toRequest().copy(availability = a)) }
                .onSuccess { toast(context, "Marked as ${a.label.lowercase()}"); loader.reload() }
                .onFailure { toast(context, it.message ?: "Update failed") }
        }
    }

    Scaffold(
        containerColor = b.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val p = when (val st = loader.state) {
                is Load.Ready -> st.value
                else -> null
            }
            if (p != null) {
                Row(
                    Modifier.fillMaxWidth().background(b.card).navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { shareProperty(context, p) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, null); Text("  Share")
                    }
                    val next = if (p.availability == Availability.AVAILABLE) {
                        if (p.transactionType == TransactionType.RENT) Availability.RENTED else Availability.SOLD
                    } else {
                        Availability.AVAILABLE
                    }
                    Button(
                        onClick = { confirmStatus = next },
                        colors = ButtonDefaults.buttonColors(containerColor = if (next == Availability.AVAILABLE) b.success.content else Brand.Red),
                        modifier = Modifier.weight(1f),
                    ) { Text("Mark as ${next.label}") }
                }
            }
        },
    ) { padding ->
        LoadContent(loader) { p ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                // ----- photos -----
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    if (p.photoIds.isEmpty()) {
                        PropertyPhoto(p.id, null, Modifier.fillMaxSize())
                    } else {
                        val pager = rememberPagerState(pageCount = { p.photoIds.size })
                        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                            PropertyPhoto(p.id, p.photoIds[page], Modifier.fillMaxSize(), maxPx = 1200)
                        }
                        Text(
                            "${pager.currentPage + 1}/${p.photoIds.size}",
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                        RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", Tint(Color.White, Brand.Navy), onBack, size = 40.dp)
                        Spacer(Modifier.weight(1f))
                        RoundIconButton(Icons.Filled.Edit, "Edit", Tint(Color.White, Brand.Navy), onEdit, size = 40.dp)
                    }
                }

                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.headlineSmall, color = b.navy, modifier = Modifier.weight(1f))
                        if (p.availability != Availability.AVAILABLE) Pill(p.availability.label, b.danger)
                    }
                    Text(listOfNotNull(p.locality, p.building).joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = b.muted)
                    Text(priceText(p), style = MaterialTheme.typography.titleLarge, color = b.link, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Pill(p.transactionType.label, if (p.transactionType == TransactionType.RENT) b.success else b.info)
                        p.furnishing?.let { Pill(it.chipLabel(), b.neutral) }
                    }

                    // ----- key facts -----
                    BrandCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            Fact(Icons.Outlined.Bed, p.bedrooms?.let { if (it == 1) "1 Bed" else "$it Beds" } ?: p.category.label)
                            p.bathrooms?.let { Fact(Icons.Outlined.Bathtub, if (it == 1) "1 Bath" else "$it Baths") }
                            p.carpetAreaSqft?.let { Fact(Icons.Outlined.SquareFoot, "$it sq ft") }
                        }
                        Spacer(Modifier.height(12.dp))
                        FactGrid(
                            listOf(
                                "Society" to p.building,
                                "Floor" to p.floor?.let { f -> p.totalFloors?.let { "$f of $it" } ?: "$f" },
                                "Parking" to p.parkingSpots?.let { if (it == 0) "None" else "$it" },
                                "Available" to (p.possession?.label ?: p.possessionDate?.let(::formatDate)),
                                "Deposit" to p.deposit?.let(Money::full),
                                "Address" to p.address,
                            ).filter { it.second != null }.map { it.first to it.second!! },
                        )
                    }

                    // ----- owner -----
                    if (p.ownerName != null || p.ownerPhone != null) {
                        SectionTitle("Owner contact")
                        BrandCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    p.ownerName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                                    p.ownerPhone?.let { Text(PhoneNumbers.display(it), style = MaterialTheme.typography.bodyMedium, color = b.muted) }
                                }
                                p.ownerPhone?.let { phone ->
                                    RoundIconButton(Icons.Filled.Call, "Call owner", b.info, { dial(context, phone) }, size = 40.dp)
                                    Spacer(Modifier.width(8.dp))
                                    RoundIconButton(whatsAppIcon(), "WhatsApp owner", Tint(b.success.container, Brand.WhatsApp), { openWhatsApp(context, phone) }, size = 40.dp)
                                }
                            }
                        }
                    }

                    // ----- manage photos -----
                    SectionTitle("Photos")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.photoIds.forEach { id ->
                            Box(
                                Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))
                                    .combinedClickable(onClick = {}, onLongClick = { deletePhoto = id }),
                            ) { PropertyPhoto(p.id, id, Modifier.fillMaxSize(), maxPx = 200) }
                        }
                        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = uploading == 0, modifier = Modifier.height(72.dp)) {
                            Icon(Icons.Filled.AddPhotoAlternate, null)
                            Text(if (uploading > 0) "  Uploading $uploading…" else "  Add")
                        }
                    }
                    if (p.photoIds.isNotEmpty()) Text("Long-press a photo to remove it.", style = MaterialTheme.typography.bodySmall, color = b.muted)

                    p.notes?.let {
                        SectionTitle("Notes")
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }

                    SectionTitle("Interested clients")
                    LoadContent(matches) { list ->
                        Column {
                            if (list.isEmpty()) EmptyMessage("No active client requirements match this listing")
                            list.forEach { ClientMatchCard(it) { onInquiry(it.inquiry.id) } }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            confirmStatus?.let { a ->
                AlertDialog(
                    onDismissRequest = { confirmStatus = null },
                    title = { Text("Mark as ${a.label.lowercase()}?") },
                    text = {
                        Text(
                            if (a == Availability.AVAILABLE) "It will show up in client matches again."
                            else "It stops appearing in client matches. You can mark it available again later.",
                        )
                    },
                    confirmButton = { TextButton(onClick = { confirmStatus = null; setAvailability(p, a) }) { Text("Mark as ${a.label}") } },
                    dismissButton = { TextButton(onClick = { confirmStatus = null }) { Text("Cancel") } },
                )
            }
            deletePhoto?.let { id ->
                AlertDialog(
                    onDismissRequest = { deletePhoto = null },
                    title = { Text("Remove this photo?") },
                    confirmButton = {
                        TextButton(onClick = {
                            deletePhoto = null
                            scope.launch {
                                api.call { deletePropertyPhoto(p.id, id) }
                                    .onSuccess { loader.reload() }
                                    .onFailure { toast(context, it.message ?: "Couldn't remove the photo") }
                            }
                        }) { Text("Remove") }
                    },
                    dismissButton = { TextButton(onClick = { deletePhoto = null }) { Text("Cancel") } },
                )
            }
        }
    }
}

/** Shares the listing's details as text (WhatsApp, SMS, email…). */
private fun shareProperty(context: Context, p: Property) {
    val lines = listOfNotNull(
        p.title,
        listOfNotNull(p.building, p.locality).joinToString(", "),
        priceText(p),
        listOfNotNull(
            p.carpetAreaSqft?.let { "$it sq ft" },
            p.furnishing?.chipLabel(),
            p.bathrooms?.let { "$it bath" },
            p.floor?.let { f -> p.totalFloors?.let { "floor $f of $it" } ?: "floor $f" },
        ).joinToString(" · ").ifEmpty { null },
        p.deposit?.let { "Deposit ${Money.full(it)}" },
    )
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
    context.startActivity(Intent.createChooser(intent, "Share property"))
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.brand.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FactGrid(facts: List<Pair<String, String>>) {
    facts.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            row.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
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
    var baths by rememberText()
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
            area = p.carpetAreaSqft?.toString() ?: ""; baths = p.bathrooms?.toString() ?: ""; furnishing = p.furnishing
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
    val pBaths = opt(baths) { it.toIntOrNull()?.takeIf { n -> n in 0..20 } }
    val pFloor = opt(floor) { it.toIntOrNull()?.takeIf { n -> n in -5..200 } }
    val pTotal = opt(totalFloors) { it.toIntOrNull()?.takeIf { n -> n in 0..200 } }
    val pDate = opt(possessionDate) { runCatching { LocalDate.parse(it) }.getOrNull() }
    val valid = loaded && title.isNotBlank() && category != null && locality.isNotBlank() && pPrice != null && pPrice > 0 &&
        listOf(pDeposit, pArea, pParking, pBaths, pFloor, pTotal, pDate).all { it.isSuccess }

    fun save() {
        val body = PropertyRequest(
            title = title.trim(), transactionType = type, category = category ?: return, price = pPrice ?: return,
            deposit = pDeposit.getOrNull(), locality = locality.trim(),
            building = building.trim().ifEmpty { null }, address = address.trim().ifEmpty { null },
            carpetAreaSqft = pArea.getOrNull(), bathrooms = pBaths.getOrNull(), furnishing = furnishing, parkingSpots = pParking.getOrNull(),
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
                NumberField("Bathrooms", baths, { baths = it }, Modifier.weight(1f), isError = pBaths.isFailure)
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
            OutlinedTextField(
                notes, { notes = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Tip: add the portal listing ID (e.g. 99acres I94007278) so enquiries are matched to this listing.") },
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Saving…" else "Save property")
            }
        }
    }
}
