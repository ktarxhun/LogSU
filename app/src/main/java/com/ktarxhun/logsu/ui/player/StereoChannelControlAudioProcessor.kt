package com.ktarxhun.logsu.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Stereo PCM16 akışında Sol (Mikrofon) ve Sağ (Arayan / Dahili Ses) kanallarını
 * bağımsız olarak açıp kapatabilen ve isteğe bağlı %150 ses artırma uygulayan AudioProcessor.
 * Yalnızca tek bir kanal seçiliyse o kanalı hem sol hem sağ çıkışa vererek tam ses seviyesi sağlar.
 */
@OptIn(UnstableApi::class)
class StereoChannelControlAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var leftEnabled: Boolean = true

    @Volatile
    var rightEnabled: Boolean = true

    @Volatile
    var boostEnabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2)
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val channels = inputAudioFormat.channelCount
        val gain = if (boostEnabled) 1.5f else 1.0f
        val lOn = leftEnabled
        val rOn = rightEnabled

        val outBuffer = replaceOutputBuffer(remaining)

        if (channels == 1) {
            while (inputBuffer.remaining() >= 2) {
                val raw = inputBuffer.short
                if (!lOn) {
                    outBuffer.putShort(0.toShort())
                } else {
                    val sample = raw.toFloat() * gain
                    outBuffer.putShort(sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                val rawL = inputBuffer.short.toFloat()
                val rawR = inputBuffer.short.toFloat()

                val outL: Float
                val outR: Float
                when {
                    lOn && rOn -> {
                        outL = rawL * gain
                        outR = rawR * gain
                    }
                    lOn && !rOn -> {
                        val s = rawL * gain
                        outL = s
                        outR = s
                    }
                    !lOn && rOn -> {
                        val s = rawR * gain
                        outL = s
                        outR = s
                    }
                    else -> {
                        outL = 0f
                        outR = 0f
                    }
                }

                outBuffer.putShort(outL.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                outBuffer.putShort(outR.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }

        outBuffer.flip()
    }
}
