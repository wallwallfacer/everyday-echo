package com.everydayecho.memorykeeper.transcribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.everydayecho.memorykeeper.R
import com.everydayecho.memorykeeper.record.AacDecoder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs the native speech recognizer in its own process (":asr", see the manifest), so a
 * native crash or an out-of-memory kill during transcription cannot take the app down.
 * The app talks to it through [RemoteAsr]; this service only turns audio into text and
 * never writes transcripts itself.
 */
class AsrService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { javaCrashFile(this).writeText(e.stackTraceToString().take(4000)) }
            prev?.uncaughtException(t, e)
        }
        messenger = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == MSG_RUN) {
                    val paths = msg.data.getStringArrayList(KEY_PATHS).orEmpty()
                    val reply = msg.replyTo
                    worker.execute { run(paths, reply) }
                }
                true
            },
        )
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    /**
     * Started (in addition to bound) by [RemoteAsr] so the process can run as a foreground
     * service while it works: a plain bound background process is the first thing the
     * system kills when memory is tight, and the recognizer needs ~400 MB.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "语音转文字", NotificationManager.IMPORTANCE_LOW))
            }
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_listening)
                .setContentTitle("正在本地转录语音…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { stopSelf() } // stays alive through the binding, just at lower priority
        return START_NOT_STICKY
    }

    private fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun run(paths: List<String>, reply: Messenger) {
        val current = currentFile(this)
        try {
            current.writeText("") // empty = crashed while loading the model
            val recognizer = newRecognizer(this)
            try {
                for (p in paths) {
                    current.writeText(p)
                    val text = recognize(recognizer, File(p))
                    send(reply, MSG_RESULT) {
                        putString(KEY_PATH, p)
                        putString(KEY_TEXT, text)
                    }
                }
            } finally {
                recognizer.release()
            }
            current.delete()
            leaveForeground()
            send(reply, MSG_DONE) {}
        } catch (e: Throwable) {
            current.delete()
            runCatching { leaveForeground() }
            send(reply, MSG_ERROR) { putString(KEY_ERROR, e.message ?: e.toString()) }
        }
    }

    private fun send(to: Messenger, what: Int, fill: Bundle.() -> Unit) {
        val m = Message.obtain(null, what)
        m.data = Bundle().apply(fill)
        runCatching { to.send(m) }
    }

    /** Returns recognized text, or null if the audio could not be decoded. */
    private fun recognize(recognizer: OfflineRecognizer, file: File): String? {
        val pcm = runCatching { AacDecoder.decode(file) }.getOrNull() ?: return null
        if (pcm.samples.isEmpty()) return null
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(pcm.samples, pcm.sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun newRecognizer(context: Context) = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = ModelManager.modelFile(context).absolutePath,
                    language = "",
                    useInverseTextNormalization = true,
                ),
                tokens = ModelManager.tokensFile(context).absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        ),
    )

    companion object {
        private const val CHANNEL_ID = "asr"
        private const val NOTIF_ID = 2002
        const val MSG_RUN = 1
        const val MSG_RESULT = 2
        const val MSG_DONE = 3
        const val MSG_ERROR = 4
        const val KEY_PATHS = "paths"
        const val KEY_PATH = "path"
        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"

        /** The file being recognized (empty while loading the model); left behind if the process dies. */
        fun currentFile(context: Context) = File(context.cacheDir, "asr_current")

        /** Stack trace of a Java crash in the ASR process, if any. */
        fun javaCrashFile(context: Context) = File(context.cacheDir, "asr_java_crash.txt")
    }
}
