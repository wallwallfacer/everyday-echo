package com.everydayecho.memorykeeper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.everydayecho.memorykeeper.MainActivity
import com.everydayecho.memorykeeper.R
import com.everydayecho.memorykeeper.data.FileRepository
import com.everydayecho.memorykeeper.record.SpeechSegmentRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that runs while the user is awake-and-listening.
 *
 * Spec §14: manual toggle, persistent notification, 16-hour auto-stop.
 */
class MonitoringService : Service() {

    private lateinit var repo: FileRepository
    private var recorder: SpeechSegmentRecorder? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var startedAtMs: Long = 0
    private var autoStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        repo = FileRepository(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        // Spec §14: manual toggle. If Android kills us we don't auto-restart —
        // re-enabling is a deliberate user action.
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (startedAtMs > 0) return
        startedAtMs = SystemClock.elapsedRealtime()
        startInForeground()
        recorder = SpeechSegmentRecorder(
            context = this,
            dirFor = { repo.recordingsDir(it.toLocalDate()) },
            onStatus = { updateStatus(it) },
            onError = { updateStatus(it) },
        ).also { it.start() }

        autoStopJob = scope.launch {
            val limitMs = getMaxHours() * 3600L * 1000L
            delay(limitMs)
            stopMonitoring()
        }
        setRunning(true)
    }

    private fun stopMonitoring() {
        recorder?.stop()
        recorder = null
        autoStopJob?.cancel()
        autoStopJob = null
        startedAtMs = 0
        setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder?.stop()
        scope.cancel()
        setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, MonitoringService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_stop), stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateStatus(text: String) {
        if (startedAtMs == 0L) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun startInForeground() {
        val notification = buildNotification(getString(R.string.notification_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun setRunning(running: Boolean) {
        getPrefs().edit().putBoolean(KEY_RUNNING, running).apply()
    }

    private suspend fun getMaxHours(): Int = runCatching {
        repo.loadProfile().preferences.monitoringMaxHours
    }.getOrDefault(16).coerceIn(1, 24)

    private fun getPrefs(): SharedPreferences =
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    companion object {
        const val ACTION_STOP = "com.everydayecho.memorykeeper.STOP"
        const val STATE_PREFS = "mk_service_state"
        const val KEY_RUNNING = "running"
        private const val CHANNEL_ID = "mk_listening"
        private const val NOTIF_ID = 17

        fun isRunning(context: Context): Boolean = context
            .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RUNNING, false)

        fun start(context: Context) {
            val i = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
