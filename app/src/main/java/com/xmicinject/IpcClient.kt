package com.xmicinject

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal object IpcClient {

    private const val TAG = "XMicIpc"
    private const val HOST = "127.0.0.1"
    private const val PORT = 38_673
    private const val RECONNECT_DELAY_MS = 1_000L
    private const val INJECT_CHUNK_BYTES = 4_096

    val muteRealMic: AtomicBoolean = AtomicBoolean(false)

    private val lock = Any()
    private var uplinkStream: OutputStream? = null

    fun start() {
        val thread = Thread({ runLoop() }, "xmicinject-ipc")
        thread.isDaemon = true
        thread.start()
    }

    @Volatile private var lastUplinkLogMs: Long = 0L

    fun write(data: ByteArray) {
        synchronized(lock) { uplinkStream }?.runCatching { write(data) }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastUplinkLogMs >= 2_000L) {
            lastUplinkLogMs = nowMs
            Log.d(TAG, "[$nowMs] uplink write: ${data.size}B")
        }
    }

    private fun runLoop() {
        while (true) {
            runCatching { connect() }
                .onFailure { Log.d(TAG, "Disconnected: ${it.message}") }
            onDisconnect()
            Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    private fun connect() {
        Socket(HOST, PORT).use { socket ->
            Log.i(TAG, "Connected to $HOST:$PORT")
            synchronized(lock) { uplinkStream = socket.getOutputStream() }
            muteRealMic.set(true)
            readInjectStream(socket)
        }
    }

    private fun readInjectStream(socket: Socket) {
        val input = socket.getInputStream()
        val chunk = ByteArray(INJECT_CHUNK_BYTES)
        var firstChunk = true
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (firstChunk) {
                firstChunk = false
                Log.i(TAG, "[${System.currentTimeMillis()}] first inject chunk received: ${n}B")
            }
            PcmRingBuffer.write(chunk.copyOf(n))
        }
    }

    private fun onDisconnect() {
        synchronized(lock) { uplinkStream = null }
        muteRealMic.set(false)
        PcmRingBuffer.clear()
        UplinkSender.reset()
        Log.i(TAG, "Disconnected — passthrough restored")
    }
}
