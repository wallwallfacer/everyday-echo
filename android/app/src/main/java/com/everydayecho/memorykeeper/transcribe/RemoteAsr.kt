package com.everydayecho.memorykeeper.transcribe

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.io.File

/** The ASR process died mid-run. [culprit] is the file being recognized, or null if it died loading the model. */
class AsrCrashException(val culprit: String?, val reason: String) : RuntimeException(reason)

/** Client side of [AsrService]: sends files to the ASR process and streams the results back. */
object RemoteAsr {
    private sealed class Event {
        class Result(val path: String, val text: String?) : Event()
        object Done : Event()
        class Error(val message: String) : Event()
        object Died : Event()
    }

    /** Recognizes [files] in the ASR process; [onResult] gets null text for audio that could not be decoded. */
    suspend fun run(context: Context, files: List<File>, onResult: suspend (File, String?) -> Unit) {
        val app = context.applicationContext
        val events = Channel<Event>(Channel.UNLIMITED)
        val replies = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                when (msg.what) {
                    AsrService.MSG_RESULT -> events.trySend(
                        Event.Result(msg.data.getString(AsrService.KEY_PATH).orEmpty(), msg.data.getString(AsrService.KEY_TEXT)),
                    )
                    AsrService.MSG_DONE -> events.trySend(Event.Done)
                    AsrService.MSG_ERROR -> events.trySend(Event.Error(msg.data.getString(AsrService.KEY_ERROR).orEmpty()))
                }
                true
            },
        )
        val conn = object : ServiceConnection {
            var sent = false

            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (sent) return // the system restarted the service after a crash; that run is over
                sent = true
                val m = Message.obtain(null, AsrService.MSG_RUN)
                m.data = Bundle().apply { putStringArrayList(AsrService.KEY_PATHS, ArrayList(files.map { it.absolutePath })) }
                m.replyTo = replies
                runCatching { Messenger(binder).send(m) }.onFailure { events.trySend(Event.Died) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                events.trySend(Event.Died)
            }

            override fun onBindingDied(name: ComponentName) {
                events.trySend(Event.Died)
            }

            override fun onNullBinding(name: ComponentName) {
                events.trySend(Event.Error("无法启动转录进程"))
            }
        }

        val started = System.currentTimeMillis()
        AsrService.javaCrashFile(app).delete()
        check(app.bindService(Intent(app, AsrService::class.java), conn, Context.BIND_AUTO_CREATE)) { "无法启动转录进程" }
        val byPath = files.associateBy { it.absolutePath }
        try {
            for (ev in events) {
                when (ev) {
                    is Event.Result -> byPath[ev.path]?.let { onResult(it, ev.text) }
                    Event.Done -> return
                    is Event.Error -> throw IllegalStateException(ev.message)
                    Event.Died -> {
                        val cur = AsrService.currentFile(app)
                        val culprit = if (cur.exists()) cur.readText().ifBlank { null } else null
                        cur.delete()
                        throw AsrCrashException(culprit, crashReason(app, started))
                    }
                }
            }
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }

    /** Best-effort description of why the ASR process died, from the system's exit records. */
    private suspend fun crashReason(context: Context, since: Long): String {
        delay(1000) // the exit record is written shortly after the process dies
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 30) {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrNull()
                ?.firstOrNull { it.processName.endsWith(":asr") && it.timestamp >= since }
            if (info != null) {
                parts += reasonName(info.reason)
                info.description?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (info.pss > 0) parts += "内存占用约 ${info.pss / 1024} MB"
            }
        }
        val java = AsrService.javaCrashFile(context)
        if (java.exists()) parts += java.readLines().firstOrNull().orEmpty()
        return parts.joinToString(" · ").ifBlank { "转录进程意外退出" }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生代码崩溃"
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统回收"
        ApplicationExitInfo.REASON_SIGNALED -> "被系统信号终止"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被系统终止"
        ApplicationExitInfo.REASON_ANR -> "无响应被系统终止"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "进程初始化失败"
        else -> "退出码 $reason"
    }
}
