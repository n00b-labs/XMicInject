package com.xmicinject

import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

// Captures real mic audio from hooked AudioRecord calls and writes it to a WAV file.
// Handles multi-channel to mono conversion, sample rate conversion, and stream selection.
internal object UplinkSender {

    private const val TAG: String = "XMicUplink"
    internal const val OUTPUT_SAMPLE_RATE_HZ: Int = WIRE_SAMPLE_RATE_HZ

    // Ignore near-silence chunks when selecting which AudioRecord stream to follow.
    private const val MIN_RMS: Float = 0.006f

    // Switch to a different stream if the current one has been silent for this long.
    private const val STREAM_STALE_MS: Long = 1200L

    private const val NONE: Long = Long.MIN_VALUE

    private val lock: Any = Any()
    private val ignoredStreams: MutableSet<Long> = HashSet()
    private val observedStreams: MutableSet<Long> = HashSet()
    private var activeStreamId: Long = NONE
    private var activeStreamSource: Int = -1
    private var activeStreamLastMs: Long = 0L
    private var loggedFirstSend: Boolean = false
    private var lastActiveStreamLogMs: Long = 0L

    fun configureCaptureOutput(dataDir: String?) {
        CaptureFileWriter.configure(dataDir)
    }

    // Called from each hooked AudioRecord.read(). buf is already filled with real mic data.
    fun send(
        buf: ByteArray,
        offset: Int,
        count: Int,
        sampleRateHz: Int,
        channelCount: Int,
        streamId: Long,
        packageName: String,
        audioSource: Int
    ) {
        val mono: ByteArray = toMono(buf, offset, count, channelCount)
        if (mono.isEmpty()) return

        val chunkRms: Float = rms(mono)
        logObservedStream(
            packageName = packageName,
            streamId = streamId,
            audioSource = audioSource,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            chunkBytes = count,
            chunkRms = chunkRms
        )
        if (!selectStream(
                streamId = streamId,
                packageName = packageName,
                audioSource = audioSource,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                chunkRms = chunkRms
            )
        ) return

        val payload: ByteArray = AudioResampler.resampleBytes(mono, 0, mono.size, sampleRateHz, OUTPUT_SAMPLE_RATE_HZ)
        if (payload.isEmpty()) return

        CaptureFileWriter.appendPcm16Mono(payload)
        IpcClient.write(payload)
        if (!loggedFirstSend) {
            loggedFirstSend = true
            Log.i(
                TAG,
                "Capture flowing: pkg=$packageName streamId=$streamId " +
                    "source=${audioSourceName(audioSource)} srcRate=${sampleRateHz}Hz " +
                    "ch=$channelCount dstRate=${OUTPUT_SAMPLE_RATE_HZ}Hz"
            )
        }

        val nowMs: Long = System.currentTimeMillis()
        if ((nowMs - lastActiveStreamLogMs) >= 2_000L) {
            lastActiveStreamLogMs = nowMs
            Log.d(
                TAG,
                "Capture active: pkg=$packageName streamId=$streamId " +
                    "source=${audioSourceName(audioSource)} rms=$chunkRms bytes=$count"
            )
        }
    }

    fun logIgnoredSource(
        packageName: String,
        streamId: Long,
        audioSource: Int,
        sampleRateHz: Int,
        channelCount: Int
    ) {
        if (streamId == NONE) return
        synchronized(lock) {
            if (!ignoredStreams.add(streamId)) return
        }
        Log.i(
            TAG,
            "Ignoring source: pkg=$packageName streamId=$streamId " +
                "source=${audioSourceName(audioSource)} sampleRate=${sampleRateHz}Hz ch=$channelCount"
        )
    }

    fun reset() {
        synchronized(lock) {
            activeStreamId = NONE
            activeStreamSource = -1
            activeStreamLastMs = 0L
            loggedFirstSend = false
            lastActiveStreamLogMs = 0L
            observedStreams.clear()
            ignoredStreams.clear()
        }
        CaptureFileWriter.reset()
    }

    // Telegram may open multiple AudioRecord instances at once. We dump only one active
    // stream to the file; mixing several instances would make the capture unusable.
    private fun selectStream(
        streamId: Long,
        packageName: String,
        audioSource: Int,
        sampleRateHz: Int,
        channelCount: Int,
        chunkRms: Float
    ): Boolean {
        if (streamId == NONE) return false
        val nowMs: Long = System.currentTimeMillis()
        synchronized(lock) {
            val noActive: Boolean = activeStreamId == NONE
            val stale: Boolean = !noActive && (nowMs - activeStreamLastMs) >= STREAM_STALE_MS
            val isActive: Boolean = streamId == activeStreamId
            val candidatePriority: Int = sourcePriority(audioSource)
            val activePriority: Int = sourcePriority(activeStreamSource)
            val betterSource: Boolean = !isActive && candidatePriority > activePriority && chunkRms >= MIN_RMS

            if (noActive && chunkRms < MIN_RMS) return false

            if (noActive || betterSource || (stale && chunkRms >= MIN_RMS)) {
                if (activeStreamId != streamId) {
                    Log.i(
                        TAG,
                        "Stream selected: pkg=$packageName streamId=$streamId " +
                            "source=${audioSourceName(audioSource)} rms=$chunkRms " +
                            "sampleRate=${sampleRateHz}Hz ch=$channelCount"
                    )
                    activeStreamId = streamId
                    activeStreamSource = audioSource
                }
                if (chunkRms >= MIN_RMS) activeStreamLastMs = nowMs
                return true
            }

            if (isActive) {
                if (chunkRms >= MIN_RMS) activeStreamLastMs = nowMs
                return true
            }
            return false
        }
    }

    private fun logObservedStream(
        packageName: String,
        streamId: Long,
        audioSource: Int,
        sampleRateHz: Int,
        channelCount: Int,
        chunkBytes: Int,
        chunkRms: Float
    ) {
        if (streamId == NONE) return
        synchronized(lock) {
            if (!observedStreams.add(streamId)) return
        }
        Log.i(
            TAG,
            "Observed stream: pkg=$packageName streamId=$streamId " +
                "source=${audioSourceName(audioSource)} sampleRate=${sampleRateHz}Hz " +
                "ch=$channelCount bytes=$chunkBytes rms=$chunkRms"
        )
    }

    private fun audioSourceName(audioSource: Int): String {
        return when (audioSource) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            else -> "UNKNOWN($audioSource)"
        }
    }

    private fun sourcePriority(audioSource: Int): Int {
        return when (audioSource) {
            MediaRecorder.AudioSource.UNPROCESSED -> 4
            MediaRecorder.AudioSource.MIC -> 3
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> 2
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> 1
            else -> 0
        }
    }

    private fun toMono(src: ByteArray, offset: Int, length: Int, channelCount: Int): ByteArray {
        if (length < 2) return ByteArray(0)
        val channels: Int = channelCount.coerceAtLeast(1)
        if (channels == 1) return src.copyOfRange(offset, offset + length)

        val bytesPerFrame: Int = channels * 2
        val frameCount: Int = length / bytesPerFrame
        if (frameCount <= 0) return ByteArray(0)

        val out = ByteArray(frameCount * 2)
        var srcIndex = offset
        var outIndex = 0
        repeat(frameCount) {
            var sum = 0
            repeat(channels) {
                val low: Int = src[srcIndex].toInt() and 0xFF
                val high: Int = src[srcIndex + 1].toInt()
                sum += (high shl 8) or low
                srcIndex += 2
            }
            val mono: Int = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outIndex] = (mono and 0xFF).toByte()
            out[outIndex + 1] = ((mono ushr 8) and 0xFF).toByte()
            outIndex += 2
        }
        return out
    }

    // RMS (Root Mean Square) measures the energy of an audio chunk.
    private fun rms(payload: ByteArray): Float {
        val sampleCount: Int = payload.size / 2
        if (sampleCount <= 0) return 0f

        var sumSquares = 0.0
        var i = 0
        repeat(sampleCount) {
            val low: Int = payload[i].toInt() and 0xFF
            val high: Int = payload[i + 1].toInt()
            val sample: Int = (high shl 8) or low
            val normalized: Double = sample.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
            i += 2
        }
        return sqrt(sumSquares / sampleCount).toFloat()
    }
}
