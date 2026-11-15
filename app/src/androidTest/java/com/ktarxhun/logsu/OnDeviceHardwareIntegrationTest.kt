package com.ktarxhun.logsu

import android.content.ContentUris
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ktarxhun.logsu.data.db.LogSuDatabase
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.CallType
import com.ktarxhun.logsu.data.repository.RecordRepository
import com.ktarxhun.logsu.stt.OfflineSpeechTranscriber
import com.ktarxhun.logsu.ui.player.StereoChannelControlAudioProcessor
import com.ktarxhun.logsu.util.GalleryExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class OnDeviceHardwareIntegrationTest {

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportMonoWavAsVideoToGallery_producesValidPlayableMp4AndCleansUp() = runBlocking {
        val context = appContext()
        val wavFile = File(context.cacheDir, "test_mono_${System.currentTimeMillis()}.wav")
        var exportedName: String? = null
        try {
            writeSyntheticWav(wavFile, sampleRate = 44100, channels = 1, durationSec = 2, amplitude = 12000)

            var doneOk = false
            GalleryExporter.exportAudioAsVideoToGallery(
                context = context,
                audioFile = wavFile,
                title = "Ortam Testi",
                subtitle = "Mono 44.1kHz"
            ) { ok, fileName ->
                doneOk = ok
                exportedName = fileName
            }

            assertTrue("Mono WAV video donusturmesi basarili olmali", doneOk)
            assertNotNull("MediaStore dosya adi donmeli", exportedName)

            val uri = findMediaStoreVideoByName(context, exportedName!!)
            assertNotNull("MediaStore kaydi bulunmali", uri)
            verifyPlayableVideo(context, uri!!, expectWidth = "768", expectHeight = "1280", expectAudio = true)
        } finally {
            exportedName?.let { deleteMediaStoreVideoByName(context, it) }
            wavFile.delete()
        }
    }

    @Test
    fun exportStereoWavAsVideoToGallery_producesValidPlayableMp4AndCleansUp() = runBlocking {
        val context = appContext()
        val wavFile = File(context.cacheDir, "test_stereo_${System.currentTimeMillis()}.wav")
        var exportedName: String? = null
        try {
            writeSyntheticWav(wavFile, sampleRate = 44100, channels = 2, durationSec = 2, amplitude = 12000)

            var doneOk = false
            GalleryExporter.exportAudioAsVideoToGallery(
                context = context,
                audioFile = wavFile,
                title = "Arama Testi",
                subtitle = "Stereo 44.1kHz"
            ) { ok, fileName ->
                doneOk = ok
                exportedName = fileName
            }

            assertTrue("Stereo WAV video donusturmesi basarili olmali", doneOk)
            assertNotNull(exportedName)

            val uri = findMediaStoreVideoByName(context, exportedName!!)
            assertNotNull("MediaStore kaydi bulunmali", uri)
            verifyPlayableVideo(context, uri!!, expectWidth = "768", expectHeight = "1280", expectAudio = true)
        } finally {
            exportedName?.let { deleteMediaStoreVideoByName(context, it) }
            wavFile.delete()
        }
    }

    @Test
    fun exportGuestVideoWithAudioToGallery_muxesHardwareMp4AndWavAndCleansUp() = runBlocking {
        val context = appContext()
        val ts = System.currentTimeMillis()
        val prefix = "LogSU_HWTest_$ts"
        val videoFile = File(context.cacheDir, "test_vid_$ts.mp4")
        val wavFile = File(context.cacheDir, "test_vid_$ts.wav")
        try {
            createMinimalHardwareMp4(videoFile, width = 768, height = 1280, frameCount = 24)
            writeSyntheticWav(wavFile, sampleRate = 44100, channels = 2, durationSec = 1, amplitude = 8000)

            val ok = GalleryExporter.exportVideoToGallery(
                context = context,
                videoFile = videoFile,
                audioFile = wavFile,
                customName = prefix
            )
            assertTrue("Emanet video + ses birlestirme basarili olmali", ok)

            val uri = findMediaStoreVideoByPrefix(context, prefix)
            assertNotNull("Birlestirilmis MediaStore videosu bulunmali", uri)
            verifyPlayableVideo(context, uri!!, expectWidth = "768", expectHeight = "1280", expectAudio = true)
        } finally {
            deleteMediaStoreVideoByPrefix(context, prefix)
            videoFile.delete()
            wavFile.delete()
        }
    }

    @Test
    fun offlineSpeechTranscriber_detectsMonoAndStereoSpeakersOnDevice() = runBlocking {
        val context = appContext()
        val monoWav = File(context.cacheDir, "stt_mono_${System.currentTimeMillis()}.wav")
        val stereoWav = File(context.cacheDir, "stt_stereo_${System.currentTimeMillis()}.wav")
        try {
            writeSyntheticWav(monoWav, sampleRate = 16000, channels = 1, durationSec = 1, amplitude = 10000)
            var monoTranscript = ""
            OfflineSpeechTranscriber.transcribeCall(monoWav) { monoTranscript = it }
            assertTrue("Mono kayitta [Mikrofon] etiketi olmali: $monoTranscript", monoTranscript.contains("[Mikrofon]"))

            writeSyntheticWav(stereoWav, sampleRate = 16000, channels = 2, durationSec = 1, amplitude = 10000)
            var stereoTranscript = ""
            OfflineSpeechTranscriber.transcribeCall(stereoWav) { stereoTranscript = it }
            assertTrue("Stereo kayitta [Sen] etiketi olmali: $stereoTranscript", stereoTranscript.contains("[Sen]"))
            assertTrue("Stereo kayitta [Karsi Taraf] etiketi olmali: $stereoTranscript", stereoTranscript.contains("[Karşı Taraf]"))
        } finally {
            monoWav.delete()
            stereoWav.delete()
        }
    }

    @Test
    fun roomRepository_preservesAmbientAndLockedWhilePurgingOldCallRecords() = runBlocking {
        val context = appContext()
        val db = LogSuDatabase.getDatabase(context)
        val repo = RecordRepository(db.callRecordDao(), db.guestSessionDao(), db.guestEventDao())

        val now = System.currentTimeMillis()
        val oldTime = now - 30L * 24 * 60 * 60 * 1000L
        val oldAmbientFile = File(context.cacheDir, "db_ambient_$now.wav").apply { writeBytes(ByteArray(128)) }
        val oldCallFile = File(context.cacheDir, "db_oldcall_$now.wav").apply { writeBytes(ByteArray(128)) }
        val lockedCallFile = File(context.cacheDir, "db_locked_$now.wav").apply { writeBytes(ByteArray(128)) }

        var ambientId = 0L
        var oldCallId = 0L
        var lockedCallId = 0L
        try {
            ambientId = repo.insertCallRecord(
                CallRecord(
                    phoneNumber = "Ortam Kaydı",
                    contactName = null,
                    isContact = false,
                    callType = CallType.AMBIENT_RECORDING,
                    startTime = oldTime,
                    endTime = oldTime + 5000,
                    durationSeconds = 5,
                    audioFilePath = oldAmbientFile.absolutePath,
                    videoFilePath = null,
                    touchFilePath = null,
                    transcript = null,
                    isLocked = false,
                    createdAt = oldTime
                )
            )
            oldCallId = repo.insertCallRecord(
                CallRecord(
                    phoneNumber = "+905550000001",
                    contactName = null,
                    isContact = false,
                    callType = CallType.INCOMING_GSM,
                    startTime = oldTime,
                    endTime = oldTime + 5000,
                    durationSeconds = 5,
                    audioFilePath = oldCallFile.absolutePath,
                    videoFilePath = null,
                    touchFilePath = null,
                    transcript = null,
                    isLocked = false,
                    createdAt = oldTime
                )
            )
            lockedCallId = repo.insertCallRecord(
                CallRecord(
                    phoneNumber = "+905550000002",
                    contactName = "Kilitli Test",
                    isContact = true,
                    callType = CallType.INCOMING_GSM,
                    startTime = oldTime,
                    endTime = oldTime + 5000,
                    durationSeconds = 5,
                    audioFilePath = lockedCallFile.absolutePath,
                    videoFilePath = null,
                    touchFilePath = null,
                    transcript = null,
                    isLocked = true,
                    createdAt = oldTime
                )
            )

            repo.purgeOldRecords(contactDays = 3, unknownDays = 7)

            assertNotNull("Ortam kaydi otomatik silinmemeli", repo.getCallRecordById(ambientId))
            assertNull("Eski kilitsiz arama silinmeli", repo.getCallRecordById(oldCallId))
            assertNotNull("Kilitli arama korunmali", repo.getCallRecordById(lockedCallId))
            assertTrue("ExoPlayer kanal islemcisi temel kontrol", !StereoChannelControlAudioProcessor().isActive())
        } finally {
            repo.getCallRecordById(ambientId)?.let { repo.deleteCallRecord(it) }
            repo.getCallRecordById(oldCallId)?.let { repo.deleteCallRecord(it) }
            repo.getCallRecordById(lockedCallId)?.let { repo.deleteCallRecord(it) }
            oldAmbientFile.delete()
            oldCallFile.delete()
            lockedCallFile.delete()
        }
    }

    private fun verifyPlayableVideo(
        context: Context,
        uri: Uri,
        expectWidth: String,
        expectHeight: String,
        expectAudio: Boolean
    ) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            assertEquals("yes", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            if (expectAudio) {
                assertEquals("yes", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            }
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue("Video suresi 0'dan buyuk olmali: $durationMs", durationMs > 0L)
            assertEquals(expectWidth, mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals(expectHeight, mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))

            val frame = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertNotNull("En az bir video karesi cozumlenebilmeli", frame)
            val centerColors = HashSet<Int>()
            val midX = frame!!.width / 2
            for (y in 0 until frame.height step 8) {
                centerColors.add(frame.getPixel(midX, y))
            }
            frame.recycle()
            assertTrue("Kare icerigi bozuk veya tek renk olmamali (renk sayisi=${centerColors.size})", centerColors.size > 3)
        } finally {
            mmr.release()
        }
    }

    private fun findMediaStoreVideoByName(context: Context, displayName: String): Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun findMediaStoreVideoByPrefix(context: Context, prefix: String): Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun deleteMediaStoreVideoByName(context: Context, displayName: String) {
        context.contentResolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName)
        )
    }

    private fun deleteMediaStoreVideoByPrefix(context: Context, prefix: String) {
        context.contentResolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%")
        )
    }

    private fun createMinimalHardwareMp4(outFile: File, width: Int, height: Int, frameCount: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            val paint = android.graphics.Paint().apply { color = android.graphics.Color.CYAN }
            for (i in 0 until frameCount) {
                val canvas = surface.lockCanvas(null)
                canvas.drawRGB(30 + (i * 5) % 180, 90, 200)
                canvas.drawCircle(width / 2f, height / 2f, 120f, paint)
                surface.unlockCanvasAndPost(canvas)
                Thread.sleep(20)
                drainEncoder(encoder, muxer, endOfStream = false, trackIndexRef = { trackIndex }, setTrackIndex = { idx ->
                    trackIndex = idx
                    muxer.start()
                    muxerStarted = true
                }, isMuxerStarted = { muxerStarted })
            }

            encoder.signalEndOfInputStream()
            drainEncoder(encoder, muxer, endOfStream = true, trackIndexRef = { trackIndex }, setTrackIndex = { idx ->
                trackIndex = idx
                muxer.start()
                muxerStarted = true
            }, isMuxerStarted = { muxerStarted })
            surface.release()
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        endOfStream: Boolean,
        trackIndexRef: () -> Int,
        setTrackIndex: (Int) -> Unit,
        isMuxerStarted: () -> Boolean
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val status = encoder.dequeueOutputBuffer(info, if (endOfStream) 20_000L else 5_000L)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!isMuxerStarted()) {
                    setTrackIndex(muxer.addTrack(encoder.outputFormat))
                }
            } else if (status >= 0) {
                val encoded = encoder.getOutputBuffer(status)
                if (encoded != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && isMuxerStarted()) {
                    encoded.position(info.offset)
                    encoded.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndexRef(), encoded, info)
                }
                encoder.releaseOutputBuffer(status, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun writeSyntheticWav(file: File, sampleRate: Int, channels: Int, durationSec: Int, amplitude: Int) {
        val totalFrames = sampleRate * durationSec
        val dataSize = totalFrames * channels * 2
        val byteRate = sampleRate * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(dataSize + 36)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()

        val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames) {
            val sampleL = (sin(2.0 * PI * 440.0 * i / sampleRate) * amplitude).toInt().toShort()
            pcmBuffer.putShort(sampleL)
            if (channels == 2) {
                val sampleR = (sin(2.0 * PI * 660.0 * i / sampleRate) * amplitude).toInt().toShort()
                pcmBuffer.putShort(sampleR)
            }
        }

        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmBuffer.array())
        }
    }
}
