@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brokerbuddy.AppContainer
import com.brokerbuddy.BrokerBuddyApp
import com.brokerbuddy.core.phone.PhoneNumbers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as BrokerBuddyApp).container

/** Result of loading data for a screen. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Holder returned by [rememberLoad]; call [reload] to fetch again (e.g. after an edit). */
class Loader<T>(val state: Load<T>, val reload: () -> Unit)

/**
 * Loads data when the screen is shown and whenever [keys] change or [Loader.reload] is called.
 * Previous data stays visible while a reload is in flight.
 */
@Composable
fun <T> rememberLoad(vararg keys: Any?, fetch: suspend () -> Result<T>): Loader<T> {
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    var generation by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(generation, *keys) {
        fetch().fold(
            onSuccess = { state = Load.Ready(it) },
            onFailure = { if (state !is Load.Ready) state = Load.Failed(it.message ?: "Something went wrong") },
        )
    }
    return Loader(state) { generation++ }
}

@Composable
fun <T> LoadContent(loader: Loader<T>, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> ErrorMessage(s.message, onRetry = loader.reload)
        is Load.Ready -> content(s.value)
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
        }
    }
}

@Composable
fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun LabeledValue(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Unknown values are shown as such — never filled with a guess.
        Text(value ?: "Not specified", Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Dropdown for enums (or any list) with an optional "not specified" entry. */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: "Not specified",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text("Not specified") }, onClick = { onSelect(null); expanded = false })
            }
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** Multi-select chips (e.g. acceptable furnishing types). */
@Composable
fun <T> ChipSelector(options: List<T>, selected: Set<T>, label: (T) -> String, onChange: (Set<T>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onChange(if (option in selected) selected - option else selected + option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}

@Composable
fun rememberText(initial: String? = null) = rememberSaveable { mutableStateOf(initial ?: "") }

private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun formatDateTime(iso: String): String =
    runCatching { dateTimeFormat.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(iso)

fun formatDate(iso: String): String =
    runCatching { dateFormat.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(iso)

/** Opens the dialer — no CALL_PHONE permission needed; the broker taps call themselves. */
fun dial(context: Context, e164: String) {
    launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$e164")), "No phone app found")
}

/**
 * Opens a WhatsApp chat via the official click-to-chat link. Works with WhatsApp or
 * WhatsApp Business; falls back to the browser (web.whatsapp / install page) otherwise.
 */
fun openWhatsApp(context: Context, e164: String, message: String? = null) {
    val uri = Uri.parse("https://wa.me/${PhoneNumbers.whatsAppDigits(e164)}").buildUpon().apply {
        if (!message.isNullOrBlank()) appendQueryParameter("text", message)
    }.build()
    launch(context, Intent(Intent.ACTION_VIEW, uri), "No app can open WhatsApp links")
}

fun sendSms(context: Context, e164: String, message: String? = null) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$e164")).apply {
        if (message != null) putExtra("sms_body", message)
    }
    launch(context, intent, "No SMS app found")
}

private fun launch(context: Context, intent: Intent, failure: String) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
    }
}

fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
