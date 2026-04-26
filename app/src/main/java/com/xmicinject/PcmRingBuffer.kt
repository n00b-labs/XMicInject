package com.xmicinject

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object PcmRingBuffer {

    private const val TAG = "XMicRing"
    private const val BUFFER_SECONDS = 4
    private const val CAPACITY = WIRE_SAMPLE_RATE_HZ * WIRE_BYTES_PER_SAMPLE * BUFFER_SECONDS
    private const val LOG_INTERVAL_MS = 2_000L

    private val buffer = ByteArray(CAPACITY)
    private val lock = Any()
    private var writePos = 0
    private var available = 0
    private var firstWriteAfterClear = true
    private var lastWriteLogMs = 0L
    private var lastReadLogMs = 0L

    val active: AtomicBoolean = AtomicBoolean(false)

    val bufferedBytes: Int get() = synchronized(lock) { available }

    fun write(src: ByteArray) {
        if (src.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        var isFirstAfterClear = false
        var nowAvailable = 0
        var shouldLog = false
        synchronized(lock) {
            isFirstAfterClear = firstWriteAfterClear
            if (isFirstAfterClear) firstWriteAfterClear = false
            val len = src.size.coerceAtMost(CAPACITY)
            writePos = copyIntoRing(src, src.size - len, len, writePos)
            available = (available + len).coerceAtMost(CAPACITY)
            nowAvailable = available
            shouldLog = nowMs - lastWriteLogMs >= LOG_INTERVAL_MS
            if (shouldLog) lastWriteLogMs = nowMs
        }
        active.set(true)
        if (isFirstAfterClear) {
            Log.i(TAG, "[$nowMs] first write after clear: +${src.size}B available=${nowAvailable}B (${msInBuf(nowAvailable)}ms buffered)")
        }
        if (shouldLog) {
            Log.d(TAG, "[$nowMs] write: +${src.size}B available=${nowAvailable}B (${msInBuf(nowAvailable)}ms buffered)")
        }
    }

    fun read(count: Int): ByteArray? {
        val nowMs = System.currentTimeMillis()
        var result: ByteArray? = null
        var shouldLog = false
        var logAvail = 0
        synchronized(lock) {
            if (available < count) return null
            val out = ByteArray(count)
            val readPos = (writePos - available + CAPACITY) % CAPACITY
            copyOutOfRing(readPos, out, count)
            available -= count
            result = out
            shouldLog = nowMs - lastReadLogMs >= LOG_INTERVAL_MS
            logAvail = available
            if (shouldLog) lastReadLogMs = nowMs
        }
        if (shouldLog) {
            Log.d(TAG, "[$nowMs] read: -${count}B remaining=${logAvail}B (${msInBuf(logAvail)}ms buffered)")
        }
        return result
    }

    fun clear() {
        var discarded = 0
        synchronized(lock) {
            discarded = available
            writePos = 0
            available = 0
            firstWriteAfterClear = true
        }
        active.set(false)
        Log.i(TAG, "[${System.currentTimeMillis()}] cleared: discarded=${discarded}B (${msInBuf(discarded)}ms)")
    }

    private fun msInBuf(bytes: Int): Int = bytes * 1000 / (WIRE_SAMPLE_RATE_HZ * WIRE_BYTES_PER_SAMPLE)

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
