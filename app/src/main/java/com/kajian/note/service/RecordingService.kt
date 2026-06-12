package com.kajian.note.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kajian.note.R
import com.kajian.note.ui.MainActivity
import kotlinx.coroutines.*

/**
 * RecordingService — ForegroundService RINGAN.
 * Tugasnya HANYA: WakeLock + Persistent Notification + Timer broadcast.
 * AudioRecordManager & ContinuousGroqRecorder tetap di Fragment (tidak ada race condition).
 */
class RecordingService : Service() {

    companion object {
        private const val TAG        = "RecordingService"
        private const val CHANNEL_ID = "kajian_recording"
        private const val NOTIF_ID   = 1001

        // Actions
        const val ACTION_START_REKAM   = "START_REKAM"
        const val ACTION_START_GROQ    = "START_GROQ"
        const val ACTION_START_SPEAKER = "START_SPEAKER"
        const val ACTION_STOP          = "STOP_RECORDING"

        // Extras
        const val EXTRA_MODE           = "mode"

        // Timer broadcast (hanya untuk UI sync)
        const val BROADCAST_TICK       = "com.kajian.note.RECORDING_TICK"
        const val EXTRA_ELAPSED_MS     = "elapsed_ms"

        @Volatile var isRunning = false
            private set
        @Volatile var currentMode = ""
            private set

        fun startRekam(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_START_REKAM
                putExtra(EXTRA_MODE, ACTION_START_REKAM)
            })

        fun startGroq(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_START_REKAM
                putExtra(EXTRA_MODE, ACTION_START_GROQ)
            })

        fun startSpeaker(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_START_REKAM
                putExtra(EXTRA_MODE, ACTION_START_SPEAKER)
            })

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_STOP
            })
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var startTimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        isRunning = true
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: intent?.action ?: ACTION_START_REKAM
        currentMode = mode

        when (intent?.action) {
            ACTION_START_REKAM, ACTION_START_GROQ, ACTION_START_SPEAKER -> {
                startTimeMs = System.currentTimeMillis()
                val title = when (mode) {
                    ACTION_START_GROQ    -> "⚡ Merekam (Groq)..."
                    ACTION_START_SPEAKER -> "👥 Multi Speaker merekam..."
                    else                 -> "🔴 Merekam audio..."
                }
                startForeground(NOTIF_ID, buildNotif(title, "Rekaman aman meski layar off"))
                startTimer()
                Log.d(TAG, "Recording started, mode=$mode")
            }
            ACTION_STOP -> {
                Log.d(TAG, "Service stop requested")
                stopSelf()
            }
        }
        return START_NOT_STICKY  // Jangan restart otomatis — fragment yang kontrol lifecycle
    }

    // ── Timer broadcast ───────────────────────────────────────────────────────

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                updateNotificationTimer(elapsed)
                sendBroadcast(Intent(BROADCAST_TICK).apply {
                    putExtra(EXTRA_ELAPSED_MS, elapsed)
                })
                delay(1000)
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun updateNotificationTimer(elapsedMs: Long) {
        val mm = (elapsedMs / 60000).toString().padStart(2, '0')
        val ss = ((elapsedMs % 60000) / 1000).toString().padStart(2, '0')
        val title = when (currentMode) {
            ACTION_START_GROQ    -> "⚡ Merekam (Groq) $mm:$ss"
            ACTION_START_SPEAKER -> "👥 Multi Speaker $mm:$ss"
            else                 -> "🔴 Merekam $mm:$ss"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(title, "Tap untuk kembali ke app"))
    }

    private fun buildNotif(title: String, text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_stop, "⏹ Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "KajianNote Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifikasi aktif saat merekam kajian"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KajianNote::RecordWakeLock")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        Log.d(TAG, "WakeLock acquired")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentMode = ""
        timerJob?.cancel()
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
        Log.d(TAG, "Service destroyed, WakeLock released")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
