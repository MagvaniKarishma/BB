package com.brokerbuddy.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.brokerbuddy.calls.CallerDirectoryStore
import com.brokerbuddy.calls.CallerDirectorySyncWorker
import com.brokerbuddy.calls.CallerIdController
import com.brokerbuddy.calls.CallerSettings
import com.brokerbuddy.calls.CallerSetup
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast

/**
 * Walks the agent through what the caller screen needs. Every item is granted through
 * Android's own dialogs/settings; the section re-checks when the user comes back.
 */
@Composable
fun CallerSetupSection() {
    val context = LocalContext.current
    val settings = remember { CallerSettings(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ } }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    SectionTitle("Caller screen")
    if (!CallerSetup.isSupported) {
        Text(
            "Caller identification needs Android 10 or newer (this phone runs Android ${Build.VERSION.RELEASE}). " +
                "You can still search a caller's number under Clients.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        "Shows who is calling and what they're looking for. BrokerBuddy only reads the incoming number — it never blocks, " +
            "rejects or records calls.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Re-checked whenever the screen resumes (e.g. back from system settings).
    val status = remember(refresh) {
        listOf(
            CallerSetup.hasScreeningRole(context),
            CallerSetup.canNotify(context),
            CallerSetup.canDrawOverlays(context),
            CallerSetup.hasContactsPermission(context),
        )
    }
    val (hasRole, canNotify, canOverlay, hasContacts) = status

    SetupItem("Call screening access", "Required. Lets Android tell BrokerBuddy an incoming number.", hasRole) {
        val intent = CallerSetup.screeningRoleRequest(context)
        if (intent != null) roleLauncher.launch(intent) else toast(context, "This phone doesn't offer call screening to apps")
    }
    SetupItem("Notifications", "Required. The caller card appears as a notification.", canNotify) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(CallerSetup.notificationSettings(context))
        }
    }
    SetupItem(
        "Floating caller card",
        "Optional. \"Display over other apps\" shows the card on top of the call screen. Some phones don't allow it.",
        canOverlay,
    ) { runCatching { context.startActivity(CallerSetup.overlaySettings(context)) }.onFailure { toast(context, "Not available on this phone") } }
    SetupItem(
        "Calls from saved contacts",
        "Optional. Some Android versions only pass calls from numbers saved in your phone contacts to apps with Contacts access.",
        hasContacts,
    ) { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var unknown by remember { mutableStateOf(settings.showUnknown) }
    var overlay by remember { mutableStateOf(settings.useOverlay) }
    Toggle("Show caller screen", enabled) { enabled = it; settings.enabled = it }
    Toggle("Offer \"Create client\" for unknown numbers", unknown) { unknown = it; settings.showUnknown = it }
    Toggle("Use floating card when allowed", overlay) { overlay = it; settings.useOverlay = it }

    val cached = remember(refresh) { CallerDirectoryStore.get(context).size }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$cached numbers saved for offline caller ID", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { CallerDirectorySyncWorker.syncNow(context); toast(context, "Refreshing…") }) { Text("Refresh") }
    }

    // Exercise the same pipeline an incoming call uses, without a real call.
    var testNumber by rememberText()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Try it", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                testNumber, { testNumber = it }, singleLine = true, label = { Text("Caller number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(enabled = testNumber.isNotBlank(), onClick = {
                CallerIdController.onIncomingCall(context, testNumber.trim())
                toast(context, "Simulated call from $testNumber")
            }) { Text("Simulate incoming call") }
            Text(
                "Shows the notification/card exactly as for a real call. On an emulator you can also place a real test call " +
                    "with: adb emu gsm call 9820012345",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SetupItem(title: String, description: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (ok) "✓ " else "✗ ") + title, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) OutlinedButton(onClick = onFix) { Text("Allow") }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
