package com.kajian.note.utils

import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * AudioPlayer — wrapper MediaPlayer yang aman untuk NoteDetailActivity.
 * Handle lifecycle, seek, speed, skip.
 */
class AudioPlayer {

    private var mp: MediaPlayer? = null
    private var onProgress: ((Int, Int) -> Unit)? = null  // (currentMs, totalMs)
    private var onComplete: (() -> Unit)? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    val isPlaying: Boolean get() = mp?.isPlaying == true
    val currentPosition: Int get() = mp?.currentPosition ?: 0
    val duration: Int get() = mp?.duration ?: 0

    fun load(
        file: File,
        onProgress: (Int, Int) -> Unit,
        onComplete: () -> Unit
    ): Boolean {
        return try {
            release()
            this.onProgress = onProgress
            this.onComplete = onComplete

            mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { onComplete(); stopProgressUpdates() }
            }
            startProgressUpdates()
            true
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Load error: ${e.message}", e)
            false
        }
    }

    fun play() { mp?.start(); startProgressUpdates() }
    fun pause() { mp?.pause(); stopProgressUpdates() }

    fun seekTo(ms: Int) {
        mp?.seekTo(ms)
        onProgress?.invoke(ms, duration)
    }

    fun skipForward(sec: Int = 5) {
        val newPos = (currentPosition + sec * 1000).coerceAtMost(duration)
        seekTo(newPos)
    }

    fun skipBackward(sec: Int = 5) {
        val newPos = (currentPosition - sec * 1000).coerceAtLeast(0)
        seekTo(newPos)
    }

    fun setSpeed(speed: Float) {
        try {
            mp?.playbackParams = mp?.playbackParams?.setSpeed(speed)!!
        } catch (e: Exception) {
            Log.w("AudioPlayer", "Speed not supported: ${e.message}")
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                if (isPlaying) {
                    onProgress?.invoke(currentPosition, duration)
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    fun release() {
        stopProgressUpdates()
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
        mp = null
    }

    fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
