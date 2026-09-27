@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.properties

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.brokerbuddy.core.match.MatchLabel
import com.brokerbuddy.core.match.Matching
import com.brokerbuddy.core.model.Availability
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.InquiryMatch
import com.brokerbuddy.core.model.Possession
import com.brokerbuddy.core.model.Property
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.PropertyRequest
import com.brokerbuddy.core.model.PropertyType
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
    // null = all availabilities
    var availability by rememberSaveable { mutableStateOf<Availability?>(Availability.AVAILABLE) }
    var category by rememberSaveable { mutableStateOf<PropertyCategory?>(null) }
    var propertyType by rememberSaveable { mutableStateOf<PropertyType?>(null) }
    var furnishing by rememberSaveable { mutableStateOf<Furnishing?>(null) }
    var minPrice by rememberSaveable { mutableStateOf<Long?>(null) }
    var maxPrice by rememberSaveable { mutableStateOf<Long?>(null) }
    var priceDialog by remember { mutableStateOf(false) }
    val filtered = category != null || propertyType != null || furnishing != null || minPrice != null || maxPrice != null ||
        availability != Availability.AVAILABLE || query.isNotBlank()
    fun clearFilters() {
        query = ""; availability = Availability.AVAILABLE; category = null; propertyType = null; furnishing = null
        minPrice = null; maxPrice = null
    }
    val loader = rememberLoad(query, type, availability, category, propertyType, furnishing, minPrice, maxPrice) {
        if (query.isNotBlank()) delay(300)
        api.call {
            properties(
                q = query.ifBlank { null }, transactionType = type, availability = availability, category = category,
                propertyType = propertyType, furnishing = furnishing, minPrice = minPrice, maxPrice = maxPrice,
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
            SearchField(query, { query = it }, "Search title, area or society...", Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuChip("BHK", category, PropertyCategory.entries, { it.label }) { category = it }
                FilterChip(
                    selected = minPrice != null || maxPrice != null,
                    onClick = { priceDialog = true },
                    label = { Text(Money.range(minPrice, maxPrice) ?: "Price") },
                )
                MenuChip("Type", propertyType, PropertyType.entries, { it.label }) { propertyType = it }
                MenuChip("Furnishing", furnishing, Furnishing.entries, { it.label }) { furnishing = it }
                MenuChip(
                    "All availability", availability, Availability.entries, { if (it == Availability.AVAILABLE) "Available" else it.label },
                    noneLabel = "All availability",
                ) { availability = it }
                if (filtered) TextButton(onClick = ::clearFilters) { Text("Clear filters") }
            }
            PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.fillMaxSize()) {
                LoadContent(loader) { list ->
                    if (list.properties.isEmpty()) {
                        EmptyMessage(
                            if (filtered) "No ${type.label.lowercase()} properties match these filters."
                            else "No ${type.label.lowercase()} properties yet. Tap + to add a listing.",
                        )
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
                                    badge = p.availability.takeIf { it != Availability.AVAILABLE }?.let { "Not available · ${it.label}" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (priceDialog) {
        PriceRangeDialog(minPrice, maxPrice, onDismiss = { priceDialog = false }) { lo, hi ->
            minPrice = lo; maxPrice = hi; priceDialog = false
        }
    }
}

/** A filter chip with a menu; the first entry clears it. */
@Composable
private fun <T> MenuChip(label: String, selected: T?, options: List<T>, optionLabel: (T) -> String, noneLabel: String = "Any", onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected != null, onClick = { open = true }, label = { Text(selected?.let(optionLabel) ?: label) })
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); open = false })
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
private fun PriceRangeDialog(min: Long?, max: Long?, onDismiss: () -> Unit, onApply: (Long?, Long?) -> Unit) {
    var lo by remember { mutableStateOf(min?.toString() ?: "") }
    var hi by remember { mutableStateOf(max?.toString() ?: "") }
    val pLo = lo.takeIf { it.isNotBlank() }?.let(Money::parse)
    val pHi = hi.takeIf { it.isNotBlank() }?.let(Money::parse)
    val loBad = lo.isNotBlank() && pLo == null
    val hiBad = hi.isNotBlank() && pHi == null
    val inverted = pLo != null && pHi != null && pLo > pHi
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Minimum", lo, { lo = it }, Modifier.fillMaxWidth(), pLo?.let(Money::full) ?: "e.g. 50k, 80 lakh", loBad, KeyboardType.Text)
                NumberField(
                    "Maximum", hi, { hi = it }, Modifier.fillMaxWidth(),
                    if (inverted) "Below the minimum" else pHi?.let(Money::full) ?: "e.g. 1.2 Cr", hiBad || inverted, KeyboardType.Text,
                )
            }
        },
        confirmButton = { TextButton(enabled = !loBad && !hiBad && !inverted, onClick = { onApply(pLo, pHi) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = { onApply(null, null) }) { Text("Clear") } },
    )
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
    var replacing by remember { mutableStateOf<String?>(null) }

    suspend fun upload(uri: android.net.Uri): Boolean {
        val bytes = PropertyPhotos.prepareUpload(context, uri)
        if (bytes == null) {
            toast(context, "Couldn't read that photo")
            return false
        }
        val part = MultipartBody.Part.createFormData("photo", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
        return api.call { uploadPropertyPhoto(propertyId, part) }.onFailure { toast(context, it.message ?: "Upload failed") }.isSuccess
    }

    // Replace = add the new photo, then remove the old one (so a failed upload loses nothing).
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val old = replacing
        replacing = null
        if (uri == null || old == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = 1
            if (upload(uri)) {
                api.call { deletePropertyPhoto(propertyId, old) }.onFailure { toast(context, "New photo added, but the old one couldn't be removed") }
            }
            uploading = 0
            loader.reload()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = uris.size
            for (uri in uris) {
                upload(uri)
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
                        colors = if (next == Availability.AVAILABLE) ButtonDefaults.buttonColors(containerColor = b.success.content)
                        else ButtonDefaults.buttonColors(containerColor = Brand.Red, contentColor = Color.White),
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
                        p.propertyType?.let { Pill(it.label, b.info) }
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
                                "Floor" to p.floor?.let { f ->
                                    Matching.floorBandOf(f, p.totalFloors)?.let { band -> "$f of ${p.totalFloors} (${band.label.lowercase()})" }
                                        ?: "$f (total floors not recorded)"
                                },
                                "Built-up area" to p.builtUpAreaSqft?.let { "$it sq ft" },
                                "Parking" to p.parkingSpots?.let { if (it == 0) "None" else "$it" },
                                "Available" to (p.possession?.label ?: p.possessionDate?.let(::formatDate)),
                                "Deposit" to p.deposit?.let(Money::full),
                                "Address" to p.address,
                            ).filter { it.second != null }.map { it.first to it.second!! },
                        )
                    }

                    if (p.amenities.isNotEmpty()) {
                        SectionTitle("Amenities")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.amenities.forEach { Pill(it, b.neutral) }
                        }
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
                                    .combinedClickable(onClick = { deletePhoto = id }, onLongClick = { deletePhoto = id }),
                            ) { PropertyPhoto(p.id, id, Modifier.fillMaxSize(), maxPx = 200) }
                        }
                        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = uploading == 0, modifier = Modifier.height(72.dp)) {
                            Icon(Icons.Filled.AddPhotoAlternate, null)
                            Text(if (uploading > 0) "  Uploading $uploading…" else "  Add")
                        }
                    }
                    Text(
                        if (p.photoIds.isEmpty()) "No photos yet — add some so clients can see the place."
                        else "Tap a photo to replace or remove it. The first photo is the cover.",
                        style = MaterialTheme.typography.bodySmall, color = b.muted,
                    )

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
                    title = { Text("Photo") },
                    text = {
                        Column {
                            PropertyPhoto(p.id, id, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)), maxPx = 800)
                            TextButton(onClick = { deletePhoto = null; replacing = id; replacePicker.launch("image/*") }) { Text("Replace with another photo") }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            deletePhoto = null
                            scope.launch {
                                api.call { deletePropertyPhoto(p.id, id) }
                                    .onSuccess { loader.reload() }
                                    .onFailure { toast(context, it.message ?: "Couldn't remove the photo") }
                            }
                        }) { Text("Remove photo", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { deletePhoto = null }) { Text("Close") } },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.inquiry.client?.name ?: "Client", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill(MatchLabel.of(m.score, m.checks), if (MatchLabel.isExact(m.checks)) MaterialTheme.brand.success else MaterialTheme.brand.neutral)
            }
            Text(
                listOfNotNull(
                    "${m.inquiry.transactionType.label} · ${m.inquiry.category.label}",
                    Money.range(m.inquiry.budgetMin, m.inquiry.budgetMax),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted,
            )
            m.checks.filter { it.outcome != "n/a" }.forEach { CheckLine(it) }
        }
    }
}

private val COMMON_AMENITIES = listOf("Lift", "Security", "Power backup", "Gym", "Swimming pool", "Clubhouse", "Garden", "Sea view")

@Composable
fun PropertyFormScreen(propertyId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    var title by rememberText()
    var type by rememberSaveable { mutableStateOf(TransactionType.RENT) }
    var category by rememberSaveable { mutableStateOf<PropertyCategory?>(null) }
    var propertyType by rememberSaveable { mutableStateOf<PropertyType?>(null) }
    var price by rememberText()
    var deposit by rememberText()
    var locality by rememberText()
    var building by rememberText()
    var address by rememberText()
    var area by rememberText()
    var builtUp by rememberText()
    // Comma-separated, so it survives rotation like the other fields.
    var amenities by rememberText()
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
            title = p.title; type = p.transactionType; category = p.category; propertyType = p.propertyType
            builtUp = p.builtUpAreaSqft?.toString() ?: ""; amenities = p.amenities.joinToString(", ")
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
    val pBuiltUp = opt(builtUp) { it.toIntOrNull()?.takeIf { n -> n > 0 } }
    val amenityList = amenities.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    val pParking = opt(parking) { it.toIntOrNull()?.takeIf { n -> n in 0..50 } }
    val pBaths = opt(baths) { it.toIntOrNull()?.takeIf { n -> n in 0..20 } }
    val pFloor = opt(floor) { it.toIntOrNull()?.takeIf { n -> n in -5..200 } }
    val pTotal = opt(totalFloors) { it.toIntOrNull()?.takeIf { n -> n in 0..200 } }
    val pDate = opt(possessionDate) { runCatching { LocalDate.parse(it) }.getOrNull() }
    val valid = loaded && title.isNotBlank() && category != null && locality.isNotBlank() && pPrice != null && pPrice > 0 &&
        listOf(pDeposit, pArea, pBuiltUp, pParking, pBaths, pFloor, pTotal, pDate).all { it.isSuccess } &&
        (pFloor.getOrNull() == null || pTotal.getOrNull() == null || pFloor.getOrNull()!! <= pTotal.getOrNull()!!)

    fun save() {
        val body = PropertyRequest(
            title = title.trim(), transactionType = type, category = category ?: return, propertyType = propertyType, price = pPrice ?: return,
            deposit = pDeposit.getOrNull(), locality = locality.trim(),
            building = building.trim().ifEmpty { null }, address = address.trim().ifEmpty { null },
            carpetAreaSqft = pArea.getOrNull(), builtUpAreaSqft = pBuiltUp.getOrNull(), amenities = amenityList, bathrooms = pBaths.getOrNull(), furnishing = furnishing, parkingSpots = pParking.getOrNull(),
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
            DropdownField("BHK / size *", PropertyCategory.entries, category, { it.label }, { category = it }, Modifier.fillMaxWidth())
            DropdownField("Property type", PropertyType.entries, propertyType, { it.label }, { propertyType = it }, Modifier.fillMaxWidth(), allowNone = true)
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
                NumberField("Built-up sq ft", builtUp, { builtUp = it }, Modifier.weight(1f), isError = pBuiltUp.isFailure)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Bathrooms", baths, { baths = it }, Modifier.weight(1f), isError = pBaths.isFailure)
                NumberField("Parking", parking, { parking = it }, Modifier.weight(1f), isError = pParking.isFailure)
            }
            val floorAboveTotal = pFloor.getOrNull() != null && pTotal.getOrNull() != null && pFloor.getOrNull()!! > pTotal.getOrNull()!!
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Floor (0 = ground)", floor, { floor = it }, Modifier.weight(1f), isError = pFloor.isFailure || floorAboveTotal)
                NumberField(
                    "Total floors", totalFloors, { totalFloors = it }, Modifier.weight(1f),
                    if (floorAboveTotal) "Below the floor" else null, pTotal.isFailure || floorAboveTotal,
                )
            }
            Text(
                "Floor and total floors tell clients whether it's a lower, middle or higher floor.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted,
            )
            DropdownField("Furnishing", Furnishing.entries, furnishing, { it.label }, { furnishing = it }, Modifier.fillMaxWidth(), allowNone = true)
            DropdownField("Possession", Possession.entries, possession, { it.label }, { possession = it }, Modifier.fillMaxWidth(), allowNone = true)
            if (possession == Possession.UNDER_CONSTRUCTION) {
                NumberField(
                    "Possession date (YYYY-MM-DD)", possessionDate, { possessionDate = it }, Modifier.fillMaxWidth(),
                    isError = pDate.isFailure, keyboardType = KeyboardType.Text,
                )
            }
            SectionTitle("Amenities")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COMMON_AMENITIES.forEach { a ->
                    val on = amenityList.any { it.equals(a, ignoreCase = true) }
                    FilterChip(
                        selected = on,
                        onClick = {
                            amenities = (if (on) amenityList.filterNot { it.equals(a, ignoreCase = true) } else amenityList + a).joinToString(", ")
                        },
                        label = { Text(a) },
                    )
                }
            }
            OutlinedTextField(
                amenities, { amenities = it }, label = { Text("Amenities") }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Tap above or type your own, separated by commas") },
            )
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
