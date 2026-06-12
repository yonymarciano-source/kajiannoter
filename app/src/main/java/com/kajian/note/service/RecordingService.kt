package com.kajian.note.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kajian.note.R
import com.kajian.note.ui.MainActivity
import com.kajian.note.utils.AudioRecordManager
import com.kajian.note.utils.ContinuousGroqRecorder
import kotlinx.coroutines.*
import java.io.File

/**
 * RecordingService — ForegroundService untuk semua mode recording.
 * Menjaga recording tetap hidup saat layar off / app di-background.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG           = "RecordingService"
        private const val CHANNEL_ID    = "kajian_recording"
        private const val NOTIF_ID      = 1001

        const val ACTION_START_REKAM    = "START_REKAM"
        const val ACTION_START_GROQ     = "START_GROQ"
        const val ACTION_START_SPEAKER  = "START_SPEAKER"
        const val ACTION_STOP           = "STOP_RECORDING"

        const val EXTRA_LANGUAGE        = "language"
        const val EXTRA_CACHE_DIR       = "cache_dir"

        const val BROADCAST_STATE       = "com.kajian.note.RECORDING_STATE"
        const val EXTRA_STATE           = "state"
        const val EXTRA_ELAPSED_MS      = "elapsed_ms"
        const val EXTRA_CHUNK_TEXT      = "chunk_text"
        const val EXTRA_AUDIO_PATH      = "audio_path"
        const val EXTRA_FULL_TEXT       = "full_text"

        const val STATE_STARTED         = "STARTED"
        const val STATE_TICK            = "TICK"
        const val STATE_CHUNK           = "CHUNK"
        const val STATE_STOPPED         = "STOPPED"

        @Volatile var isRunning = false
            private set

        fun startRekam(ctx: Context, language: String) =
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_START_REKAM
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_CACHE_DIR, ctx.cacheDir.absolutePath)
            })

        fun startGroq(ctx: Context, language: String) =
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_START_GROQ
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_CACHE_DIR, ctx.cacheDir.absolutePath)
            })

        fun startSpeaker(ctx: Context, language: String) =
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_START_SPEAKER
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_CACHE_DIR, ctx.cacheDir.absolutePath)
            })

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, RecordingService::class.java).apply {
                action = ACTION_STOP
            })
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioRec: AudioRecordManager? = null
    private var continuousRec: ContinuousGroqRecorder? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var startTimeMs = 0L
    private var currentMode = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lang      = intent?.getStringExtra(EXTRA_LANGUAGE) ?: "id"
        val cachePath = intent?.getStringExtra(EXTRA_CACHE_DIR) ?: cacheDir.absolutePath
        val cacheDir  = File(cachePath)

        when (intent?.action) {
            ACTION_START_REKAM -> {
                currentMode = ACTION_START_REKAM
                startForegroundNotification("🔴 Merekam audio...")
                startRekamMode(cacheDir)
            }
            ACTION_START_GROQ -> {
                currentMode = ACTION_START_GROQ
                startForegroundNotification("⚡ Merekam (Groq)...")
                startGroqMode(lang, cacheDir)
            }
            ACTION_START_SPEAKER -> {
                currentMode = ACTION_START_SPEAKER
                startForegroundNotification("👥 Multi Speaker merekam...")
                startSpeakerMode(cacheDir)
            }
            ACTION_STOP -> stopRecordingAndBroadcast()
        }
        return START_STICKY
    }

    // ── Modes ─────────────────────────────────────────────────────────────────

    private fun startRekamMode(cacheDir: File) {
        startTimeMs = System.currentTimeMillis()
        audioRec = AudioRecordManager {}
        audioRec!!.start(cacheDir)
        startTimer()
        broadcast(STATE_STARTED)
    }

    private fun startGroqMode(language: String, cacheDir: File) {
        startTimeMs = System.currentTimeMillis()
        continuousRec = ContinuousGroqRecorder(
            ctx         = applicationContext,
            language    = language,
            onPartial   = { chunk -> broadcast(STATE_CHUNK, chunkText = chunk) },
            onAmplitude = {}
        )
        continuousRec!!.start(cacheDir)
        startTimer()
        broadcast(STATE_STARTED)
    }

    private fun startSpeakerMode(cacheDir: File) {
        startTimeMs = System.currentTimeMillis()
        audioRec = AudioRecordManager {}
        audioRec!!.start(cacheDir)
        startTimer()
        broadcast(STATE_STARTED)
    }

    private fun stopRecordingAndBroadcast() {
        timerJob?.cancel()
        when (currentMode) {
            ACTION_START_GROQ -> {
                continuousRec?.stop { fullText, audioFile ->
                    broadcast(STATE_STOPPED, audioPath = audioFile?.absolutePath ?: "", fullText = fullText)
                    continuousRec = null
                    stopSelf()
                } ?: stopSelf()
            }
            ACTION_START_REKAM, ACTION_START_SPEAKER -> {
                audioRec?.stop { file ->
                    broadcast(STATE_STOPPED, audioPath = file?.absolutePath ?: "")
                    audioRec?.destroy(); audioRec = null
                    stopSelf()
                } ?: stopSelf()
            }
            else -> stopSelf()
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                updateNotificationTimer(elapsed)
                broadcast(STATE_TICK, elapsedMs = elapsed)
                delay(1000)
            }
        }
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    private fun broadcast(
        state: String,
        chunkText: String = "",
        audioPath: String = "",
        fullText: String  = "",
        elapsedMs: Long   = System.currentTimeMillis() - startTimeMs
    ) {
        sendBroadcast(Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE,      state)
            putExtra(EXTRA_CHUNK_TEXT, chunkText)
            putExtra(EXTRA_AUDIO_PATH, audioPath)
            putExtra(EXTRA_FULL_TEXT,  fullText)
            putExtra(EXTRA_ELAPSED_MS, elapsedMs)
        })
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun startForegroundNotification(title: String) {
        startForeground(NOTIF_ID, buildNotif(title, "Rekaman aman meski layar off"))
    }

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
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
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
            CHANNEL_ID,
            "KajianNote Recording",
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
            .apply { acquire(4 * 60 * 60 * 1000L) } // max 4 jam
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        timerJob?.cancel()
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
