package com.xmicinject

import java.util.concurrent.atomic.AtomicBoolean

internal object PcmRingBuffer {

    private const val BUFFER_SECONDS = 4
    private const val CAPACITY = WIRE_SAMPLE_RATE_HZ * WIRE_BYTES_PER_SAMPLE * BUFFER_SECONDS

    private val buffer = ByteArray(CAPACITY)
    private val lock = Any()
    private var writePos = 0
    private var available = 0

    val active: AtomicBoolean = AtomicBoolean(false)

    fun write(src: ByteArray) {
        if (src.isEmpty()) return
        synchronized(lock) {
            val len = src.size.coerceAtMost(CAPACITY)
            writePos = copyIntoRing(src, src.size - len, len, writePos)
            available = (available + len).coerceAtMost(CAPACITY)
        }
        active.set(true)
    }

    fun read(count: Int): ByteArray? {
        synchronized(lock) {
            if (available < count) return null
            val out = ByteArray(count)
            val readPos = (writePos - available + CAPACITY) % CAPACITY
            copyOutOfRing(readPos, out, count)
            available -= count
            return out
        }
    }

    fun clear() {
        synchronized(lock) {
            writePos = 0
            available = 0
        }
        active.set(false)
    }

    private fun copyIntoRing(src: ByteArray, srcStart: Int, count: Int, ringPos: Int): Int {
        var remaining = count
        var sp = srcStart
        var rp = ringPos
        while (remaining > 0) {
            val chunk = remaining.coerceAtMost(CAPACITY - rp)
            System.arraycopy(src, sp, buffer, rp, chunk)
            rp = (rp + chunk) % CAPACITY
            sp += chunk
            remaining -= chunk
        }
        return rp
    }

    private fun copyOutOfRing(ringPos: Int, dst: ByteArray, count: Int) {
        var remaining = count
        var rp = ringPos
        var dp = 0
        while (remaining > 0) {
            val chunk = remaining.coerceAtMost(CAPACITY - rp)
            System.arraycopy(buffer, rp, dst, dp, chunk)
            rp = (rp + chunk) % CAPACITY
            dp += chunk
            remaining -= chunk
        }
    }
}
