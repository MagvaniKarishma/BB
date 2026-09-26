package com.brokerbuddy.ui.portal

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brokerbuddy.R
import com.brokerbuddy.core.caller.CallerCards
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.IntegrationStatus
import com.brokerbuddy.core.model.NoteSource
import com.brokerbuddy.core.model.Portal
import com.brokerbuddy.core.model.PortalCsvImportRequest
import com.brokerbuddy.core.model.PortalIntegration
import com.brokerbuddy.core.model.PortalLeadItem
import com.brokerbuddy.core.model.PortalLeadStatus
import com.brokerbuddy.core.model.PortalListingSummary
import com.brokerbuddy.core.model.PortalTextImportRequest
import com.brokerbuddy.core.model.UpdatePortalLeadRequest
import com.brokerbuddy.core.portal.DateFilter
import com.brokerbuddy.core.portal.DateWindow
import com.brokerbuddy.core.portal.DateWindows
import com.brokerbuddy.core.portal.PortalListingText
import com.brokerbuddy.ui.caller.AddNoteDialog
import com.brokerbuddy.ui.common.EmptyMessage
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.LoadContent
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.dial
import com.brokerbuddy.ui.common.formatDateTime
import com.brokerbuddy.ui.common.openWhatsApp
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.design.BrandBackBar
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.FilterTabs
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.design.PropertyPhoto
import com.brokerbuddy.ui.design.RoundIconButton
import com.brokerbuddy.ui.design.TabItem
import com.brokerbuddy.ui.design.whatsAppIcon
import com.brokerbuddy.ui.reminders.AddReminderDialog
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.Tint
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The date filter shared by the listing list and a listing's clients. */
data class PortalFilter(val filter: DateFilter, val start: LocalDate? = null, val end: LocalDate? = null) {
    fun window(zone: ZoneId = ZoneId.systemDefault()): DateWindow? = DateWindows.of(filter, LocalDate.now(zone), zone, start, end)

    fun label(): String = when {
        filter != DateFilter.CUSTOM -> filter.label
        start != null && end != null -> if (start == end) "$start" else "$start – $end"
        else -> "Custom"
    }

    /** Route query for passing the filter to the next screen. */
    fun query(): String = "filter=${filter.name}&start=${start?.toString().orEmpty()}&end=${end?.toString().orEmpty()}"

    companion object {
        fun from(filter: String?, start: String?, end: String?) = PortalFilter(
            DateFilter.entries.firstOrNull { it.name == filter } ?: DateFilter.TODAY,
            start?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            end?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }
}

@Composable
private fun portalTint(portal: Portal): Tint = if (portal == Portal.ACRES_99) MaterialTheme.brand.info else MaterialTheme.brand.purple

private fun integrationTint(status: IntegrationStatus, b: com.brokerbuddy.ui.theme.BrandColors): Tint = when (status) {
    IntegrationStatus.CONNECTED -> b.success
    IntegrationStatus.NOT_CONNECTED -> b.amber
    IntegrationStatus.IMPORT_REQUIRED -> b.neutral
}

/** Today / Yesterday / Last 7 Days / Custom, with a date-range picker for Custom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateFilterBar(value: PortalFilter, onChange: (PortalFilter) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    FilterTabs(
        DateFilter.entries.map { TabItem(it, if (it == DateFilter.CUSTOM && value.filter == DateFilter.CUSTOM) value.label() else it.label) },
        selected = value.filter,
        onSelect = { if (it == DateFilter.CUSTOM) picking = true else onChange(PortalFilter(it)) },
        modifier = modifier,
    )
    if (picking) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        // The picker reports midnight UTC of the chosen days.
                        val s = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        val e = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: s
                        if (s != null) onChange(PortalFilter(DateFilter.CUSTOM, s, e))
                        picking = false
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DateRangePicker(state, modifier = Modifier.height(480.dp)) }
    }
}

/**
 * A listing photo from the portal (public image link) or the broker's own property photo;
 * the stock artwork when neither is available. Portal images are fetched without the
 * BrokerBuddy sign-in, so the account token never goes to another site.
 */
@Composable
fun ListingPhoto(listing: PortalListingSummary, modifier: Modifier = Modifier) {
    val url = listing.photoUrl
    when {
        url != null && url.startsWith("https://") -> RemoteImage(url, modifier)
        listing.propertyId != null -> PropertyPhoto(listing.propertyId!!, listing.propertyPhotoId, modifier, maxPx = 600)
        else -> Image(painterResource(R.drawable.property_placeholder), null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

@Composable
private fun RemoteImage(url: String, modifier: Modifier) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.inputStream.use { input ->
                    val bytes = input.readNBytesCompat(4 * 1024 * 1024)
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(modifier) {
        val img = image
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Image(painterResource(R.drawable.property_placeholder), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(context, "No app can open this link") }
}

private fun relative(iso: String) = CallerCards.relative(iso, Instant.now(), ZoneId.systemDefault())

// ---------- Home → Today's Work → 99acres / Housing.com Leads ----------

/** A portal's listings that received identifiable enquiries, most recent enquiry first. */
@Composable
fun PortalLeadsScreen(
    portal: Portal,
    initial: PortalFilter,
    onBack: () -> Unit,
    onListing: (listingId: String, filter: PortalFilter) -> Unit,
) {
    val api = appContainer().api
    var filter by rememberSaveable(stateSaver = PortalFilterSaver) { mutableStateOf(initial) }
    var showSetup by remember { mutableStateOf(false) }
    val window = filter.window()
    val loader = rememberLoad(portal, filter) {
        api.call { portalListings(portal, window?.from?.toString(), window?.to?.toString()).listings }
    }
    val integration = rememberLoad(portal) { api.call { portalIntegrations().portals.firstOrNull { it.portal == portal } } }
    val status = (integration.state as? Load.Ready)?.value

    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = {
            BrandBackBar("${portal.label} Leads", onBack) {
                IconButton(onClick = { showSetup = true }) { Icon(Icons.Outlined.Settings, "Lead sources and import") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            status?.let { IntegrationBanner(it, onSetup = { showSetup = true }) }
            DateFilterBar(filter, { filter = it }, Modifier.padding(vertical = 8.dp))
            LoadContent(loader) { listings ->
                if (listings.isEmpty()) {
                    EmptyPortal(portal, filter, status, onSetup = { showSetup = true })
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(listings, key = { it.id }) { l -> ListingCard(l, onClick = { onListing(l.id, filter) }) }
                    }
                }
            }
        }
    }
    if (showSetup) {
        LeadSourcesSheet(portal, status, onDismiss = { showSetup = false }, onImported = { loader.reload(); integration.reload() })
    }
}

private val PortalFilterSaver = androidx.compose.runtime.saveable.Saver<PortalFilter, String>(
    save = { it.query() },
    restore = { q ->
        val m = q.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        PortalFilter.from(m["filter"], m["start"], m["end"])
    },
)

@Composable
private fun IntegrationBanner(status: PortalIntegration, onSetup: () -> Unit) {
    val b = MaterialTheme.brand
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(status.status.label, integrationTint(status.status, b))
        Spacer(Modifier.width(8.dp))
        Text(
            when (status.status) {
                IntegrationStatus.CONNECTED -> "Leads arrive automatically" + (status.lastReceivedAt?.let { " · last ${relative(it)}" } ?: "")
                IntegrationStatus.NOT_CONNECTED -> "Inbound link created, no lead received yet"
                IntegrationStatus.IMPORT_REQUIRED -> "Share, paste or import leads to see them here"
            },
            style = MaterialTheme.typography.bodySmall, color = b.muted, modifier = Modifier.weight(1f), maxLines = 2,
        )
        TextButton(onClick = onSetup) { Text("Set up", color = b.link) }
    }
}

@Composable
private fun EmptyPortal(portal: Portal, filter: PortalFilter, status: PortalIntegration?, onSetup: () -> Unit) {
    val b = MaterialTheme.brand
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No ${portal.label} leads — ${filter.label().lowercase()}", style = MaterialTheme.typography.titleMedium, color = b.navy)
        Text(
            if (status?.status == IntegrationStatus.CONNECTED) {
                "No enquiries arrived for these dates. Try Last 7 Days."
            } else {
                "Only enquiries that reach BrokerBuddy are shown (views and clicks on the portal aren't leads). " +
                    "Share a ${portal.label} lead message from WhatsApp, paste a lead email, or import the lead export."
            },
            style = MaterialTheme.typography.bodyMedium, color = b.muted,
        )
        OutlinedButton(onClick = onSetup) { Text("Import or connect ${portal.label}") }
    }
}

@Composable
private fun ListingCard(l: PortalListingSummary, onClick: () -> Unit) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    BrandCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = 0.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            ListingPhoto(l, Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(PortalListingText.title(l), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PortalListingText.location(l)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    l.category?.let { Pill(it.label, b.info) }
                    PortalListingText.price(l)?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = b.navy) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${l.interestedClients} interested", style = MaterialTheme.typography.labelMedium, color = b.navy)
                    if (l.newLeads > 0) Pill("${l.newLeads} new", b.danger)
                }
                Text("Last enquiry ${relative(l.lastEnquiryAt)}", style = MaterialTheme.typography.bodySmall, color = b.muted)
                if (!l.identified) {
                    Text("The source didn't say which listing these enquiries were about.", style = MaterialTheme.typography.bodySmall, color = b.muted)
                }
                l.url?.let { url ->
                    TextButton(onClick = { openLink(context, url) }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = b.link)
                        Spacer(Modifier.width(4.dp))
                        Text("Open on ${l.portal.label}", color = b.link, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ---------- listing → interested clients ----------

@Composable
fun PortalListingScreen(
    portal: Portal,
    listingId: String,
    initial: PortalFilter,
    onBack: () -> Unit,
    onClient: (String) -> Unit,
) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable(stateSaver = PortalFilterSaver) { mutableStateOf(initial) }
    val window = filter.window()
    val loader = rememberLoad(listingId, filter) {
        api.call { portalListing(listingId, portal, window?.from?.toString(), window?.to?.toString()) }
    }
    var noteFor by remember { mutableStateOf<PortalLeadItem?>(null) }
    var followUpFor by remember { mutableStateOf<PortalLeadItem?>(null) }

    fun setStatus(lead: PortalLeadItem, status: PortalLeadStatus) {
        scope.launch {
            api.call { updatePortalLead(lead.id, UpdatePortalLeadRequest(status)) }
                .onSuccess { loader.reload() }
                .onFailure { toast(context, it.message ?: "Couldn't update") }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = { BrandBackBar("Interested Clients", onBack) },
    ) { padding ->
        LoadContent(loader) { detail ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ListingHeader(portal, detail.listing, detail.totalInterestedClients) }
                item { DateFilterBar(filter, { filter = it }) }
                if (detail.leads.isEmpty()) {
                    item { EmptyMessage("No enquiries about this listing — ${filter.label().lowercase()}. Try Last 7 Days.") }
                }
                items(detail.leads, key = { it.id }) { lead ->
                    LeadCard(
                        lead,
                        onClient = onClient,
                        onNote = { noteFor = lead },
                        onFollowUp = { followUpFor = lead },
                        onStatus = { setStatus(lead, it) },
                    )
                }
            }
        }
    }
    noteFor?.let { lead ->
        val client = lead.client
        if (client != null) {
            AddNoteDialog(client.id, client.name, NoteSource.MANUAL, onDismiss = { noteFor = null }, onSaved = {
                noteFor = null
                toast(context, "Note saved to ${client.name}")
            })
        }
    }
    followUpFor?.let { lead ->
        val client = lead.client
        if (client != null) {
            AddReminderDialog(
                clientId = client.id,
                defaultTitle = "Follow up: ${lead.listing?.title ?: portal.label + " enquiry"}",
                onDismiss = { followUpFor = null },
                onCreated = {
                    followUpFor = null
                    if (lead.status == PortalLeadStatus.NEW) setStatus(lead, PortalLeadStatus.FOLLOW_UP)
                    toast(context, "Follow-up saved")
                },
            )
        }
    }
}

@Composable
private fun ListingHeader(portal: Portal, l: PortalListingSummary?, interested: Int) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    BrandCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        if (l != null) ListingPhoto(l, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(portal.label, portalTint(portal))
                l?.externalId?.let { Text("  ID $it", style = MaterialTheme.typography.bodySmall, color = b.muted) }
            }
            Text(l?.let(PortalListingText::title) ?: "Listing", style = MaterialTheme.typography.titleMedium)
            l?.locality?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = b.muted) }
            l?.let(PortalListingText::price)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = b.navy) }
            l?.carpetAreaSqft?.let { Text("$it sq ft carpet", style = MaterialTheme.typography.bodySmall, color = b.muted) }
            Text("$interested identifiable interested client${if (interested == 1) "" else "s"} in total", style = MaterialTheme.typography.labelLarge, color = b.navy)
            l?.url?.let { url -> TextButton(onClick = { openLink(context, url) }, contentPadding = PaddingValues(0.dp)) { Text("Open original ${portal.label} listing", color = b.link) } }
            if (l?.propertyId != null) Text("Matched to your property listing", style = MaterialTheme.typography.bodySmall, color = b.success.content)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeadCard(
    lead: PortalLeadItem,
    onClient: (String) -> Unit,
    onNote: () -> Unit,
    onFollowUp: () -> Unit,
    onStatus: (PortalLeadStatus) -> Unit,
) {
    val b = MaterialTheme.brand
    val context = LocalContext.current
    var statusMenu by remember { mutableStateOf(false) }
    val phone = lead.phone ?: lead.client?.primaryPhone
    val name = lead.client?.name ?: lead.name ?: phone ?: lead.email ?: "Name not given"
    BrandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                phone?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = b.muted) }
                if (phone == null) lead.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = b.muted) }
            }
            Box {
                TextButton(onClick = { statusMenu = true }) { Pill(lead.status.label, statusTint(lead.status)) }
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    PortalLeadStatus.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { statusMenu = false; if (s != lead.status) onStatus(s) })
                    }
                }
            }
        }
        Text("Enquired ${formatDateTime(lead.enquiredAt)} · via ${lead.channel.label}", style = MaterialTheme.typography.bodySmall, color = b.muted)
        val budget = Money.range(lead.budgetMin, lead.budgetMax)
        if (budget != null || lead.requirement != null) {
            Text(listOfNotNull(budget?.let { "Budget $it" }, lead.requirement).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
        lead.message?.let {
            Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = b.navy, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (phone != null) {
                RoundIconButton(Icons.Filled.Call, "Call", b.info, { dial(context, phone) }, size = 40.dp)
                RoundIconButton(whatsAppIcon(), "WhatsApp", Tint(b.success.container, Brand.WhatsApp), { openWhatsApp(context, phone) }, size = 40.dp)
            }
            val client = lead.client
            if (client != null) {
                RoundIconButton(Icons.Outlined.Person, "Open client profile", b.neutral, { onClient(client.id) }, size = 40.dp)
                RoundIconButton(Icons.Outlined.EditNote, "Add note", b.amber, onNote, size = 40.dp)
                RoundIconButton(Icons.Outlined.CalendarMonth, "Schedule follow-up", b.purple, onFollowUp, size = 40.dp)
            }
        }
        if (lead.client == null) {
            Text(
                "No phone number or email linked this enquiry to a client, so notes and follow-ups aren't available.",
                style = MaterialTheme.typography.bodySmall, color = b.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun statusTint(s: PortalLeadStatus): Tint {
    val b = MaterialTheme.brand
    return when (s) {
        PortalLeadStatus.NEW -> b.danger
        PortalLeadStatus.CONTACTED -> b.info
        PortalLeadStatus.FOLLOW_UP -> b.amber
        PortalLeadStatus.CONVERTED -> b.success
        PortalLeadStatus.NOT_INTERESTED, PortalLeadStatus.CLOSED -> b.neutral
    }
}

// ---------- lead sources: status, imports, setup ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeadSourcesSheet(portal: Portal, status: PortalIntegration?, onDismiss: () -> Unit, onImported: () -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val b = MaterialTheme.brand
    var busy by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var newKeyUrl by remember { mutableStateOf<String?>(null) }

    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(2_000_000).toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (text == null) {
                result = "Couldn't read the file."
            } else {
                api.call { importPortalCsv(PortalCsvImportRequest(portal, text, uri.lastPathSegment)) }
                    .onSuccess { r ->
                        result = "Imported ${r.created} new lead${if (r.created == 1) "" else "s"}" +
                            (if (r.duplicates > 0) ", ${r.duplicates} already here" else "") +
                            (if (r.clientsCreated > 0) ", ${r.clientsCreated} new client${if (r.clientsCreated == 1) "" else "s"}" else "") +
                            (if (r.skipped.isNotEmpty()) ". Skipped ${r.skipped.size}: " + r.skipped.take(3).joinToString("; ") { "row ${it.row} – ${it.reason}" } else "")
                        onImported()
                    }
                    .onFailure { result = it.message }
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${portal.label} lead sources") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(it.status.label, integrationTint(it.status, b))
                        Spacer(Modifier.width(8.dp))
                        val counts = it.leadsByChannel.entries.joinToString { (k, v) -> "$v ${k.lowercase()}" }
                        if (counts.isNotEmpty()) Text(counts, style = MaterialTheme.typography.bodySmall, color = b.muted)
                    }
                }
                Text(
                    "${portal.label} has no public lead API, so BrokerBuddy doesn't read your portal account. Leads get here by:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("• WhatsApp: share a ${portal.label} lead message to BrokerBuddy (works now).", style = MaterialTheme.typography.bodySmall)
                Text("• Lead export: download leads from ${portal.label} as CSV and import it.", style = MaterialTheme.typography.bodySmall)
                Text("• Lead email: paste the email ${portal.label} sent you.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "• Inbound link (owners): for a CRM integration or email forwarding tool that can post leads. Shows Connected only after a lead actually arrives.",
                    style = MaterialTheme.typography.bodySmall,
                )
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = b.navy) }
                newKeyUrl?.let {
                    Text("Your inbound link (shown once — copy it now):", style = MaterialTheme.typography.labelMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = b.link)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { pickCsv.launch("*/*") }) { Text("Import CSV") }
                    OutlinedButton(enabled = !busy, onClick = { pasting = true }) { Text("Paste lead email") }
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val server = appContainer_serverUrl(context)
                            api.call { createInboundKey(portal) }
                                .onSuccess { newKeyUrl = server.trimEnd('/') + it.path; onImported() }
                                .onFailure { result = it.message }
                            busy = false
                        }
                    }) { Text(if (status?.inboundKeyPrefix != null) "New inbound link" else "Create inbound link") }
                }
                if (busy) Text("Working…", style = MaterialTheme.typography.bodySmall, color = b.muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (pasting) PasteLeadDialog(portal, onDismiss = { pasting = false }, onDone = { msg -> pasting = false; result = msg; onImported() })
}

private suspend fun appContainer_serverUrl(context: android.content.Context): String =
    (context.applicationContext as com.brokerbuddy.BrokerBuddyApp).container.sessionStore.current().serverUrl

@Composable
private fun PasteLeadDialog(portal: Portal, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    var text by rememberText()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a ${portal.label} lead email") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, minLines = 6, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Paste the whole email") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.length >= 10 && !busy, onClick = {
                busy = true
                scope.launch {
                    api.call { importPortalText(PortalTextImportRequest(text.trim(), portal)) }
                        .onSuccess { onDone(if (it.created) "Lead saved." else "This lead was already saved.") }
                        .onFailure { error = it.message }
                    busy = false
                }
            }) { Text("Save lead") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
