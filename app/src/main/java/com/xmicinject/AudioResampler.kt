package com.xmicinject

import kotlin.math.ceil
import kotlin.math.floor

// Linear interpolation resampler for PCM16 mono audio.
// All functions are pure (no state) and safe to call from any thread.
internal object AudioResampler {

    // How many source bytes must be read from the ring buffer to produce `outputBytes`
    // bytes at `targetHz`, given that the buffer stores audio at `sourceHz`.
    // Uses ceil so we never under-read — better to have one extra sample than one too few.
    fun sourceBytesNeeded(outputBytes: Int, targetHz: Int, sourceHz: Int): Int {
        val outputSamples = outputBytes / 2
        if (outputSamples <= 0) return 0
        val step = sourceHz.toDouble() / targetHz.toDouble()
        val sourceSamples = ceil(((outputSamples - 1) * step) + 1.0).toInt().coerceAtLeast(1)
        return sourceSamples * 2
    }

    // Same as sourceBytesNeeded but for short[] paths where the caller works in samples.
    fun sourceSamplesNeeded(outputSamples: Int, targetHz: Int, sourceHz: Int): Int {
        if (outputSamples <= 0) return 0
        val step = sourceHz.toDouble() / targetHz.toDouble()
        return ceil(((outputSamples - 1) * step) + 1.0).toInt().coerceAtLeast(1)
    }

    // Resamples raw PCM16 LE bytes from fromHz to toHz.
    // Converts bytes → shorts internally, resamples, converts back.
    fun resampleBytes(src: ByteArray, offset: Int, length: Int, fromHz: Int, toHz: Int): ByteArray {
        if (length < 2 || fromHz <= 0 || toHz <= 0) return ByteArray(0)
        val inSamples = bytesToShorts(src, offset, length / 2)
        val outCount = ((inSamples.size.toLong() * toHz) / fromHz).toInt().coerceAtLeast(1)
        return shortsToBytes(lerp(inSamples, outCount, fromHz.toDouble() / toHz.toDouble()))
    }

    // Resamples a PCM16 short[] from fromHz to toHz.
    fun resampleShorts(src: ShortArray, offset: Int, count: Int, fromHz: Int, toHz: Int): ShortArray {
        if (count <= 0 || fromHz <= 0 || toHz <= 0) return ShortArray(0)
        val outCount = ((count.toLong() * toHz) / fromHz).toInt().coerceAtLeast(1)
        return lerp(src.copyOfRange(offset, offset + count), outCount, fromHz.toDouble() / toHz.toDouble())
    }

    // Decodes PCM16 little-endian bytes into shorts.
    // PCM16 LE layout: byte[0] = low bits (0-7), byte[1] = high bits (8-15).
    fun bytesToShorts(src: ByteArray, offset: Int, count: Int): ShortArray {
        val out = ShortArray(count)
        var i = offset
        for (j in 0 until count) {
            val low = src[i].toInt() and 0xFF      // mask avoids sign-extension
            val high = src[i + 1].toInt()
            out[j] = ((high shl 8) or low).toShort()
            i += 2
        }
        return out
    }

    // Encodes shorts back into PCM16 little-endian bytes (reverse of bytesToShorts).
    fun shortsToBytes(src: ShortArray): ByteArray {
        val out = ByteArray(src.size * 2)
        var i = 0
        for (sample in src) {
            val v = sample.toInt()
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v ushr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    // Linear interpolation core. For each output sample position, finds the two nearest
    // source samples and blends them by fractional distance.
    //
    // step = sourceHz / targetHz
    //   > 1.0 → downsampling (e.g. 48k → 16k, step = 3.0)
    //   < 1.0 → upsampling  (e.g. 16k → 48k, step = 0.33)
    //
    // Quality note: lerp has no anti-aliasing filter. It's fine for voice but will alias
    // on downsampling of music or high-frequency content.
    private fun lerp(source: ShortArray, outCount: Int, step: Double): ShortArray {
        val output = ShortArray(outCount)
        val lastIndex = source.lastIndex
        for (i in 0 until outCount) {
            val srcPos = i * step
            val left = floor(srcPos).toInt().coerceIn(0, lastIndex)
            val right = minOf(left + 1, lastIndex)
            val frac = srcPos - left
            val value = source[left] + (source[right] - source[left]) * frac
            output[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }
}
