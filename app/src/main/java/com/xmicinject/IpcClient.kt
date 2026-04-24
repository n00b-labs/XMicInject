package com.xmicinject

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

// Manages the TCP connection to the audio provider.
//
// Two streams share the single connection:
//   input  → translated PCM from provider → PcmRingBuffer (inject)
//   output ← real mic audio from hooks    ← UplinkSender  (uplink)
internal object IpcClient {

    private const val TAG = "XMicIpcClient"
    private const val HOST = "127.0.0.1"
    private const val PORT = 38673
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_CHUNK = 4096

    @Volatile private var started = false
    @Volatile private var output: OutputStream? = null

    // True while connected. The hook reads this to decide whether to fill silence
    // when the inject buffer is empty (provider connected but no audio yet).
    @Volatile var muteRealMic: Boolean = false
        private set

    // Starts the background IPC thread. Safe to call from multiple packages —
    // only the first call does anything; subsequent calls are ignored.
    fun startOnce() {
        if (started) return
        started = true
        val thread = Thread(::reconnectLoop)
        thread.isDaemon = true   // does not prevent the process from exiting
        thread.name = "xmicinject-ipc"
        thread.start()
    }

    // Sends bytes to the provider over the uplink stream. No-op if not connected.
    // Called from hook threads — write is non-blocking from the caller's perspective
    // because the OS TCP send buffer absorbs the data immediately.
    fun write(data: ByteArray) {
        output?.let { stream ->
            runCatching { stream.write(data) }
                .onFailure { output = null }   // mark disconnected so next write is skipped
        }
    }

    // Runs forever in the background. After each disconnect (or failed connect),
    // cleans up state and waits 1 s before retrying.
    private fun reconnectLoop() {
        while (true) {
            runCatching { connect() }
                .onFailure { Log.w(TAG, "Connection failed: ${it.message}") }
            // Order matters: mute off before clearing buffer so the hook never writes
            // silence into a buffer that is already gone.
            muteRealMic = false
            output = null
            PcmRingBuffer.clear()
            UplinkSender.reset()
            Thread.sleep(1000)
        }
    }

    // Blocks until the provider closes the connection.
    // tcpNoDelay disables Nagle's algorithm — important for audio where small chunks
    // must be delivered immediately rather than batched.
    private fun connect() {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        Log.i(TAG, "Connected to $HOST:$PORT")
        output = socket.outputStream
        muteRealMic = true
        val buf = ByteArray(READ_CHUNK)
        val stream = socket.inputStream
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break   // provider closed the connection
            PcmRingBuffer.write(buf, 0, n)
        }
        Log.i(TAG, "Disconnected from $HOST:$PORT")
    }
}
