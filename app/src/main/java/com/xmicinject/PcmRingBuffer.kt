package com.xmicinject

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal object PcmRingBuffer {

    // 4 seconds of 16 kHz mono PCM16 = 128 000 bytes
    private const val CAPACITY = 16_000 * 2 * 4
    private val data = ByteArray(CAPACITY)
    private var writePos = 0
    private var available = 0
    private val lock = Any()

    val active: AtomicBoolean = AtomicBoolean(false)

    fun write(src: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            active.set(true)
            var remaining = length
            var srcPos = offset
            while (remaining > 0) {
                val chunk = minOf(remaining, CAPACITY - writePos)
                System.arraycopy(src, srcPos, data, writePos, chunk)
                writePos = (writePos + chunk) % CAPACITY
                available = minOf(available + chunk, CAPACITY)
                srcPos += chunk
                remaining -= chunk
            }
        }
    }

    fun readBytes(dst: ByteArray, dstOffset: Int, count: Int): Boolean {
        if (!active.get()) return false
        synchronized(lock) {
            if (available < count) return false
            var readPos = (writePos - available + CAPACITY) % CAPACITY
            var remaining = count
            var dstPos = dstOffset
            while (remaining > 0) {
                val chunk = minOf(remaining, CAPACITY - readPos)
                System.arraycopy(data, readPos, dst, dstPos, chunk)
                readPos = (readPos + chunk) % CAPACITY
                dstPos += chunk
                remaining -= chunk
            }
            available -= count
            return true
        }
    }

    fun readShorts(dst: ShortArray, dstOffset: Int, count: Int): Boolean {
        val bytes = ByteArray(count * 2)
        if (!readBytes(bytes, 0, bytes.size)) return false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) dst[dstOffset + i] = bb.getShort()
        return true
    }

    fun clear() {
        synchronized(lock) {
            available = 0
            active.set(false)
        }
    }
}
