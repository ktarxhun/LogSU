package com.ktarxhun.logsu.util

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryExporterWavTest {

    private fun createTestWav(channels: Int, sampleRate: Int, payloadBytes: Int): File {
        val file = File.createTempFile("logsu-wav-test", ".wav")
        val byteRate = sampleRate * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + payloadBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(payloadBytes)
        }.array()

        FileOutputStream(file).use { out ->
            out.write(header)
            if (payloadBytes > 0) {
                out.write(ByteArray(payloadBytes))
            }
        }
        return file
    }

    @Test
    fun readsMono16kWavHeaderAccurately() {
        val wav = createTestWav(channels = 1, sampleRate = 16000, payloadBytes = 32000)
        try {
            val info = GalleryExporter.readWavInfo(wav)
            assertNotNull(info)
            assertEquals(1, info!!.channels)
            assertEquals(16000, info.sampleRate)
            assertEquals(16, info.bitsPerSample)
            assertEquals(44L, info.dataOffset)
            assertEquals(32000L, info.dataSize)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun readsStereo16kWavHeaderAccurately() {
        val wav = createTestWav(channels = 2, sampleRate = 16000, payloadBytes = 64000)
        try {
            val info = GalleryExporter.readWavInfo(wav)
            assertNotNull(info)
            assertEquals(2, info!!.channels)
            assertEquals(16000, info.sampleRate)
            assertEquals(64000L, info.dataSize)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun returnsNullForEmptyOrHeaderOnlyWav() {
        val headerOnly = createTestWav(channels = 1, sampleRate = 16000, payloadBytes = 0)
        try {
            assertNull(GalleryExporter.readWavInfo(headerOnly))
            assertNull(GalleryExporter.readWavInfo(File(headerOnly.parent, "nonexistent.wav")))
        } finally {
            headerOnly.delete()
        }
    }
}
