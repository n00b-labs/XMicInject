package com.xmicinject

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XMicHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.xmicinject") return
        SocketClient.startOnce()
        hookReadByteArray(lpparam.classLoader)
        hookReadShortArray(lpparam.classLoader)
    }

    private fun hookReadByteArray(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.media.AudioRecord", cl,
            "read",
            ByteArray::class.java, Int::class.java, Int::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val count = param.result as? Int ?: return
                    if (count <= 0) return
                    val buf = param.args[0] as ByteArray
                    val off = param.args[1] as Int
                    PcmRingBuffer.readBytes(buf, off, count)
                }
            }
        )
    }

    private fun hookReadShortArray(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.media.AudioRecord", cl,
            "read",
            ShortArray::class.java, Int::class.java, Int::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val count = param.result as? Int ?: return
                    if (count <= 0) return
                    val buf = param.args[0] as ShortArray
                    val off = param.args[1] as Int
                    PcmRingBuffer.readShorts(buf, off, count)
                }
            }
        )
    }
}
