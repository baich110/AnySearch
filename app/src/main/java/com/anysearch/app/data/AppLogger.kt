package com.anysearch.app.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 强大的持久化日志系统
 * - 内存环形缓冲 + 文件持久化（闪退/被杀也在）
 * - 全局崩溃捕获（UncaughtExceptionHandler）写入崩溃日志
 * - 时间戳 + 线程 + 级别 + 标签
 * - 可导出完整日志文本，供复制/分享调试
 */
object AppLogger {

    const val LEVEL_VERBOSE = 0
    const val LEVEL_DEBUG = 1
    const val LEVEL_INFO = 2
    const val LEVEL_WARN = 3
    const val LEVEL_ERROR = 4
    const val LEVEL_CRASH = 5

    /** 内存保留的最大条数 */
    private const val MAX_MEMORY_ENTRIES = 2000

    /** 单文件最大字节数，超过则滚动 */
    private const val MAX_FILE_BYTES = 512 * 1024  // 512KB

    /** 最多保留的日志文件数 */
    private const val MAX_LOG_FILES = 5

    /** 崩溃标记文件名（用于检测上次是否异常退出） */
    private const val CRASH_MARKER_FILE = "last_crash_marker.txt"

    private data class Entry(
        val timestamp: Long,
        val uptimeMs: Long,
        val level: Int,
        val tag: String,
        val thread: String,
        val message: String
    )

    private val memory = CopyOnWriteArrayList<Entry>()
    private val hasInit = AtomicBoolean(false)

    @Volatile
    private var logDir: File? = null

    private val levelName = arrayOf("V", "D", "I", "W", "E", "C")
    private val tagMaxLen = 18

    /** 初始化日志目录（应在 Application/MainActivity onCreate 最早调用） */
    fun init(context: Context) {
        if (hasInit.getAndSet(true)) return
        val dir = File(context.filesDir, "logs")
        try {
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
            installCrashHandler()
            // 检测上次是否异常退出
            checkPreviousCrash(dir)
            log(LEVEL_INFO, "AppLogger", "日志系统已初始化，目录=${dir.absolutePath}")
            logDeviceInfo()
        } catch (e: Exception) {
            // 初始化失败不阻塞应用
            android.util.Log.e("AppLogger", "日志初始化失败", e)
        }
    }

    /**
     * 检测上次是否异常退出。
     * 正常流程：onDestroy 时 markNormalExit() 删除标记。
     * 若启动时发现标记还在 => 上次是被杀/崩溃，补记一条。
     */
    private fun checkPreviousCrash(dir: File) {
        try {
            val marker = File(dir, CRASH_MARKER_FILE)
            if (marker.exists()) {
                val lastInfo = marker.readText().trim()
                log(LEVEL_CRASH, "AppLogger", "检测到上次异常退出！$lastInfo")
                marker.delete()
            } else {
                // 标记本次运行开始（若正常退出会由 markNormalExit 删除）
                marker.writeText("启动时间=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 标记本次正常退出（正常流程调用，删除崩溃标记） */
    fun markNormalExit() {
        try {
            logDir?.let { File(it, CRASH_MARKER_FILE)?.delete() }
        } catch (e: Exception) { }
    }

    /** 记录设备信息，方便排查机型相关 bug */
    private fun logDeviceInfo() {
        log(LEVEL_INFO, "Device", "Build=" + Build.FINGERPRINT)
        log(LEVEL_INFO, "Device", "Model=${Build.MODEL}, SDK=${Build.VERSION.SDK_INT}, Release=${Build.VERSION.RELEASE}")
        log(LEVEL_INFO, "Device", "Board=${Build.BOARD}, Brand=${Build.BRAND}, Device=${Build.DEVICE}")
    }

    /** 全局崩溃捕获：任何未捕获异常都会写入日志文件（同步，立即落盘） */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val sb = StringBuilder()
                sb.append("===== CRASH =====\n")
                sb.append("Thread: ${thread.name}\n")
                sb.append("Time: $ts\n")
                sb.append("Uptime: ").append(formatUptime(SystemClock.elapsedRealtime())).append("\n")
                sb.append(throwable.toString()).append("\n")
                for (ste in throwable.stackTrace) {
                    sb.append("    at ").append(ste).append("\n")
                }
                throwable.cause?.let { cause ->
                    sb.append("Caused by: ").append(cause).append("\n")
                    for (ste in cause.stackTrace) {
                        sb.append("    at ").append(ste).append("\n")
                    }
                }
                val dir = logDir
                if (dir != null) {
                    try {
                        // 1) 写崩溃标记（下次启动可检测到异常退出）
                        File(dir, CRASH_MARKER_FILE).writeText(
                            "崩溃时间=$ts\n崩溃线程=${thread.name}\n异常=${throwable.toString()}"
                        )
                        // 2) 崩溃前内存日志立即落盘（同步）
                        val memText = buildCurrentMemoryText(clearAfter = false)
                        File(dir, currentLogFileName()).appendText(memText)
                        // 3) 写崩溃堆栈
                        File(dir, currentLogFileName()).appendText(
                            "MM-dd HH:mm:ss.SSS [C][${thread.name.take(16)}][CRASH] $sb\n"
                        )
                    } catch (e2: Exception) {
                        android.util.Log.e("AppLogger", "崩溃落盘失败", e2)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppLogger", "崩溃日志写入失败", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun v(tag: String, msg: String) = log(LEVEL_VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(LEVEL_DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LEVEL_INFO, tag, msg)
    fun w(tag: String, msg: String) = log(LEVEL_WARN, tag, msg)
    fun e(tag: String, msg: String) = log(LEVEL_ERROR, tag, msg)
    fun e(tag: String, msg: String, throwable: Throwable?) {
        if (throwable != null) log(LEVEL_ERROR, tag, "$msg\n${stackTraceToString(throwable)}")
        else log(LEVEL_ERROR, tag, msg)
    }

    @Synchronized
    fun log(level: Int, tag: String, message: String) {
        val now = System.currentTimeMillis()
        val entry = Entry(
            timestamp = now,
            uptimeMs = SystemClock.elapsedRealtime(),
            level = level,
            tag = tag,
            thread = Thread.currentThread().name,
            message = message
        )
        memory.add(entry)
        // 内存上限，移除最旧的
        while (memory.size > MAX_MEMORY_ENTRIES) {
            memory.removeAt(0)
        }
        // 同步写文件（确保崩溃时日志已落盘，不依赖异步线程）
        val dir = logDir
        if (dir != null) {
            try {
                appendToFile(dir, entry)
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    /** 立即把内存日志全部落盘（崩溃前/导出前调用） */
    fun flushToDiskNow() {
        val dir = logDir ?: return
        try {
            val file = File(dir, currentLogFileName())
            file.appendText(buildCurrentMemoryText(clearAfter = true) + "\n")
        } catch (e: Exception) {
            android.util.Log.e("AppLogger", "flush失败", e)
        }
    }

    /** 追加单条到文件，带滚动 */
    private fun appendToFile(dir: File, entry: Entry) {
        try {
            var file = File(dir, currentLogFileName())
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                // 滚动：删除最旧的备份，重命名当前
                val backups = dir.listFiles { f -> f.name.startsWith("app_log_") }?.sortedByDescending { it.name } ?: emptyList()
                if (backups.size >= MAX_LOG_FILES) {
                    backups.lastOrNull()?.delete()
                }
                val newName = "app_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
                file.renameTo(File(dir, newName))
                file = File(dir, currentLogFileName())
            }
            file.appendText(formatEntry(entry) + "\n")
        } catch (e: Exception) {
            // 写文件失败不影响主流程
        }
    }

    private fun currentLogFileName(): String = "app_log_current.txt"

    private fun formatEntry(e: Entry): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(e.timestamp))
        val tag = e.tag.padEnd(tagMaxLen).take(tagMaxLen)
        val thread = e.thread.take(16).padEnd(16)
        return "$ts [${levelName.getOrElse(e.level) { "?" }}][$thread][$tag] ${e.message}"
    }

    /** 从内存 + 文件构建完整日志文本 */
    fun buildFullLogText(): String {
        val sb = StringBuilder()
        sb.append("===== AnySearch 日志导出 =====\n")
        sb.append("导出时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())).append("\n")
        sb.append("应用版本: ").append(getAppVersion()).append("\n")
        sb.append("--------------------------------\n")
        // 崩溃标记状态
        val dir = logDir
        if (dir != null) {
            val marker = File(dir, CRASH_MARKER_FILE)
            sb.append("上次退出状态: ").append(
                if (marker.exists()) "异常(标记存在，可能崩溃/被杀，本次启动已补记)" else "正常"
            ).append("\n")
            val historyFiles = dir.listFiles { f -> f.name.startsWith("app_log_") && f.name != "app_log_current.txt" }
                ?.sortedBy { it.name } ?: emptyList()
            for (f in historyFiles) {
                sb.append("----- 历史文件: ${f.name} -----\n")
                try { sb.append(f.readText()) } catch (e: Exception) { sb.append("(读取失败)") }
                sb.append("\n")
            }
        }
        // 内存日志
        sb.append("----- 当前会话内存日志 -----\n")
        memory.forEach { sb.append(formatEntry(it)).append("\n") }
        return sb.toString()
    }

    private fun buildCurrentMemoryText(clearAfter: Boolean): String {
        val sb = StringBuilder()
        memory.forEach { sb.append(formatEntry(it)).append("\n") }
        if (clearAfter) memory.clear()
        return sb.toString()
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    private fun formatUptime(ms: Long): String {
        val sec = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    private fun getAppVersion(): String = "1.2.0"
}