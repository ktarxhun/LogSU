package com.ktarxhun.logsu.root

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplayConfig
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.system.exitProcess

/**
 * Root (app_process) altında çalışan, Context gerektirmeyen MediaCodec + VirtualDisplay ekran kaydedici.
 * DisplayManager üzerinden oluşturduğu sanal ekranı doğrudan donanım H.264 MediaCodec girişine bağlar;
 * böylece system_server'daki VirtualDisplayAdapter kancalarıyla uyumlu çalışır ve 180 sn limiti olmadan MP4 üretir.
 */
object RootDisplayRecorder {

    // stopFile hic olusmazsa (uygulama sureci olurse) kayit sonsuza kadar acik kalmasin.
    private const val DEFAULT_MAX_DURATION_MS = 60L * 60L * 1000L
    private const val MAX_OUTPUT_BYTES = 3_500_000_000L

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 5) {
            System.err.println("Kullanim: RootDisplayRecorder <outputMp4> <width> <height> <bitRate> <stopFile> [parentPid] [maxDurationMs]")
            exitProcess(2)
        }

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {
        }

        val outputPath = args[0]
        val width = args[1].toIntOrNull()?.coerceAtLeast(240) ?: 1080
        val height = args[2].toIntOrNull()?.coerceAtLeast(320) ?: 2400
        val bitRate = args[3].toIntOrNull()?.coerceAtLeast(500_000) ?: 10_000_000
        val stopFile = File(args[4])
        val parentPid = args.getOrNull(5)?.toIntOrNull() ?: -1
        val maxDurationMs = args.getOrNull(6)?.toLongOrNull()
            ?.takeIf { it in 1_000L..86_400_000L }
            ?: DEFAULT_MAX_DURATION_MS
        val startTimeMs = System.currentTimeMillis()

        try {
            Looper.prepareMainLooper()
        } catch (_: Throwable) {
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var inputSurface: android.view.Surface? = null
        var iDisplayManager: Any? = null
        var releaseDisplayMethod: java.lang.reflect.Method? = null
        var callbackObj: Any? = null
        var exitCode = 0

        try {
            File(outputPath).parentFile?.mkdirs()

            val encoder = createAndConfigureEncoder(width, height, bitRate)
            codec = encoder
            val surface = encoder.createInputSurface()
            inputSurface = surface
            encoder.start()

            val videoMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = videoMuxer

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE

            val config = VirtualDisplayConfig.Builder("LogSU_Guard", width, height, DisplayMetrics.DENSITY_440)
                .setFlags(flags)
                .setSurface(surface)
                .setRequestedRefreshRate(30f)
                .build()

            val smClass = Class.forName("android.os.ServiceManager")
            val displayBinder = smClass.getMethod("getService", String::class.java).invoke(null, "display") as IBinder
            val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val dm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, displayBinder)!!
            iDisplayManager = dm

            val dmgCallbackClass = Class.forName("android.hardware.display.DisplayManagerGlobal\$VirtualDisplayCallback")
            val dmgCallbackCtor = dmgCallbackClass.getDeclaredConstructor(
                Class.forName("android.hardware.display.VirtualDisplay\$Callback"),
                Class.forName("java.util.concurrent.Executor")
            ).apply { isAccessible = true }
            val callback = dmgCallbackCtor.newInstance(null, null)
            callbackObj = callback

            val iCallbackInterface = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val iMediaProjectionInterface = Class.forName("android.media.projection.IMediaProjection")
            releaseDisplayMethod = dm.javaClass.getMethod("releaseVirtualDisplay", iCallbackInterface)

            val createMethod = dm.javaClass.getMethod(
                "createVirtualDisplay",
                VirtualDisplayConfig::class.java,
                iCallbackInterface,
                iMediaProjectionInterface,
                String::class.java
            )

            var displayId = -1
            var lastError: Throwable? = null
            for (pkg in listOf("android", "com.android.shell", "com.ktarxhun.logsu")) {
                try {
                    displayId = (createMethod.invoke(dm, config, callback, null, pkg) as? Int) ?: -1
                    if (displayId >= 0) break
                } catch (t: Throwable) {
                    lastError = t.cause ?: t
                }
            }

            if (displayId < 0) {
                throw IllegalStateException("createVirtualDisplay basarisiz: ${lastError?.message}", lastError)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var eosSignaled = false
            var eosDeadlineMs = 0L
            var firstPtsUs = -1L
            var lastWrittenPtsUs = -1L
            var nextWatchdogCheckMs = startTimeMs + 2_000L
            var nextSizeCheckMs = startTimeMs + 5_000L

            fun requestEos(reason: String) {
                System.err.println(reason)
                eosSignaled = true
                eosDeadlineMs = System.currentTimeMillis() + 2000L
                try {
                    encoder.signalEndOfInputStream()
                } catch (e: Exception) {
                    System.err.println("signalEndOfInputStream hatasi: ${e.message}")
                }
            }

            while (true) {
                if (!eosSignaled) {
                    val now = System.currentTimeMillis()
                    if (stopFile.exists()) {
                        requestEos("Durma bayragi goruldu, kayit kapatiliyor.")
                    } else if (now - startTimeMs >= maxDurationMs) {
                        requestEos("Azami kayit suresi asildi, kayit kapatiliyor.")
                    } else if (parentPid > 0 && now >= nextWatchdogCheckMs) {
                        nextWatchdogCheckMs = now + 2_000L
                        if (!File("/proc/$parentPid").exists()) {
                            requestEos("Ebeveyn surec ($parentPid) artik yok, kayit kapatiliyor.")
                        }
                    } else if (now >= nextSizeCheckMs) {
                        nextSizeCheckMs = now + 5_000L
                        if (File(outputPath).length() > MAX_OUTPUT_BYTES) {
                            requestEos("Azami dosya boyutu asildi, kayit kapatiliyor.")
                        }
                    }
                }

                if (eosSignaled && System.currentTimeMillis() > eosDeadlineMs) {
                    break
                }

                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 80_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = videoMuxer.addTrack(encoder.outputFormat)
                            videoMuxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            muxerStarted && trackIndex >= 0
                        ) {
                            if (firstPtsUs < 0L) {
                                firstPtsUs = bufferInfo.presentationTimeUs
                            }
                            var normalizedPts = (bufferInfo.presentationTimeUs - firstPtsUs).coerceAtLeast(0L)
                            if (normalizedPts <= lastWrittenPtsUs) {
                                normalizedPts = lastWrittenPtsUs + 1L
                            }
                            lastWrittenPtsUs = normalizedPts
                            bufferInfo.presentationTimeUs = normalizedPts

                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            videoMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (eos) break
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            exitCode = 1
        } finally {
            try {
                if (iDisplayManager != null && callbackObj != null && releaseDisplayMethod != null) {
                    releaseDisplayMethod.invoke(iDisplayManager, callbackObj)
                }
            } catch (_: Throwable) {
            }
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                inputSurface?.release()
            } catch (_: Throwable) {
            }
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            try {
                val outFile = File(outputPath)
                if (outFile.exists()) {
                    Os.chmod(outFile.absolutePath, 438)
                }
            } catch (_: Throwable) {
            }
            try {
                if (stopFile.exists()) stopFile.delete()
            } catch (_: Throwable) {
            }
        }
        exitProcess(exitCode)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createHardwareEncoder(mimeType: String): MediaCodec {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                val name = info.name.lowercase()
                if (name.startsWith("c2.android.") || name.startsWith("omx.google.")) continue
                if (info.isSoftwareOnly) continue
                for (type in info.supportedTypes) {
                    if (type.equals(mimeType, ignoreCase = true)) {
                        try {
                            return MediaCodec.createByCodecName(info.name)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            System.err.println("Donanim AVC codec listeleme hatasi: ${t.message}")
        }
        return MediaCodec.createEncoderByType(mimeType)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createAndConfigureEncoder(
        width: Int,
        height: Int,
        bitRate: Int
    ): MediaCodec {
        val advancedFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30.0f)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel5)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
        }

        var encoder = createHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            encoder.configure(advancedFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return encoder
        } catch (e: Exception) {
            System.err.println("Gelistirilmis MediaFormat reddedildi, sade format deneniyor: ${e.message}")
            try { encoder.release() } catch (_: Throwable) {}
        }

        val minimalFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
        }

        encoder = createHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(minimalFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return encoder
    }
}
