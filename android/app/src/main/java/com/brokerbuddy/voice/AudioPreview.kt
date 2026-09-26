package com.brokerbuddy.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.File

/** Plays one clip at a time (greeting previews). Call [release] when the screen goes away. */
class AudioPreview(private val context: Context) {
    private var player: MediaPlayer? = null

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun play(file: File, onDone: () -> Unit) = play(Uri.fromFile(file), onDone)

    fun play(uri: Uri, onDone: () -> Unit) {
        stop()
        val p = MediaPlayer()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        try {
            p.setDataSource(context, uri)
            p.setOnCompletionListener { stop(); onDone() }
            p.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            p.release()
            throw e
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun release() = stop()
}
