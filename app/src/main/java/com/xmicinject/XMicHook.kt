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
import java.util.concurrent.atomic.AtomicBoolean

class XMicHook : IXposedHookLoadPackage {

    private companion object {
        private const val TAG = "XMicHook"
        private val SKIP_PACKAGES = setOf("com.xmicinject", "android")
        private val MIC_SOURCES = setOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED
        )
    }

    // Called by LSPosed once per loaded package. We skip ourselves, the Android framework,
    // and system UIDs to avoid hooking processes that don't do VoIP and can't be injected safely.
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val uid = lpparam.appInfo?.uid ?: Process.INVALID_UID
        if (lpparam.packageName in SKIP_PACKAGES) return
        if (uid in 0 until Process.FIRST_APPLICATION_UID) return

        Log.i(TAG, "Hook loaded: ${lpparam.packageName}")
        IpcClient.startOnce()

        // Flags so we log inject/mute only once per package, not on every read() call.
        val injectionLogged = AtomicBoolean(false)
        val muteLogged = AtomicBoolean(false)

        hookByteArray(lpparam.classLoader, lpparam.packageName, injectionLogged, muteLogged)
        hookShortArray(lpparam.classLoader, lpparam.packageName, injectionLogged, muteLogged)
        hookByteBuffer(lpparam.classLoader, lpparam.packageName, injectionLogged, muteLogged)
    }

    // --- Hooks ---
    //
    // AudioRecord.read() has three overload families: byte[], short[], ByteBuffer.
    // Each is hooked separately because the buffer type and injection path differ.
    // afterHookedMethod fires AFTER Android fills the buffer with real mic audio,
    // so we can read the original data for uplink before overwriting it with inject.

    private fun hookByteArray(cl: ClassLoader, pkg: String, injectionLogged: AtomicBoolean, muteLogged: AtomicBoolean) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                val buf = param.args[0] as ByteArray
                val off = param.args[1] as Int
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)

                if (isMicSource(record)) {
                    UplinkSender.send(buf, off, count, hz, channelCount(record), streamId(record))
                }

                val replaced = injectBytes(buf, off, count, hz)
                if (replaced && injectionLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "Injection active: pkg=$pkg sampleRate=${hz}Hz")
                }
                if (!replaced && IpcClient.muteRealMic) {
                    buf.fill(0, off, off + count)
                    if (muteLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Real mic muted: pkg=$pkg (connected but buffer empty)")
                    }
                }
            }
        }
        hookRead(cl, hook, ByteArray::class.java, Int::class.java, Int::class.java)
        hookRead(cl, hook, ByteArray::class.java, Int::class.java, Int::class.java, Int::class.java)
    }

    private fun hookShortArray(cl: ClassLoader, pkg: String, injectionLogged: AtomicBoolean, muteLogged: AtomicBoolean) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                val buf = param.args[0] as ShortArray
                val off = param.args[1] as Int
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)

                if (isMicSource(record)) {
                    val bytes = AudioResampler.shortsToBytes(buf.copyOfRange(off, off + count))
                    UplinkSender.send(bytes, 0, bytes.size, hz, channelCount(record), streamId(record))
                }

                val replaced = injectShorts(buf, off, count, hz)
                if (replaced && injectionLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "Injection active: pkg=$pkg sampleRate=${hz}Hz (short[])")
                }
                if (!replaced && IpcClient.muteRealMic) {
                    buf.fill(0, off, off + count)
                    if (muteLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Real mic muted: pkg=$pkg (connected but buffer empty)")
                    }
                }
            }
        }
        hookRead(cl, hook, ShortArray::class.java, Int::class.java, Int::class.java)
        hookRead(cl, hook, ShortArray::class.java, Int::class.java, Int::class.java, Int::class.java)
    }

    private fun hookByteBuffer(cl: ClassLoader, pkg: String, injectionLogged: AtomicBoolean, muteLogged: AtomicBoolean) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                val buffer = param.args[0] as? ByteBuffer ?: return
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)

                val start = (buffer.position() - count).coerceAtLeast(0)
                val bytes = ByteArray(count)
                buffer.duplicate().apply { position(start); limit(start + count) }.get(bytes)

                if (isMicSource(record)) {
                    UplinkSender.send(bytes, 0, count, hz, channelCount(record), streamId(record))
                }

                val replaced = injectBytes(bytes, 0, count, hz)
                if (replaced) {
                    buffer.duplicate().apply { position(start); limit(start + count) }.put(bytes)
                    if (injectionLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Injection active: pkg=$pkg sampleRate=${hz}Hz (ByteBuffer)")
                    }
                } else if (IpcClient.muteRealMic) {
                    val view = buffer.duplicate().apply { position(start); limit(start + count) }
                    while (view.hasRemaining()) view.put(0)
                    if (muteLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Real mic muted: pkg=$pkg (connected but buffer empty)")
                    }
                }
            }
        }
        hookRead(cl, hook, ByteBuffer::class.java, Int::class.java)
        hookRead(cl, hook, ByteBuffer::class.java, Int::class.java, Int::class.java)
    }

    // --- Inject helpers ---

    // Reads PCM from the ring buffer (stored at 16 kHz) and writes it into dst.
    // Resamples if the app's AudioRecord uses a different sample rate.
    private fun injectBytes(dst: ByteArray, offset: Int, count: Int, sampleRateHz: Int): Boolean {
        if (sampleRateHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            return PcmRingBuffer.readBytes(dst, offset, count)
        }
        val srcBytes = AudioResampler.sourceBytesNeeded(count, sampleRateHz, PcmRingBuffer.SAMPLE_RATE_HZ)
        val tmp = ByteArray(srcBytes)
        if (!PcmRingBuffer.readBytes(tmp, 0, srcBytes)) return false
        val out = AudioResampler.resampleBytes(tmp, 0, srcBytes, PcmRingBuffer.SAMPLE_RATE_HZ, sampleRateHz)
        System.arraycopy(out, 0, dst, offset, minOf(out.size, count))
        return true
    }

    private fun injectShorts(dst: ShortArray, offset: Int, count: Int, sampleRateHz: Int): Boolean {
        if (sampleRateHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            return PcmRingBuffer.readShorts(dst, offset, count)
        }
        val srcCount = AudioResampler.sourceSamplesNeeded(count, sampleRateHz, PcmRingBuffer.SAMPLE_RATE_HZ)
        val tmp = ShortArray(srcCount)
        if (!PcmRingBuffer.readShorts(tmp, 0, srcCount)) return false
        val out = AudioResampler.resampleShorts(tmp, 0, srcCount, PcmRingBuffer.SAMPLE_RATE_HZ, sampleRateHz)
        System.arraycopy(out, 0, dst, offset, minOf(out.size, count))
        return true
    }

    // --- AudioRecord accessors ---

    // runCatching guards against reflection failures in unusual system configurations.
    // Falls back to 16 kHz so inject still works even if sampleRate can't be read.
    private fun sampleRate(record: AudioRecord): Int {
        val raw = runCatching { record.sampleRate }.getOrElse {
            Log.w(TAG, "sampleRate() threw: ${it.message} — falling back to ${PcmRingBuffer.SAMPLE_RATE_HZ}")
            -1
        }
        val resolved = raw.takeIf { it > 0 } ?: PcmRingBuffer.SAMPLE_RATE_HZ
        if (raw != resolved) {
            Log.w(TAG, "sampleRate fallback: raw=$raw resolved=$resolved")
        }
        return resolved
    }

    private fun channelCount(record: AudioRecord): Int {
        return runCatching { record.channelCount }.getOrDefault(1).takeIf { it > 0 } ?: 1
    }

    // Uses object identity hash as a stable ID for this AudioRecord instance.
    // Lets UplinkSender track which instance is actively capturing voice.
    private fun streamId(record: AudioRecord): Long {
        return System.identityHashCode(record).toLong()
    }

    // We only send uplink for mic-type sources. AudioRecord can also be used for
    // playback monitoring (REMOTE_SUBMIX) or internal routing — we don't want those.
    private fun isMicSource(record: AudioRecord): Boolean {
        val source = runCatching { record.audioSource }.getOrDefault(-1)
        return source in MIC_SOURCES
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
