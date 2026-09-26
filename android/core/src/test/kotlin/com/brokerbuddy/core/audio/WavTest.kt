package com.brokerbuddy.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class WavTest {
    @Test
    fun `writes a standard PCM header the server can read`() {
        val h = Wav.header(dataBytes = 32_000, sampleRate = 16_000)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals(36 + 32_000, b.getInt(4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals("data", String(h, 36, 4))
        assertEquals(32_000, b.getInt(40))
        assertEquals(1000, Wav.durationMs(32_000, 16_000))
    }

    @Test
    fun `measures the peak of 16-bit samples`() {
        val samples = byteArrayOf(0, 0, 0xff.toByte(), 0x3f, 0x01, 0xc0.toByte()) // 0, 16383, -16383
        assertEquals(0.5f, Wav.peak(samples, samples.size), 0.001f)
    }
}
