@file:OptIn(ExperimentalLayoutApi::class)

package com.brokerbuddy.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.brokerbuddy.core.form.FormField
import com.brokerbuddy.core.form.RequirementForm
import com.brokerbuddy.core.form.applyDraft
import com.brokerbuddy.core.model.ApplyVoiceNoteRequest
import com.brokerbuddy.core.model.Inquiry
import com.brokerbuddy.core.model.TextVoiceNoteRequest
import com.brokerbuddy.core.model.TranscriptRequest
import com.brokerbuddy.core.model.VoiceLanguage
import com.brokerbuddy.core.model.VoiceNoteResponse
import com.brokerbuddy.core.model.VoiceNoteStatus
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.Load
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.rememberText
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.inquiries.RequirementEditor
import com.brokerbuddy.ui.inquiries.RequirementFormSaver
import com.brokerbuddy.voice.Recording
import com.brokerbuddy.voice.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.brand
import com.brokerbuddy.voice.AudioPreview
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Record (or type) a note about [clientId]'s requirement, review what was extracted,
 * and save it to a new or existing inquiry. [inquiryId] pre-selects the inquiry the
 * note is about; [noteId] resumes a note recorded earlier.
 */
@Composable
fun VoiceNoteScreen(
    clientId: String,
    inquiryId: String?,
    noteId: String?,
    onBack: () -> Unit,
    onSaved: (inquiryId: String) -> Unit,
) {
    val container = appContainer()
    val api = container.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val client = rememberLoad(clientId) { api.call { client(clientId).client } }
    var currentNoteId by rememberSaveable { mutableStateOf(noteId) }
    var response by remember { mutableStateOf<VoiceNoteResponse?>(null) }
    var language by rememberSaveable { mutableStateOf(VoiceLanguage.AUTO) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val levels = remember { mutableStateListOf<Float>() }
    var pendingUpload by remember { mutableStateOf<Recording?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberText()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var micDenied by rememberSaveable { mutableStateOf(false) }

    // Resume an existing note (also after rotation / process death).
    LaunchedEffect(currentNoteId) {
        val id = currentNoteId ?: return@LaunchedEffect
        if (response?.voiceNote?.id == id) return@LaunchedEffect
        busy = "Loading note…"
        api.call { voiceNote(id) }.onSuccess { response = it }.onFailure { error = it.message }
        busy = null
    }

    fun handle(result: Result<VoiceNoteResponse>) {
        result.onSuccess { response = it; currentNoteId = it.voiceNote.id; error = null }
            .onFailure { error = it.message }
    }

    fun upload(rec: Recording) {
        busy = "Transcribing and reading requirements…"
        scope.launch {
            val text = "text/plain".toMediaType()
            val result = api.call {
                uploadVoiceNote(
                    clientId = clientId.toRequestBody(text),
                    inquiryId = inquiryId?.toRequestBody(text),
                    language = language.name.toRequestBody(text),
                    durationMs = rec.durationMs.toString().toRequestBody(text),
                    audio = MultipartBody.Part.createFormData("audio", rec.file.name, rec.file.asRequestBody(rec.mimeType.toMediaType())),
                )
            }
            if (result.isSuccess) {
                rec.file.delete()
                pendingUpload = null
            } else {
                pendingUpload = rec // keep the recording so the agent can retry when back online
            }
            handle(result)
            busy = null
        }
    }

    fun stopRecording() {
        recording = false
        val rec = recorder.stop()
        if (rec == null) error = "Nothing was recorded. Hold the phone closer and try again." else upload(rec)
    }

    fun startRecording() {
        error = null
        try {
            recorder.start { stopRecording() }
            recording = true
        } catch (e: Exception) {
            error = "Couldn't start the microphone (${e.message}). You can type the note instead."
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else micDenied = true
    }

    LaunchedEffect(recording) {
        levels.clear()
        while (recording) {
            elapsed = recorder.elapsedMs
            level = recorder.level()
            levels.add(level)
            if (levels.size > 24) levels.removeAt(0)
            delay(100)
        }
    }

    val clientName = (client.state as? Load.Ready)?.value?.name ?: "client"
    Scaffold(topBar = { BackTopBar("Voice note · $clientName", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val r = response
            when {
                busy != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(busy!!)
                }
                recording -> RecordingPanel(elapsed, levels, onStop = ::stopRecording, onCancel = { recording = false; recorder.cancel() })
                r != null -> ReviewPanel(r, onResult = ::handle, onSaved = onSaved, onBack = onBack, setBusy = { busy = it })
                typing -> {
                    Text("Type or dictate the note (the keyboard mic works in Hindi and Marathi too).")
                    OutlinedTextField(typed, { typed = it }, minLines = 5, modifier = Modifier.fillMaxWidth(), label = { Text("Note") })
                    LanguagePicker(language) { language = it }
                    Button(
                        enabled = typed.isNotBlank(),
                        onClick = {
                            busy = "Reading requirements…"
                            scope.launch {
                                handle(api.call { createTextVoiceNote(TextVoiceNoteRequest(clientId, typed.trim(), language, inquiryId)) })
                                busy = null
                            }
                        },
                    ) { Text("Read requirements") }
                    TextButton(onClick = { typing = false }) { Text("Record instead") }
                }
                else -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spacer(Modifier.height(12.dp))
                        VoiceOrb(levels = emptyList(), recording = false, onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted) startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        })
                        Text("Tap to record", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.brand.navy)
                        Text(
                            "Speak naturally in Hindi, Hinglish, Marathi or English. Up to 3 minutes.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.brand.muted, textAlign = TextAlign.Center,
                        )
                    }
                    LanguagePicker(language) { language = it }
                    if (micDenied) {
                        Text("Microphone permission is off, so you can type the note instead.", color = MaterialTheme.colorScheme.error)
                    }
                    pendingUpload?.let { rec ->
                        OutlinedButton(onClick = { upload(rec) }) { Text("Retry sending the last recording") }
                    }
                    TextButton(onClick = { typing = true }) { Text("Type the note instead") }
                }
            }
        }
    }
}

@Composable
private fun LanguagePicker(selected: VoiceLanguage, onSelect: (VoiceLanguage) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceLanguage.entries.forEach { l ->
            FilterChip(selected = l == selected, onClick = { onSelect(l) }, label = { Text(l.label) })
        }
    }
}

@Composable
private fun RecordingPanel(elapsedMs: Long, levels: List<Float>, onStop: () -> Unit, onCancel: () -> Unit) {
    val seconds = elapsedMs / 1000
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        VoiceOrb(levels = levels, recording = true, onClick = onStop)
        Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.brand.navy)
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Brand.Red).clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(4.dp)))
        }
        Text("Recording…", style = MaterialTheme.typography.titleSmall, color = Brand.Red)
        Text(
            "Speak naturally in Hindi, Hinglish, Marathi or English",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted, textAlign = TextAlign.Center,
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * The big microphone: soft rings that breathe while idle and follow the voice level
 * while recording, with a live waveform of the last couple of seconds inside.
 */
@Composable
private fun VoiceOrb(levels: List<Float>, recording: Boolean, onClick: () -> Unit) {
    val b = MaterialTheme.brand
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "pulse",
    )
    val live = levels.lastOrNull() ?: 0f
    val grow = if (recording) live else pulse * 0.25f
    Box(Modifier.size(220.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(b.link.copy(alpha = 0.08f), radius = r * (0.82f + 0.18f * grow))
            drawCircle(b.link.copy(alpha = 0.14f), radius = r * (0.68f + 0.14f * grow))
            drawCircle(b.link.copy(alpha = 0.9f), radius = r * 0.58f, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.White, radius = r * 0.56f)
            if (recording && levels.isNotEmpty()) {
                // Waveform bars, newest on the right.
                val bars = levels.takeLast(15)
                val barW = 5.dp.toPx()
                val gap = 4.dp.toPx()
                val total = bars.size * barW + (bars.size - 1) * gap
                var x = center.x - total / 2
                bars.forEach { l ->
                    val h = (0.12f + 0.88f * l.coerceIn(0f, 1f)) * r * 0.7f
                    drawRoundRect(
                        b.link,
                        topLeft = Offset(x, center.y - h / 2),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2, barW / 2),
                    )
                    x += barW + gap
                }
            }
        }
        if (!recording) Icon(Icons.Filled.Mic, contentDescription = "Start recording", tint = b.link, modifier = Modifier.size(56.dp))
    }
}

private fun inquiryLabel(i: Inquiry) = "${i.transactionType.label} · ${i.category.label}" +
    if (i.status.name != "ACTIVE") " (${i.status.label})" else ""

@Composable
private fun ReviewPanel(
    r: VoiceNoteResponse,
    onResult: (Result<VoiceNoteResponse>) -> Unit,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    setBusy: (String?) -> Unit,
) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val note = r.voiceNote
    var editingTranscript by rememberSaveable(note.id) { mutableStateOf(note.status == VoiceNoteStatus.NEEDS_TRANSCRIPT) }
    var transcriptText by rememberSaveable(note.id, note.transcript) { mutableStateOf(note.transcript ?: "") }

    fun submitTranscript() {
        setBusy("Reading requirements…")
        scope.launch {
            onResult(api.call { updateTranscript(note.id, TranscriptRequest(transcriptText.trim())) })
            editingTranscript = false
            setBusy(null)
        }
    }

    SectionTitle("What was said")
    if (note.status == VoiceNoteStatus.NEEDS_TRANSCRIPT) {
        note.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (editingTranscript) {
        OutlinedTextField(transcriptText, { transcriptText = it }, minLines = 4, modifier = Modifier.fillMaxWidth(), label = { Text("Transcript") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = transcriptText.isNotBlank(), onClick = ::submitTranscript) { Text("Read requirements") }
            if (note.status == VoiceNoteStatus.NEEDS_TRANSCRIPT && note.hasAudio) {
                OutlinedButton(onClick = {
                    setBusy("Transcribing…")
                    scope.launch { onResult(api.call { retryTranscription(note.id) }); setBusy(null) }
                }) { Text("Retry transcription") }
            }
        }
    } else {
        BrandCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                if (note.hasAudio) {
                    VoiceNotePlayButton(note.id)
                    Spacer(Modifier.width(10.dp))
                }
                Text(note.transcript ?: "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Column {
                if (note.originalTranscript != null && note.originalTranscript != note.transcript) {
                    Text("Speech-to-text heard: “${note.originalTranscript}”", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editingTranscript = true }) { Text("Correct transcript") }
            }
        }
    }

    val extraction = note.extraction
    if (note.status != VoiceNoteStatus.READY || extraction == null) return

    val warnings = r.targetWarnings + extraction.warnings
    if (warnings.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Please check", style = MaterialTheme.typography.titleSmall)
                warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    // Which inquiry this note belongs to.
    val initialTarget = note.inquiryId ?: r.suggestedInquiryId
    var target by rememberSaveable(note.id) { mutableStateOf(initialTarget) }
    SectionTitle("Save to")
    val options: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "New requirement") +
        r.inquiries.map { i -> i.id to (inquiryLabel(i) + if (i.id == r.suggestedInquiryId && note.inquiryId == null) " — suggested" else "") }
    options.forEach { (id, label) ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = target == id, onClick = { target = id }, role = Role.RadioButton),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = target == id, onClick = null)
            Text(label, Modifier.padding(start = 8.dp))
        }
    }

    // Base = the chosen inquiry (or blank); overlay only what the note stated.
    val base = r.inquiries.firstOrNull { it.id == target }?.let(RequirementForm::fromInquiry) ?: RequirementForm()
    val merge = remember(note.id, note.transcript, target) { base.applyDraft(extraction.draft) }
    var form by rememberSaveable(note.id, note.transcript, target, stateSaver = RequirementFormSaver) { mutableStateOf(merge.form) }
    var attempted by rememberSaveable(note.id) { mutableStateOf(false) }
    val validation = form.validate()
    val errors = if (attempted) validation.errors else validation.errors.filterKeys { it != FormField.TRANSACTION && it != FormField.CATEGORY }

    SectionTitle("Review requirement")
    Text(
        if (merge.evidence.isEmpty()) "Nothing requirement-related was recognised. Fill in what the client said, or correct the transcript."
        else "Filled from the note: ${merge.evidence.keys.joinToString { it.label }}. Everything else is unchanged — nothing is guessed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RequirementEditor(form, { form = it }, errors, showStatus = target != null, evidence = merge.evidence, previous = merge.previous)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            attempted = true
            val body = validation.forVoice() ?: return@Button
            setBusy("Saving…")
            scope.launch {
                api.call { applyVoiceNote(note.id, ApplyVoiceNoteRequest(target, body)) }
                    .onSuccess { onSaved(it.inquiry.id) }
                    .onFailure { toast(context, it.message ?: "Could not save") }
                setBusy(null)
            }
        }) { Text(if (target == null) "Save to Client" else "Update requirement") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                api.call { discardVoiceNote(note.id) }.onSuccess { onBack() }.onFailure { toast(context, it.message ?: "Failed") }
            }
        }) { Text("Discard") }
    }
}

/** Plays the recorded voice note (fetched through the signed-in API). */
@Composable
private fun VoiceNotePlayButton(noteId: String) {
    val context = LocalContext.current
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    val player = remember { AudioPreview(context) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.brand.info.container).clickable {
            if (playing) {
                player.stop(); playing = false
            } else {
                scope.launch {
                    val file = File(context.cacheDir, "voice-note-$noteId")
                    val ok = file.exists() || api.call { voiceNoteAudio(noteId) }.fold(
                        onSuccess = { body -> runCatching { withContext(Dispatchers.IO) { body.use { rb -> file.outputStream().use { rb.byteStream().copyTo(it) } } } }.isSuccess },
                        onFailure = { false },
                    )
                    if (!ok) {
                        toast(context, "Couldn't load the recording")
                        return@launch
                    }
                    runCatching { player.play(file) { playing = false }; playing = true }
                        .onFailure { toast(context, "Can't play this recording") }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (playing) "Stop" else "Play", tint = MaterialTheme.brand.link)
    }
}
