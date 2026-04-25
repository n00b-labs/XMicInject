package com.xmicinject

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CaptureFileWriter {

    private const val TAG: String = "XMicCaptureFile"
    private const val SAMPLE_RATE_HZ: Int = 16_000
    private const val CHANNEL_COUNT: Int = 1
    private const val BITS_PER_SAMPLE: Int = 16
    private const val WAV_HEADER_BYTES: Int = 44

    private val lock: Any = Any()

    private var outputDir: File? = null
    private var activeFile: RandomAccessFile? = null
    private var dataBytesWritten: Int = 0

    fun configure(dataDir: String?) {
        synchronized(lock) {
            closeLocked()
            outputDir = dataDir
                ?.let { path: String -> File(path, "files/xmicinject-captures") }
                ?.apply { mkdirs() }

            val dir: File = outputDir ?: run {
                Log.w(TAG, "Capture disabled: app data directory is unavailable")
                return
            }
            Log.i(TAG, "Capture output directory: ${dir.absolutePath}")
        }
    }

    fun appendPcm16Mono(payload: ByteArray) {
        if (payload.isEmpty()) return
        synchronized(lock) {
            val file: RandomAccessFile = activeFile ?: openLocked() ?: return
            file.seek((WAV_HEADER_BYTES + dataBytesWritten).toLong())
            file.write(payload)
            dataBytesWritten += payload.size
            writeHeaderLocked(file, dataBytesWritten)
        }
    }

    fun reset() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun openLocked(): RandomAccessFile? {
        val dir: File = outputDir ?: return null
        val path = File(dir, "${timestampString()}_uplink.wav")
        return runCatching {
            RandomAccessFile(path, "rw").also { file: RandomAccessFile ->
                dataBytesWritten = 0
                writeHeaderLocked(file, dataBytesWritten)
                activeFile = file
                Log.i(TAG, "Capture file opened: ${path.absolutePath}")
            }
        }.getOrElse { error: Throwable ->
            Log.e(TAG, "Failed to open capture file: ${path.absolutePath}", error)
            null
        }
    }

    private fun closeLocked() {
        runCatching { activeFile?.close() }
            .onFailure { error: Throwable ->
                Log.w(TAG, "Failed to close capture file", error)
            }
        activeFile = null
        dataBytesWritten = 0
    }

    private fun timestampString(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return formatter.format(Date())
    }

    private fun writeHeaderLocked(file: RandomAccessFile, dataSizeBytes: Int) {
        file.seek(0L)
        val byteRate: Int = SAMPLE_RATE_HZ * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)
        val blockAlign: Short = (CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)).toShort()
        val riffChunkSize: Int = dataSizeBytes + (WAV_HEADER_BYTES - 8)

        val header = ByteArray(WAV_HEADER_BYTES)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLe(header, 4, riffChunkSize)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLe(header, 16, 16)
        writeShortLe(header, 20, 1)
        writeShortLe(header, 22, CHANNEL_COUNT.toShort())
        writeIntLe(header, 24, SAMPLE_RATE_HZ)
        writeIntLe(header, 28, byteRate)
        writeShortLe(header, 32, blockAlign)
        writeShortLe(header, 34, BITS_PER_SAMPLE.toShort())
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLe(header, 40, dataSizeBytes)
        file.write(header)
    }

    private fun writeIntLe(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(dst: ByteArray, offset: Int, value: Short) {
        val raw: Int = value.toInt() and 0xFFFF
        dst[offset] = (raw and 0xFF).toByte()
        dst[offset + 1] = ((raw ushr 8) and 0xFF).toByte()
    }
}
