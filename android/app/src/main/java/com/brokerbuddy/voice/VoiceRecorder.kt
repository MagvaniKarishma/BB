package com.brokerbuddy.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

data class Recording(val file: File, val durationMs: Long) {
    val mimeType: String get() = if (file.name.endsWith(".wav")) "audio/wav" else "audio/mp4"
}

/**
 * Records a mono AAC voice note (16 kHz, 32 kbps ≈ 240 KB/min) into the app cache.
 * Requires the RECORD_AUDIO permission to have been granted by the caller.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Current input level 0..1 for a simple meter. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** @param onMaxDuration called on the main thread when the length limit stops the recording. */
    fun start(maxDurationMs: Int = MAX_DURATION_MS, onMaxDuration: () -> Unit = {}) {
        check(recorder == null) { "Already recording" }
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(maxDurationMs)
            r.setOutputFile(out.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            out.delete()
            throw e
        }
        recorder = r
        file = out
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and returns the recording, or null if nothing usable was captured. */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val out = file
        val duration = elapsedMs
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // stop() throws when no audio frames were written (e.g. tapped stop instantly)
        } finally {
            r.release()
        }
        if (!ok || out == null || !out.exists() || out.length() == 0L) {
            out?.delete()
            return null
        }
        return Recording(out, duration)
    }

    fun cancel() {
        stop()?.file?.delete()
    }

    companion object {
        const val MAX_DURATION_MS = 3 * 60 * 1000
    }
}
