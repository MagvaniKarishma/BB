package com.brokerbuddy.ui.inquiries

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.form.FormField
import com.brokerbuddy.core.form.RequirementForm
import com.brokerbuddy.core.form.applyDraft
import com.brokerbuddy.core.model.ExtractRequest
import com.brokerbuddy.core.model.VoiceLanguage
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.portal.SpeechLang
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.launch

/** Language the requirement is read in, for the chosen speech language (Hinglish comes through English). */
private fun SpeechLang.voiceLanguage() = when (this) {
    SpeechLang.ENGLISH -> VoiceLanguage.AUTO
    SpeechLang.HINDI -> VoiceLanguage.HINDI
    SpeechLang.MARATHI -> VoiceLanguage.MARATHI
}

/**
 * Big mic on the requirement form: the agent speaks, the phone turns it into words, the server
 * reads the requirement from them, and only the fields that were said are filled in. Everything
 * else is left as it was; every field stays editable by hand, and Undo puts the form back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceFillCard(
    form: RequirementForm,
    onFilled: (form: RequirementForm, evidence: Map<FormField, String>) -> Unit,
    onUndo: (RequirementForm) -> Unit,
) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    val b = MaterialTheme.brand
    var lang by rememberSaveable { mutableStateOf(SpeechLang.ENGLISH) }
    var busy by remember { mutableStateOf(false) }
    var heard by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var before by remember { mutableStateOf<RequirementForm?>(null) }

    fun read(text: String) {
        busy = true
        error = null
        heard = text
        scope.launch {
            api.call { extractRequirement(ExtractRequest(text, lang.voiceLanguage())) }
                .onSuccess { x ->
                    val merge = form.applyDraft(x.draft)
                    if (merge.evidence.isEmpty()) {
                        result = "Nothing to fill from that. Mention the type (e.g. 2 BHK), area, budget, rent or buy."
                    } else {
                        before = form
                        onFilled(merge.form, merge.evidence)
                        result = "Filled: " + merge.evidence.keys.joinToString(", ") { it.label }
                    }
                    warnings = x.warnings
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) read(text)
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the requirement")
        runCatching { speech.launch(intent) }
            .onFailure { error = "Speech recognition isn't available on this phone. Fill the form below by hand." }
    }

    BrandCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(if (busy) b.muted else Brand.Blue)
                    .clickable(enabled = !busy, onClick = ::listen)
                    .semantics { contentDescription = "Speak the requirement" },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                else Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Text(if (busy) "Reading what you said…" else "Tap and speak the requirement", style = MaterialTheme.typography.titleSmall, color = b.navy)
            Text(
                "e.g. “2 BHK rent pe chahiye Andheri West mein, budget 60 se 70 hazaar, semi furnished, ek parking”. " +
                    "Only what you say is filled; you can change anything below.",
                style = MaterialTheme.typography.bodySmall, color = b.muted, textAlign = TextAlign.Center,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpeechLang.entries.forEach { l -> FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l.label) }) }
            }
            heard?.let { Text("🎙 “$it”", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            result?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = b.success.content, textAlign = TextAlign.Center) }
            warnings.take(3).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = b.amber.content) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            before?.let { prev ->
                TextButton(onClick = { onUndo(prev); before = null; result = "Undone — the form is back as it was." }) { Text("Undo voice fill") }
            }
        }
    }
}
