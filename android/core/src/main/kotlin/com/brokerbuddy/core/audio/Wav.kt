package com.brokerbuddy.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV helpers. Greetings are recorded as WAV because phone services play it everywhere. */
object Wav {
    const val HEADER_BYTES = 44

    fun header(dataBytes: Long, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort((channels * bitsPerSample / 8).toShort()); putShort(bitsPerSample.toShort())
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }

    fun durationMs(dataBytes: Long, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): Long =
        dataBytes * 1000 / (sampleRate.toLong() * channels * bitsPerSample / 8)

    /** Peak level 0..1 of little-endian 16-bit samples, for a recording meter. */
    fun peak(buffer: ByteArray, length: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < length) {
            val s = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
            max = maxOf(max, kotlin.math.abs(s.toShort().toInt()))
            i += 2
        }
        return (max / 32767f).coerceIn(0f, 1f)
    }
}
