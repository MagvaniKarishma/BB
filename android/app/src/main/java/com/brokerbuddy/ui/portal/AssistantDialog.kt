package com.brokerbuddy.ui.portal

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.AssistantCommandRequest
import com.brokerbuddy.core.model.AssistantCommandResult
import com.brokerbuddy.core.model.AssistantNavigate
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Speech languages offered; Hinglish works with English (India). */
private enum class SpeechLang(val label: String, val tag: String) {
    ENGLISH("English", "en-IN"),
    HINDI("हिंदी", "hi-IN"),
    MARATHI("मराठी", "mr-IN"),
}

/**
 * "Ask BrokerBuddy": speak (the phone's own speech recognition) or type a command such as
 * "Show today's 99acres leads" or "Rahul ko contacted mark karo". The server works out
 * what to do; this opens the right screen or shows what changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantDialog(onDismiss: () -> Unit, onNavigate: (AssistantNavigate) -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberText()
    var lang by rememberSaveable { mutableStateOf(SpeechLang.ENGLISH) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AssistantCommandResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(command: String) {
        if (command.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val tz = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60
            api.call { assistantCommand(AssistantCommandRequest(command.trim(), tz)) }
                .onSuccess { r ->
                    result = r
                    if (r.options.isEmpty() && r.navigate != null) {
                        if (r.done) toast(context, r.message)
                        onNavigate(r.navigate!!)
                    }
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) {
            text = heard
            run(heard)
        }
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command")
        runCatching { speech.launch(intent) }.onFailure { error = "Speech recognition isn't available on this phone — type the command instead." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask BrokerBuddy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpeechLang.entries.forEach { l -> FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l.label) }) }
                }
                OutlinedTextField(
                    text, { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Show today's 99acres leads") },
                    trailingIcon = { IconButton(onClick = ::listen, enabled = !busy) { Icon(Icons.Filled.Mic, "Speak") } },
                )
                Text(
                    "Examples: “Show Housing.com leads from yesterday” · “Show everyone interested in the Andheri property” · " +
                        "“Mark Rahul as contacted” · “Rahul ke saath kal follow up” · “आजचे 99acres लीड दाखवा”",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted,
                )
                if (busy) Text("Working…", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.let { r ->
                    Text(r.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.brand.navy)
                    r.options.forEach { o -> OutlinedButton(onClick = { onNavigate(o.navigate) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) } }
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank() && !busy, onClick = { run(text) }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
