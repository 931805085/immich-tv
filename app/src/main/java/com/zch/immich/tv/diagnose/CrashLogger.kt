package com.zch.immich.tv.diagnose

import android.content.Context
import android.os.Build
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * 崩溃记录。
 *
 * 电视上看不到 logcat，App 一崩屏幕就回到桌面，只能干等。所以：
 *  1. 把完整栈写到公共可读的文件（/sdcard/Android/data/<包名>/files/immich_tv_crash.log）；
 *  2. 同时暴露成 [report]，由内置 HTTP 服务器的 GET /debug 路由读出来，
 *     用手机浏览器或 curl 就能看到上次的崩溃原因。
 */
object CrashLogger {

    private const val LOG_FILE = "immich_tv_crash.log"

    @Volatile private var lastReport: String = ""

    val report: String
        get() = lastReport

    /** 必须在 Application / Activity 创建时尽早调用 */
    fun install(context: Context) {
        // 注意：Android 上的接口方法叫 uncaughtException（JDK 里叫 uncaught），别写错
        val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            writeLog(dir, thread, throwable)
            // 交回系统默认处理器，让系统走正常的崩溃流程
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeLog(dir: File, thread: Thread, throwable: Throwable): String {
        val text = buildReport(thread, throwable)
        lastReport = text
        try {
            File(dir, LOG_FILE).writeText(text)
        } catch (_: Exception) {
            // 写文件失败也不影响崩溃流程
        }
        return text
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String = buildString {
        append("=== Immich TV 崩溃记录 ===\n")
        append("时间: ").append(DateFormat.getDateTimeInstance().format(Date())).append("\n")
        append("线程: ").append(thread.name)
            .append(" (").append(if (thread.isAlive) "alive" else "dead").append(")\n")
        append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        append("\n--- 异常 ---\n")
        append(throwable.stackTraceToString())

        // 链式异常一并展开，否则常常只看到最外层那层无意义的包装
        var cause = throwable.cause
        while (cause != null && cause !== throwable) {
            append("\n--- Caused by ---\n")
            append(cause.stackTraceToString())
            cause = cause.cause
        }
    }
}
