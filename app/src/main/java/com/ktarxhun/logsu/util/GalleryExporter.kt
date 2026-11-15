package com.ktarxhun.logsu.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.ktarxhun.logsu.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

object GalleryExporter {
    private const val TAG = "GalleryExporter"

    internal data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    private data class PendingSample(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo
    )

    internal fun readWavInfo(wavFile: File): WavInfo? {
        if (!wavFile.exists() || wavFile.length() <= 44L) return null
        return try {
            FileInputStream(wavFile).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return null
                val channels = ((header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)).coerceIn(1, 2)
                val sampleRate = ((header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)).coerceAtLeast(8000)
                val bitsPerSample = ((header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)).coerceAtLeast(16)
                val dataSize = (wavFile.length() - 44L).coerceAtLeast(0L)
                WavInfo(sampleRate, channels, bitsPerSample, 44L, dataSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV basligi okunamadi", e)
            null
        }
    }

    private fun saveMp4ToGallery(context: Context, tempMp4: File, fileName: String): Boolean {
        if (!tempMp4.exists() || tempMp4.length() < 512L) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/LogSU")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(tempMp4).use { input ->
                        input.copyTo(out)
                    }
                } ?: return false
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore yazma hatasi", e)
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                false
            }
        }

        val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LogSU")
        if (!dcimDir.exists()) dcimDir.mkdirs()
        val destFile = File(dcimDir, fileName)
        tempMp4.copyTo(destFile, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
        return destFile.exists()
    }

    /**
     * Emanet Modu ekran videosunu (ve varsa sesini) DCIM/LogSU klasörüne aktarır.
     */
    suspend fun exportVideoToGallery(
        context: Context,
        videoFile: File,
        audioFile: File? = null,
        customName: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() < 1024L) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Kayıt dosyası bulunamadı!", Toast.LENGTH_SHORT).show()
            }
            return@withContext false
        }

        val tempOut = File(context.cacheDir, "export_vid_${System.currentTimeMillis()}.mp4")
        val readableVideo = File(context.cacheDir, "src_vid_${System.currentTimeMillis()}.mp4")

        try {
            // Root sahipliğindeki dosyayı okunabilir geçici alana al
            RootShellManager.execute("cp -f '${videoFile.absolutePath}' '${readableVideo.absolutePath}' && chmod 666 '${readableVideo.absolutePath}'")
            val sourceVideo = if (readableVideo.exists() && readableVideo.length() > 1024L) readableVideo else videoFile

            var muxSuccess = false
            val wavInfo = audioFile?.let { readWavInfo(it) }
            if (audioFile != null && wavInfo != null && wavInfo.dataSize > 0L) {
                try {
                    muxSuccess = muxVideoAndAudio(sourceVideo, audioFile, wavInfo, tempOut)
                } catch (e: Exception) {
                    Log.w(TAG, "Video ve ses birlestirme basarisiz, yalnizca video aktarilacak: ${e.message}")
                }
            }

            val fileToPublish = if (muxSuccess && tempOut.exists() && tempOut.length() > 1024L) tempOut else sourceVideo
            val fileName = "${customName}_${System.currentTimeMillis()}.mp4"
            val saved = saveMp4ToGallery(context, fileToPublish, fileName)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (saved) "Video Galeriye kaydedildi (DCIM/LogSU)" else "Galeriye aktarma başarısız oldu.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            saved
        } catch (e: Exception) {
            Log.e(TAG, "Video galeriye aktarma hatasi", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Kayıt hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            false
        } finally {
            tempOut.delete()
            readableVideo.delete()
        }
    }

    /**
     * Bir ses kaydını (.wav), üzerinde LogSU amblemi ve bilgileri olan dikey bir MP4 videosuna dönüştürür.
     */
    suspend fun exportAudioAsVideoToGallery(
        context: Context,
        audioFile: File,
        title: String,
        subtitle: String,
        onDone: (Boolean, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val wavInfo = readWavInfo(audioFile)
        if (wavInfo == null || wavInfo.dataSize <= 0L) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Ses dosyası bulunamadı veya boş!", Toast.LENGTH_SHORT).show()
                onDone(false, null)
            }
            return@withContext
        }

        val tempMp4 = File(context.cacheDir, "export_audio_${System.currentTimeMillis()}.mp4")
        try {
            val bytesPerSec = wavInfo.sampleRate.toLong() * wavInfo.channels.toLong() * (wavInfo.bitsPerSample / 8L)
            val durationSec = if (bytesPerSec > 0L) {
                ((wavInfo.dataSize + bytesPerSec - 1L) / bytesPerSec).coerceAtLeast(1L)
            } else {
                1L
            }

            // 768x1280 hem 128-byte donanım hizalamasına (Snapdragon) tam uyumludur hem dikey 9:15 orandadır
            val width = 768
            val height = 1280
            val posterBitmap = createEmblemPoster(width, height, title, subtitle)
            val success = encodeAudioAndImageToMp4(audioFile, wavInfo, posterBitmap, tempMp4, durationSec, width, height)
            posterBitmap.recycle()

            val fileName = "LogSU_SesKaydi_${System.currentTimeMillis()}.mp4"
            val saved = if (success) saveMp4ToGallery(context, tempMp4, fileName) else false

            withContext(Dispatchers.Main) {
                if (saved) {
                    Toast.makeText(context, "Ses kaydı Galeriye kaydedildi (DCIM/LogSU)", Toast.LENGTH_SHORT).show()
                    onDone(true, fileName)
                } else {
                    Toast.makeText(context, "Dönüştürme başarısız oldu.", Toast.LENGTH_SHORT).show()
                    onDone(false, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ses -> Video donusturme hatasi", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
                onDone(false, null)
            }
        } finally {
            tempMp4.delete()
        }
    }

    private fun createEmblemPoster(width: Int, height: Int, title: String, subtitle: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.parseColor("#0F1115"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.parseColor("#181B22")
        canvas.drawCircle(width / 2f, height / 2f - 80f, 130f, paint)

        paint.color = Color.parseColor("#4E80EE")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawCircle(width / 2f, height / 2f - 80f, 130f, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = 64f
        paint.color = Color.parseColor("#38BDF8")
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("LogSU", width / 2f, height / 2f - 60f, paint)

        paint.color = Color.WHITE
        paint.textSize = 34f
        paint.typeface = Typeface.DEFAULT_BOLD
        val safeTitle = if (title.length > 28) title.take(25) + "..." else title
        canvas.drawText(safeTitle, width / 2f, height / 2f + 120f, paint)

        paint.color = Color.parseColor("#94A3B8")
        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(subtitle, width / 2f, height / 2f + 170f, paint)

        paint.color = Color.parseColor("#4E80EE")
        paint.textSize = 20f
        canvas.drawText("LogSU Güvenli Ses Kaydı", width / 2f, height - 120f, paint)

        return bitmap
    }

    private fun muxVideoAndAudio(videoFile: File, wavFile: File, wavInfo: WavInfo, outputFile: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var audioCodec: MediaCodec? = null
        var fis: FileInputStream? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(videoFile.absolutePath)

            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }
            if (videoTrackIndex < 0) return false
            extractor.selectTrack(videoTrackIndex)
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", wavInfo.sampleRate, wavInfo.channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec.start()

            var muxerAudioTrack = -1
            var muxerStarted = false
            val pendingAudio = mutableListOf<PendingSample>()

            fis = FileInputStream(wavFile)
            fis.skip(wavInfo.dataOffset)

            val pcmBuffer = ByteArray(4096)
            val bytesPerSecond = wavInfo.sampleRate.toDouble() * wavInfo.channels * 2.0
            var pcmEos = false
            var audioEos = false
            var totalPcmBytes = 0L
            val audioBufferInfo = MediaCodec.BufferInfo()

            while (!audioEos) {
                if (!pcmEos) {
                    val inIdx = audioCodec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val inputBuf = audioCodec.getInputBuffer(inIdx)
                        val bytesRead = fis.read(pcmBuffer)
                        val ptsUs = (totalPcmBytes.toDouble() / bytesPerSecond * 1_000_000.0).toLong()
                        if (bytesRead > 0) {
                            inputBuf?.clear()
                            inputBuf?.put(pcmBuffer, 0, bytesRead)
                            audioCodec.queueInputBuffer(inIdx, 0, bytesRead, ptsUs, 0)
                            totalPcmBytes += bytesRead
                        } else {
                            pcmEos = true
                            audioCodec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }

                val outIdx = audioCodec.dequeueOutputBuffer(audioBufferInfo, 5000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        muxerAudioTrack = muxer.addTrack(audioCodec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        for (sample in pendingAudio) {
                            muxer.writeSampleData(muxerAudioTrack, ByteBuffer.wrap(sample.data), sample.info)
                        }
                        pendingAudio.clear()
                    }
                } else if (outIdx >= 0) {
                    val isConfig = (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && audioBufferInfo.size > 0) {
                        val outBuf = audioCodec.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            outBuf.position(audioBufferInfo.offset)
                            outBuf.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            if (muxerStarted) {
                                muxer.writeSampleData(muxerAudioTrack, outBuf, audioBufferInfo)
                            } else {
                                val bytes = ByteArray(audioBufferInfo.size)
                                outBuf.get(bytes)
                                val copyInfo = MediaCodec.BufferInfo().apply {
                                    set(0, bytes.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                                }
                                pendingAudio.add(PendingSample(bytes, copyInfo))
                            }
                        }
                    }
                    audioCodec.releaseOutputBuffer(outIdx, false)
                    if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        audioEos = true
                    }
                }
            }

            if (!muxerStarted) return false

            val maxBufSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
            } else {
                2 * 1024 * 1024
            }
            val videoBuf = ByteBuffer.allocate(maxBufSize)
            val vBufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(videoBuf, 0)
                if (sampleSize < 0) break

                val extFlags = extractor.sampleFlags
                var codecFlags = 0
                if ((extFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if ((extFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }

                vBufferInfo.set(0, sampleSize, extractor.sampleTime.coerceAtLeast(0L), codecFlags)
                muxer.writeSampleData(muxerVideoTrack, videoBuf, vBufferInfo)
                extractor.advance()
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Muxer hatasi", e)
            return false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun encodeAudioAndImageToMp4(
        wavFile: File,
        wavInfo: WavInfo,
        posterBitmap: Bitmap,
        outputMp4: File,
        durationSec: Long,
        width: Int,
        height: Int
    ): Boolean {
        var muxer: MediaMuxer? = null
        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var fis: FileInputStream? = null

        try {
            muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // H.264 Video Encoder (Flexible YUV420 Image API: her cihazda stride/padding uyumlu)
            val videoFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 800000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 1)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoCodec = MediaCodec.createEncoderByType("video/avc")
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoCodec.start()

            // AAC Audio Encoder
            val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", wavInfo.sampleRate, wavInfo.channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec.start()

            // Bitmap'ten Y, U, V düzlemlerini bir kez hesapla
            val (yPlaneData, uPlaneData, vPlaneData) = bitmapToYuv420Planes(posterBitmap, width, height)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false

            val pendingVideo = mutableListOf<PendingSample>()
            val pendingAudio = mutableListOf<PendingSample>()

            fun tryStartMuxer() {
                if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                    muxer.start()
                    muxerStarted = true
                    for (sample in pendingVideo) {
                        muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(sample.data), sample.info)
                    }
                    pendingVideo.clear()
                    for (sample in pendingAudio) {
                        muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(sample.data), sample.info)
                    }
                    pendingAudio.clear()
                }
            }

            fis = FileInputStream(wavFile)
            fis.skip(wavInfo.dataOffset)

            val pcmBuffer = ByteArray(4096)
            val bytesPerSecond = wavInfo.sampleRate.toDouble() * wavInfo.channels * 2.0
            var totalPcmBytes = 0L
            var pcmEos = false
            var audioEos = false

            val totalVideoFrames = durationSec.coerceAtLeast(1L)
            var videoFramesSent = 0L
            var videoInputEos = false
            var videoEos = false

            val videoBufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()

            while (!videoEos || !audioEos) {
                // 1. Video karesi besle
                if (!videoInputEos) {
                    val vInIdx = videoCodec.dequeueInputBuffer(2000)
                    if (vInIdx >= 0) {
                        if (videoFramesSent < totalVideoFrames) {
                            val image = videoCodec.getInputImage(vInIdx)
                            if (image != null) {
                                val yStride = image.planes[0].rowStride
                                fillYuvImage(image, yPlaneData, uPlaneData, vPlaneData, width, height)
                                val ptsUs = videoFramesSent * 1_000_000L
                                // planes[0].buffer yalnızca Y düzlemini kapsadığı için toplam YUV420 boyutu hesaplanır.
                                val size = maxOf(yStride * height * 3 / 2, width * height * 3 / 2)
                                videoCodec.queueInputBuffer(vInIdx, 0, size, ptsUs, 0)
                            } else {
                                val buf = videoCodec.getInputBuffer(vInIdx)
                                buf?.clear()
                                buf?.put(yPlaneData)
                                buf?.put(uPlaneData)
                                buf?.put(vPlaneData)
                                val ptsUs = videoFramesSent * 1_000_000L
                                videoCodec.queueInputBuffer(vInIdx, 0, yPlaneData.size + uPlaneData.size + vPlaneData.size, ptsUs, 0)
                            }
                            videoFramesSent++
                        } else {
                            videoInputEos = true
                            videoCodec.queueInputBuffer(vInIdx, 0, 0, totalVideoFrames * 1_000_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }

                // 2. Ses PCM besle
                if (!pcmEos) {
                    val aInIdx = audioCodec.dequeueInputBuffer(2000)
                    if (aInIdx >= 0) {
                        val inputBuf = audioCodec.getInputBuffer(aInIdx)
                        val bytesRead = fis.read(pcmBuffer)
                        val ptsUs = (totalPcmBytes.toDouble() / bytesPerSecond * 1_000_000.0).toLong()
                        if (bytesRead > 0) {
                            inputBuf?.clear()
                            inputBuf?.put(pcmBuffer, 0, bytesRead)
                            audioCodec.queueInputBuffer(aInIdx, 0, bytesRead, ptsUs, 0)
                            totalPcmBytes += bytesRead
                        } else {
                            pcmEos = true
                            audioCodec.queueInputBuffer(aInIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }

                // 3. Video çıktısını boşalt
                if (!videoEos) {
                    val vOutIdx = videoCodec.dequeueOutputBuffer(videoBufferInfo, 2000)
                    if (vOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = muxer.addTrack(videoCodec.outputFormat)
                            tryStartMuxer()
                        }
                    } else if (vOutIdx >= 0) {
                        val isConfig = (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && videoBufferInfo.size > 0) {
                            val outBuf = videoCodec.getOutputBuffer(vOutIdx)
                            if (outBuf != null) {
                                outBuf.position(videoBufferInfo.offset)
                                outBuf.limit(videoBufferInfo.offset + videoBufferInfo.size)
                                if (muxerStarted) {
                                    muxer.writeSampleData(videoTrackIndex, outBuf, videoBufferInfo)
                                } else {
                                    val bytes = ByteArray(videoBufferInfo.size)
                                    outBuf.get(bytes)
                                    val copyInfo = MediaCodec.BufferInfo().apply {
                                        set(0, bytes.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags)
                                    }
                                    pendingVideo.add(PendingSample(bytes, copyInfo))
                                }
                            }
                        }
                        videoCodec.releaseOutputBuffer(vOutIdx, false)
                        if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            videoEos = true
                        }
                    }
                }

                // 4. Ses çıktısını boşalt
                if (!audioEos) {
                    val aOutIdx = audioCodec.dequeueOutputBuffer(audioBufferInfo, 2000)
                    if (aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = muxer.addTrack(audioCodec.outputFormat)
                            tryStartMuxer()
                        }
                    } else if (aOutIdx >= 0) {
                        val isConfig = (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && audioBufferInfo.size > 0) {
                            val outBuf = audioCodec.getOutputBuffer(aOutIdx)
                            if (outBuf != null) {
                                outBuf.position(audioBufferInfo.offset)
                                outBuf.limit(audioBufferInfo.offset + audioBufferInfo.size)
                                if (muxerStarted) {
                                    muxer.writeSampleData(audioTrackIndex, outBuf, audioBufferInfo)
                                } else {
                                    val bytes = ByteArray(audioBufferInfo.size)
                                    outBuf.get(bytes)
                                    val copyInfo = MediaCodec.BufferInfo().apply {
                                        set(0, bytes.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                                    }
                                    pendingAudio.add(PendingSample(bytes, copyInfo))
                                }
                            }
                        }
                        audioCodec.releaseOutputBuffer(aOutIdx, false)
                        if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            audioEos = true
                        }
                    }
                }
            }

            return muxerStarted
        } catch (e: Exception) {
            Log.e(TAG, "Donusturme motoru hatasi", e)
            return false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
            try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun bitmapToYuv420Planes(bitmap: Bitmap, width: Int, height: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yPlane = ByteArray(width * height)
        val uvSize = (width / 2) * (height / 2)
        val uPlane = ByteArray(uvSize)
        val vPlane = ByteArray(uvSize)

        var yIdx = 0
        var uvIdx = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[yIdx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane[yIdx++] = y.coerceIn(0, 255).toByte()

                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uPlane[uvIdx] = u.coerceIn(0, 255).toByte()
                    vPlane[uvIdx] = v.coerceIn(0, 255).toByte()
                    uvIdx++
                }
            }
        }
        return Triple(yPlane, uPlane, vPlane)
    }

    private fun fillYuvImage(
        image: Image,
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        width: Int,
        height: Int
    ) {
        val planes = image.planes
        // Y düzlemi
        val yBuf = planes[0].buffer
        val yRowStride = planes[0].rowStride
        if (yRowStride == width) {
            yBuf.put(yData)
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                yBuf.put(yData, row * width, width)
            }
        }

        val halfW = width / 2
        val halfH = height / 2
        // U ve V düzlemleri (queueInputBuffer ownership devralacağı için image.close() çağrılmaz)
        copyChromaPlane(planes[1], uData, halfW, halfH)
        copyChromaPlane(planes[2], vData, halfW, halfH)
    }

    private fun copyChromaPlane(plane: Image.Plane, data: ByteArray, halfW: Int, halfH: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 1 && rowStride == halfW) {
            buf.put(data)
            return
        }
        for (row in 0 until halfH) {
            val rowBase = row * rowStride
            val srcBase = row * halfW
            for (col in 0 until halfW) {
                buf.put(rowBase + col * pixelStride, data[srcBase + col])
            }
        }
    }
}
