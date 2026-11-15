package com.ktarxhun.logsu.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StereoChannelControlAudioProcessorTest {

    private fun processShorts(
        processor: StereoChannelControlAudioProcessor,
        channels: Int,
        samples: ShortArray
    ): ShortArray {
        val format = AudioProcessor.AudioFormat(16000, channels, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) {
            input.putShort(s)
        }
        input.flip()

        processor.queueInput(input)
        val out = processor.output
        val result = ShortArray(out.remaining() / 2)
        for (i in result.indices) {
            result[i] = out.short
        }
        return result
    }

    @Test
    fun rejectsUnsupportedEncodingsOrChannelCounts() {
        val processor = StereoChannelControlAudioProcessor()
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(16000, 2, C.ENCODING_PCM_FLOAT))
        )
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(16000, 4, C.ENCODING_PCM_16BIT))
        )
        assertFalse(processor.isActive)
    }

    @Test
    fun leftOnlyDuplicatesLeftChannelToBothOutputs() {
        val processor = StereoChannelControlAudioProcessor().apply {
            leftEnabled = true
            rightEnabled = false
            boostEnabled = false
        }
        val out = processShorts(processor, 2, shortArrayOf(1200, -3400, 500, 9999))
        assertArrayEquals(shortArrayOf(1200, 1200, 500, 500), out)
    }

    @Test
    fun rightOnlyDuplicatesRightChannelToBothOutputs() {
        val processor = StereoChannelControlAudioProcessor().apply {
            leftEnabled = false
            rightEnabled = true
            boostEnabled = false
        }
        val out = processShorts(processor, 2, shortArrayOf(1200, -3400, 500, 9999))
        assertArrayEquals(shortArrayOf(-3400, -3400, 9999, 9999), out)
    }

    @Test
    fun bothMutedOutputsSilence() {
        val processor = StereoChannelControlAudioProcessor().apply {
            leftEnabled = false
            rightEnabled = false
            boostEnabled = true
        }
        val out = processShorts(processor, 2, shortArrayOf(1200, -3400, -20000, 25000))
        assertArrayEquals(shortArrayOf(0, 0, 0, 0), out)
    }

    @Test
    fun monoBoostApplies150PercentAndClamps() {
        val processor = StereoChannelControlAudioProcessor().apply {
            leftEnabled = true
            rightEnabled = false
            boostEnabled = true
        }
        val out = processShorts(
            processor,
            1,
            shortArrayOf(1000, -2000, 30000, -30000)
        )
        assertArrayEquals(
            shortArrayOf(1500, -3000, Short.MAX_VALUE, Short.MIN_VALUE),
            out
        )
    }

    @Test
    fun monoMutedWhenLeftDisabled() {
        val processor = StereoChannelControlAudioProcessor().apply {
            leftEnabled = false
            rightEnabled = false
            boostEnabled = false
        }
        val out = processShorts(
            processor,
            1,
            shortArrayOf(1000, -2000, 30000, -30000)
        )
        assertArrayEquals(
            shortArrayOf(0, 0, 0, 0),
            out
        )
    }

    @Test
    fun stereoBothEnabledAppliesBoostAndClamps() {
        val processor = StereoChannelControlAudioProcessor().apply {
            leftEnabled = true
            rightEnabled = true
            boostEnabled = true
        }
        val out = processShorts(processor, 2, shortArrayOf(2000, -30000, 25000, -4000))
        assertArrayEquals(
            shortArrayOf(3000, Short.MIN_VALUE, Short.MAX_VALUE, -6000),
            out
        )
    }
}
