package com.brokerbuddy.ui.callassistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.brokerbuddy.core.model.Greeting
import com.brokerbuddy.core.model.GreetingScriptRequest
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.design.BrandCard
import com.brokerbuddy.ui.design.Pill
import com.brokerbuddy.ui.theme.brand
import com.brokerbuddy.voice.AudioPreview
import com.brokerbuddy.voice.GreetingRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** A recording made or picked on the phone, not saved yet (previewed first). */
private sealed interface Draft {
    val durationMs: Long?

    data class Recorded(val file: File, override val durationMs: Long) : Draft
    data class Picked(val uri: Uri, val name: String, val mimeType: String, override val durationMs: Long?) : Draft
}

private const val MAX_UPLOAD_BYTES = 6 * 1024 * 1024

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/**
 * One language's greeting: the editable script, and the broker's recording of it.
 * Recordings are previewed before saving; saving replaces the previous one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GreetingCard(greeting: Greeting, canEdit: Boolean, maxSeconds: Int, isDefaultLanguage: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val b = MaterialTheme.brand
    val lang = greeting.language.name

    val recorder = remember { GreetingRecorder(context, maxSeconds * 1000L) }
    val preview = remember { AudioPreview(context) }
    DisposableEffect(Unit) { onDispose { recorder.cancel(); preview.release() } }

    var script by remember(greeting.script) { mutableStateOf(greeting.script) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var recording by remember { mutableStateOf(false) }
    var autoStopped by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf<String?>(null) } // "draft" | "saved"
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun stopRecording() {
        val r = recorder.stop()
        recording = false
        level = 0f
        if (r == null) toast(context, "Recording too short — try again") else draft = Draft.Recorded(r.file, r.durationMs)
    }

    // Timer/meter while recording; the recorder stops itself at the limit.
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs
            level = recorder.level
            if (autoStopped) {
                autoStopped = false
                stopRecording()
                toast(context, "Stopped at $maxSeconds seconds")
            }
            delay(100)
        }
    }

    fun startRecording() {
        preview.stop(); playing = null
        (draft as? Draft.Recorded)?.file?.delete()
        draft = null
        try {
            recorder.start(onAutoStop = { autoStopped = true })
            recording = true
        } catch (e: Exception) {
            toast(context, e.message ?: "Couldn't start recording")
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast(context, "Microphone permission is needed to record — you can upload a file instead")
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name = "greeting"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = c.getLong(1)
            }
        }
        if (size > MAX_UPLOAD_BYTES) {
            toast(context, "That file is too large (max 6 MB)")
            return@rememberLauncherForActivityResult
        }
        val ms = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull()
        if (ms != null && ms > maxSeconds * 1000L) {
            toast(context, "Keep the greeting under $maxSeconds seconds")
            return@rememberLauncherForActivityResult
        }
        preview.stop(); playing = null
        draft = Draft.Picked(uri, name, mime, ms)
    }

    fun play(which: String, start: () -> Unit) {
        if (playing == which) {
            preview.stop(); playing = null
            return
        }
        try {
            start()
            playing = which
        } catch (e: Exception) {
            playing = null
            toast(context, "Can't play this audio: ${e.message}")
        }
    }

    fun playSaved() {
        scope.launch {
            busy = true
            val file = File(context.cacheDir, "greeting-saved-$lang")
            val saved = container.api.call { greetingAudio(lang) }.fold(
                onSuccess = { body ->
                    runCatching { withContext(Dispatchers.IO) { body.use { rb -> file.outputStream().use { rb.byteStream().copyTo(it) } } } }
                },
                onFailure = { Result.failure(it) },
            )
            saved
                .onSuccess { play("saved") { preview.play(file) { playing = null } } }
                .onFailure { toast(context, it.message ?: "Couldn't load the recording") }
            busy = false
        }
    }

    fun saveDraft() {
        val d = draft ?: return
        scope.launch {
            busy = true
            val part = withContext(Dispatchers.IO) {
                when (d) {
                    is Draft.Recorded -> MultipartBody.Part.createFormData("audio", d.file.name, d.file.asRequestBody("audio/wav".toMediaType()))
                    is Draft.Picked -> {
                        val bytes = context.contentResolver.openInputStream(d.uri)?.use { it.readBytes() } ?: ByteArray(0)
                        MultipartBody.Part.createFormData("audio", d.name, bytes.toRequestBody(d.mimeType.toMediaType()))
                    }
                }
            }
            val duration = d.durationMs?.toString()?.toRequestBody("text/plain".toMediaType())
            container.api.call { uploadGreetingAudio(lang, duration, part) }
                .onSuccess {
                    toast(context, "Greeting saved")
                    (d as? Draft.Recorded)?.file?.delete()
                    draft = null
                    onChanged()
                }
                .onFailure { toast(context, it.message ?: "Upload failed") }
            busy = false
        }
    }

    BrandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(greeting.language.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (isDefaultLanguage) Pill("Default", b.info)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = script,
            onValueChange = { script = it.take(1000) },
            label = { Text("Greeting script") },
            supportingText = { Text("What you'll say in your recording (also read out by the AI voice if there's no recording).") },
            enabled = canEdit && !busy,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        if (canEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (script.trim() != greeting.script) {
                    TextButton(enabled = !busy && script.trim().length >= 10, onClick = {
                        scope.launch {
                            busy = true
                            container.api.call { updateGreetingScript(lang, GreetingScriptRequest(script.trim())) }
                                .onSuccess { r -> toast(context, r.warnings.firstOrNull() ?: "Script saved"); onChanged() }
                                .onFailure { toast(context, it.message ?: "Couldn't save the script") }
                            busy = false
                        }
                    }) { Text("Save script") }
                }
                if (!greeting.isDefaultScript || greeting.hasAudio) {
                    TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("Reset to default") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // ----- the recording -----
        when {
            recording -> {
                Text("Recording… ${clock(elapsed)} / ${clock(maxSeconds * 1000L)}", color = b.danger.content)
                LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Button(onClick = { stopRecording() }) {
                    Icon(Icons.Filled.Stop, null); Text("  Stop")
                }
            }
            draft != null -> {
                val d = draft!!
                Text(
                    "New ${if (d is Draft.Recorded) "recording" else "file"}${d.durationMs?.let { " · ${clock(it)}" } ?: ""} — listen before saving",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        play("draft") {
                            when (d) {
                                is Draft.Recorded -> preview.play(d.file) { playing = null }
                                is Draft.Picked -> preview.play(d.uri) { playing = null }
                            }
                        }
                    }) {
                        Icon(if (playing == "draft") Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                        Text(if (playing == "draft") " Stop" else " Preview")
                    }
                    Button(enabled = !busy, onClick = { preview.stop(); playing = null; saveDraft() }) {
                        Text(if (greeting.hasAudio) "Replace greeting" else "Save greeting")
                    }
                    TextButton(enabled = !busy, onClick = {
                        preview.stop(); playing = null
                        (d as? Draft.Recorded)?.file?.delete()
                        draft = null
                    }) { Text("Discard") }
                }
            }
            else -> {
                Text(
                    if (greeting.hasAudio) "Your recording${greeting.durationMs?.let { " · ${clock(it)}" } ?: ""}"
                    else "No recording — the AI voice reads the script instead",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (greeting.hasAudio) MaterialTheme.colorScheme.onSurface else b.muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (greeting.hasAudio) {
                        OutlinedButton(enabled = !busy, onClick = { if (playing == "saved") { preview.stop(); playing = null } else playSaved() }) {
                            Icon(if (playing == "saved") Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                            Text(if (playing == "saved") " Stop" else " Play")
                        }
                    }
                    if (canEdit) {
                        OutlinedButton(enabled = !busy, onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }) {
                            Icon(Icons.Filled.Mic, null)
                            Text(if (greeting.hasAudio) " Re-record" else " Record")
                        }
                        OutlinedButton(enabled = !busy, onClick = { picker.launch("audio/*") }) {
                            Icon(Icons.Filled.UploadFile, null); Text(" Upload")
                        }
                        if (greeting.hasAudio) {
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    container.api.call { deleteGreetingAudio(lang) }
                                        .onSuccess { toast(context, "Recording deleted"); onChanged() }
                                        .onFailure { toast(context, it.message ?: "Delete failed") }
                                    busy = false
                                }
                            }) {
                                Icon(Icons.Filled.Delete, null); Text(" Delete")
                            }
                        }
                    }
                }
                if (canEdit) {
                    Text("Upload MP3 or WAV, up to $maxSeconds seconds.", style = MaterialTheme.typography.bodySmall, color = b.muted)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Reset ${greeting.language.label} greeting?") },
            text = { Text("The script goes back to the default and any recording is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.api.call { resetGreeting(lang) }
                            .onSuccess { onChanged() }
                            .onFailure { toast(context, it.message ?: "Reset failed") }
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
