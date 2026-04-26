package com.xmicinject

import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class XMicHook : IXposedHookLoadPackage {

    private companion object {
        private const val TAG = "XMicHook"
        private val SKIP_PACKAGES = setOf("com.xmicinject", "android", "com.voimacher")
        private val TARGET_PACKAGES = setOf("org.telegram.messenger")
        private val UPLINK_SOURCES = setOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED
        )
        private const val READ_LOG_INTERVAL_MS: Long = 2_000L

        private fun msInBuf(bytes: Int): Int = bytes * 1000 / (WIRE_SAMPLE_RATE_HZ * WIRE_BYTES_PER_SAMPLE)
    }

    private val seenReadPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastReadLogMsByPath: MutableMap<String, Long> = ConcurrentHashMap()

    @Volatile private var firstInjectDone = false
    @Volatile private var lastInjectLogMs = 0L

    // Called by LSPosed once per loaded package. We skip ourselves, the Android framework,
    // and system UIDs to avoid hooking processes that don't do VoIP and can't be injected safely.
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val uid = lpparam.appInfo?.uid ?: Process.INVALID_UID
        if (lpparam.packageName in SKIP_PACKAGES) return
        if (lpparam.packageName !in TARGET_PACKAGES) return
        if (uid in 0 until Process.FIRST_APPLICATION_UID) return

        Log.i(TAG, "Hook loaded: ${lpparam.packageName}")
        UplinkSender.configureCaptureOutput(lpparam.appInfo?.dataDir)
        IpcClient.start()

        hookByteBuffer(lpparam.classLoader, lpparam.packageName)
    }

    private fun hookByteBuffer(cl: ClassLoader, pkg: String) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                val buffer = param.args[0] as? ByteBuffer ?: return
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)
                val channels = channelCount(record)
                val source = audioSource(record)
                val streamId = streamId(record)
                logReadInvocation(
                    family = "ByteBuffer",
                    packageName = pkg,
                    streamId = streamId,
                    audioSource = source,
                    sampleRateHz = hz,
                    channelCount = channels,
                    count = count,
                    countUnit = "bytes"
                )

                val start = (buffer.position() - count).coerceAtLeast(0)
                val bytes = ByteArray(count)
                buffer.duplicate().apply { position(start); limit(start + count) }.get(bytes)

                if (isUplinkSource(source)) {
                    UplinkSender.send(
                        buf = bytes,
                        offset = 0,
                        count = count,
                        sampleRateHz = hz,
                        channelCount = channels,
                        streamId = streamId,
                        packageName = pkg,
                        audioSource = source
                    )
                } else {
                    UplinkSender.logIgnoredSource(
                        packageName = pkg,
                        streamId = streamId,
                        audioSource = source,
                        sampleRateHz = hz,
                        channelCount = channels
                    )
                }

                if (IpcClient.muteRealMic.get() && isUplinkSource(source)) {
                    inject(buffer, start, count, hz)
                }
            }
        }
        hookRead(cl, hook, ByteBuffer::class.java, Int::class.java, Int::class.java)
    }

    private fun inject(buffer: ByteBuffer, start: Int, count: Int, appSampleRateHz: Int) {
        val nowMs = System.currentTimeMillis()
        val sourceBytesNeeded = count * WIRE_SAMPLE_RATE_HZ / appSampleRateHz
        val bufferedBefore = PcmRingBuffer.bufferedBytes
        val ringBytes = PcmRingBuffer.read(sourceBytesNeeded)
        val injecting = ringBytes != null

        if (injecting && !firstInjectDone) {
            firstInjectDone = true
            Log.i(TAG, "[$nowMs] FIRST INJECT: bufferedBefore=${bufferedBefore}B (${msInBuf(bufferedBefore)}ms) consumed=${sourceBytesNeeded}B appHz=$appSampleRateHz")
        }
        if (nowMs - lastInjectLogMs >= 2_000L) {
            lastInjectLogMs = nowMs
            Log.d(TAG, "[$nowMs] inject: bufferedBefore=${bufferedBefore}B (${msInBuf(bufferedBefore)}ms) injecting=$injecting need=${sourceBytesNeeded}B")
        }

        val injectBytes = if (ringBytes != null) {
            AudioResampler.resampleBytes(ringBytes, 0, ringBytes.size, WIRE_SAMPLE_RATE_HZ, appSampleRateHz)
        } else {
            ByteArray(count)
        }
        val view = buffer.duplicate().apply { position(start); limit(start + count) }
        view.put(injectBytes, 0, count.coerceAtMost(injectBytes.size))
        if (injectBytes.size < count) {
            while (view.hasRemaining()) view.put(0)
        }
    }

    // --- AudioRecord accessors ---

    // runCatching guards against reflection failures in unusual system configurations.
    // Falls back to 16 kHz so capture still works even if sampleRate can't be read.
    private fun sampleRate(record: AudioRecord): Int {
        val raw = runCatching { record.sampleRate }.getOrElse {
            Log.w(TAG, "sampleRate() threw: ${it.message} -- falling back to ${UplinkSender.OUTPUT_SAMPLE_RATE_HZ}")
            -1
        }
        val resolved = raw.takeIf { it > 0 } ?: UplinkSender.OUTPUT_SAMPLE_RATE_HZ
        if (raw != resolved) {
            Log.w(TAG, "sampleRate fallback: raw=$raw resolved=$resolved")
        }
        return resolved
    }

    private fun channelCount(record: AudioRecord): Int {
        return runCatching { record.channelCount }.getOrDefault(1).takeIf { it > 0 } ?: 1
    }

    private fun audioSource(record: AudioRecord): Int {
        return runCatching { record.audioSource }.getOrDefault(-1)
    }

    // Uses object identity hash as a stable ID for this AudioRecord instance.
    // Lets UplinkSender track which instance is actively capturing voice.
    private fun streamId(record: AudioRecord): Long {
        return System.identityHashCode(record).toLong()
    }

    // We only send uplink for mic-type sources. AudioRecord can also be used for
    // playback monitoring (REMOTE_SUBMIX) or internal routing — we don't want those.
    private fun isUplinkSource(audioSource: Int): Boolean {
        return audioSource in UPLINK_SOURCES
    }

    private fun audioSourceName(audioSource: Int): String {
        return when (audioSource) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            else -> "UNKNOWN($audioSource)"
        }
    }

    private fun logReadInvocation(
        family: String,
        packageName: String,
        streamId: Long,
        audioSource: Int,
        sampleRateHz: Int,
        channelCount: Int,
        count: Int,
        countUnit: String
    ) {
        val pathKey = "$family:$streamId"
        val nowMs = System.currentTimeMillis()
        if (seenReadPaths.add(pathKey)) {
            Log.i(
                TAG,
                "AudioRecord.read($family) observed: pkg=$packageName streamId=$streamId " +
                    "source=${audioSourceName(audioSource)} sampleRate=${sampleRateHz}Hz " +
                    "ch=$channelCount count=$count $countUnit"
            )
            lastReadLogMsByPath[pathKey] = nowMs
            return
        }

        val lastLogMs = lastReadLogMsByPath[pathKey] ?: 0L
        if ((nowMs - lastLogMs) < READ_LOG_INTERVAL_MS) return
        lastReadLogMsByPath[pathKey] = nowMs
        Log.d(
            TAG,
            "AudioRecord.read($family) active: pkg=$packageName streamId=$streamId " +
                "source=${audioSourceName(audioSource)} sampleRate=${sampleRateHz}Hz " +
                "ch=$channelCount count=$count $countUnit"
        )
    }

    // --- Hook registration ---

    // findAndHookMethod expects (className, classLoader, methodName, ...paramTypes, callback).
    // We build that array by appending the hook object after the param type list.
    private fun hookRead(cl: ClassLoader, hook: XC_MethodHook, vararg types: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.media.AudioRecord", cl, "read",
                *(types.toList() + hook).toTypedArray()
            )
        }.onFailure {
            XposedBridge.log("XMicHook failed to hook read(${types.joinToString { it.simpleName }}): ${it.message}")
        }
    }
}
