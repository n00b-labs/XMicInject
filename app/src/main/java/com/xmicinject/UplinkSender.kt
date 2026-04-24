package com.xmicinject

import android.util.Log
import kotlin.math.sqrt

// Captures real mic audio from hooked AudioRecord calls and sends it to the provider.
// Handles multi-channel → mono conversion, sample rate conversion, and stream selection.
internal object UplinkSender {

    private const val TAG = "XMicUplink"

    // Ignore near-silence chunks when selecting which AudioRecord stream to follow.
    private const val MIN_RMS = 0.006f

    // Switch to a different stream if the current one has been silent for this long.
    private const val STREAM_STALE_MS = 1200L

    private const val NONE = Long.MIN_VALUE

    private val lock = Any()
    private var activeStreamId: Long = NONE
    private var activeStreamLastMs: Long = 0L
    private var loggedFirstSend = false

    // Called from each hooked AudioRecord.read(). buf is already filled with real mic data.
    fun send(buf: ByteArray, offset: Int, count: Int, sampleRateHz: Int, channelCount: Int, streamId: Long) {
        val mono = toMono(buf, offset, count, channelCount)
        if (mono.isEmpty()) return
        if (!selectStream(streamId, mono)) return

        val payload = if (sampleRateHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            mono
        } else {
            AudioResampler.resampleBytes(mono, 0, mono.size, sampleRateHz, PcmRingBuffer.SAMPLE_RATE_HZ)
        }
        if (payload.isEmpty()) return
        IpcClient.write(payload)
        if (!loggedFirstSend) {
            loggedFirstSend = true
            Log.i(TAG, "Uplink flowing: sending mic to provider (srcRate=${sampleRateHz}Hz)")
        }
    }

    // Called by IpcClient on disconnect so the next connection starts fresh.
    fun reset() {
        synchronized(lock) {
            activeStreamId = NONE
            activeStreamLastMs = 0L
            loggedFirstSend = false
        }
    }

    // Picks one AudioRecord stream to follow and ignores all others.
    //
    // Problem: the hook runs in every app simultaneously. Multiple apps may have an open
    // AudioRecord at the same time (e.g. Telegram + a background recorder). We must send
    // only one mic stream to the provider — mixing them would produce garbage.
    //
    // Solution: lock onto the first stream whose RMS exceeds MIN_RMS (i.e. the user is
    // actually speaking). Stay locked until that stream goes silent for STREAM_STALE_MS,
    // then switch to whichever stream next has signal.
    private fun selectStream(streamId: Long, payload: ByteArray): Boolean {
        if (streamId == NONE) return false
        val chunkRms = rms(payload)
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val noActive = activeStreamId == NONE
            val stale = !noActive && (nowMs - activeStreamLastMs) >= STREAM_STALE_MS
            val isActive = streamId == activeStreamId

            if (noActive && chunkRms < MIN_RMS) return false

            if (noActive || (stale && chunkRms >= MIN_RMS)) {
                if (activeStreamId != streamId) {
                    Log.i(TAG, "Stream selected: id=$streamId rms=$chunkRms")
                    activeStreamId = streamId
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

    // Averages all channels into mono PCM16 LE.
    private fun toMono(src: ByteArray, offset: Int, length: Int, channelCount: Int): ByteArray {
        if (length < 2) return ByteArray(0)
        val channels = channelCount.coerceAtLeast(1)
        if (channels == 1) return src.copyOfRange(offset, offset + length)

        val bytesPerFrame = channels * 2
        val frameCount = length / bytesPerFrame
        if (frameCount <= 0) return ByteArray(0)

        val out = ByteArray(frameCount * 2)
        var srcIndex = offset
        var outIndex = 0
        repeat(frameCount) {
            var sum = 0
            repeat(channels) {
                val low = src[srcIndex].toInt() and 0xFF
                val high = src[srcIndex + 1].toInt()
                sum += (high shl 8) or low
                srcIndex += 2
            }
            val mono = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outIndex] = (mono and 0xFF).toByte()
            out[outIndex + 1] = ((mono ushr 8) and 0xFF).toByte()
            outIndex += 2
        }
        return out
    }

    // RMS (Root Mean Square) — measures the energy/loudness of an audio chunk.
    // Returns a value from 0.0 (silence) to 1.0 (maximum loudness).
    // Formula: sqrt( mean( (sample / MAX_VALUE)² ) )
    // Used to tell apart speech from silence when selecting the active stream.
    private fun rms(payload: ByteArray): Float {
        val sampleCount = payload.size / 2
        if (sampleCount <= 0) return 0f
        var sumSquares = 0.0
        var i = 0
        repeat(sampleCount) {
            val low = payload[i].toInt() and 0xFF
            val high = payload[i + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
            i += 2
        }
        return sqrt(sumSquares / sampleCount).toFloat()
    }
}
