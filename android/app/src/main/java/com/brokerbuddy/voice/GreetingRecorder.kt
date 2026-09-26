package com.brokerbuddy.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.brokerbuddy.core.audio.Wav
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Records the AI receptionist's greeting as 16 kHz mono 16-bit WAV — a format phone
 * services play reliably (unlike the AAC used for voice notes). Stops by itself at
 * [maxMs]. The caller must hold the RECORD_AUDIO permission.
 */
class GreetingRecorder(private val context: Context, private val maxMs: Long = 60_000) {
    @Volatile private var running = false
    @Volatile var level = 0f
        private set
    private var worker: Thread? = null
    private var file: File? = null
    private var dataBytes = 0L
    private var startedAt = 0L

    val isRecording get() = running
    val elapsedMs get() = if (running) SystemClock.elapsedRealtime() - startedAt else 0L

    @SuppressLint("MissingPermission") // checked by the screen before starting
    fun start(onAutoStop: () -> Unit) {
        check(!running) { "Already recording" }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, RATE / 5 * 2) // at least 200 ms
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone is not available")
        }
        val out = File(context.cacheDir, "greeting-${System.currentTimeMillis()}.wav")
        file = out
        dataBytes = 0
        running = true
        startedAt = SystemClock.elapsedRealtime()
        rec.startRecording()
        worker = thread(name = "greeting-recorder") {
            val buf = ByteArray(bufSize)
            RandomAccessFile(out, "rw").use { f ->
                f.write(ByteArray(Wav.HEADER_BYTES)) // placeholder, filled in at the end
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        f.write(buf, 0, n)
                        dataBytes += n
                        level = Wav.peak(buf, n)
                    }
                    if (Wav.durationMs(dataBytes, RATE) >= maxMs) {
                        running = false
                        onAutoStop()
                    }
                }
                rec.stop()
                rec.release()
                f.seek(0)
                f.write(Wav.header(dataBytes, RATE))
            }
        }
    }

    /** Stops and returns the recording (null if it was too short to keep). */
    fun stop(): Recording? {
        running = false
        worker?.join(2000)
        worker = null
        val out = file ?: return null
        file = null
        val ms = Wav.durationMs(dataBytes, RATE)
        if (ms < 1000) {
            out.delete()
            return null
        }
        return Recording(out, ms)
    }

    fun cancel() {
        stop()?.file?.delete()
    }

    companion object {
        const val RATE = 16_000
    }
}
