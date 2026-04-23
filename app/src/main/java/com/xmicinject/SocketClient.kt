package com.xmicinject

import android.net.LocalSocket
import android.net.LocalSocketAddress

internal object SocketClient {

    // Must match the socket name in the provider app (e.g. Voimacher's MicIpcServer)
    private const val SOCKET_NAME = "voimacher_mic"
    private const val CHUNK = 4096
    private var started = false

    fun startOnce() {
        if (started) return
        started = true
        val t = Thread {
            while (true) {
                try {
                    runLoop()
                } catch (_: Exception) {
                    PcmRingBuffer.clear()
                }
                Thread.sleep(1000)
            }
        }
        t.isDaemon = true
        t.name = "xmicinject-ipc"
        t.start()
    }

    private fun runLoop() {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
        val buf = ByteArray(CHUNK)
        val stream = socket.inputStream
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            PcmRingBuffer.write(buf, 0, n)
        }
        PcmRingBuffer.clear()
    }
}
