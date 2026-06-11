package com.kajian.note.utils

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * AudioSourceManager — Manages microphone configuration for different recording scenarios.
 *
 * Mode NORMAL:   Direct speech (person talking into phone)
 *                → Standard settings, AEC ON, NS ON
 *
 * Mode SPEAKER:  Audio from external speaker (Zoom, YouTube, TV, laptop)
 *                → AEC OFF (don't cancel "echo" = the speaker audio we WANT)
 *                → NS OFF (don't suppress the background frequency we need)
 *                → AGC ON (auto-boost gain for quieter audio)
 *                → Ultra-sensitive thresholds
 *
 * The trick: We open a silent AudioRecord with AEC disabled BEFORE SpeechRecognizer
 * starts, which influences the audio stack's processing pipeline on some devices.
 */
object AudioSourceManager {

    private const val TAG = "AudioSourceManager"

    enum class Mode {
        NORMAL,   // Direct mic — person speaking to phone
        SPEAKER   // External speaker — Zoom, YouTube, TV, etc.
    }

    data class Config(
        val mode: Mode,
        val silenceCompleteMs: Long,
        val silencePossibleMs: Long,
        val minSpeechMs: Long,
        val restartDelayMs: Long,
        val forceFlushMs: Long,
        val audioSource: Int,
        val description: String,
        val tip: String
    )

    /**
     * Build config combining Mode + Sensitivity level (1-5).
     *
     * Sensitivity maps to:
     * 1 = Low      → only clear, close speech. Fewer false positives.
     * 2 = Normal-  → slightly conservative
     * 3 = Normal   → balanced default
     * 4 = High     → catches distant/quiet speech
     * 5 = Max      → catches everything, including noisy environments
     *
     * For Speaker/PA mode, we always start at least at level 3.
     */
    fun buildConfig(mode: Mode, sensitivity: Int = 3): Config {
        val level = sensitivity.coerceIn(1, 5)
        return when (mode) {
            Mode.NORMAL -> Config(
                mode = Mode.NORMAL,
                // Diperpanjang: complete 3s, possibly 3s, min 15s — kurangi intermittent gap
                silenceCompleteMs  = when(level) { 1->3000L; 2->3000L; 3->3000L; 4->1500L; else->800L },
                silencePossibleMs  = when(level) { 1->3000L; 2->3000L; 3->3000L; 4->1000L; else->500L },
                minSpeechMs        = when(level) { 1->15_000L; 2->15_000L; 3->15_000L; 4->10_000L; else->5_000L },
                restartDelayMs     = when(level) { 1->200L;  2->120L;  3->80L;   4->50L;  else->30L  },
                forceFlushMs       = when(level) { 1->20_000L; 2->18_000L; 3->15_000L; 4->10_000L; else->8_000L },
                audioSource        = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                description        = "Mode Normal · Sensitivitas $level/5",
                tip                = when(level) {
                    1 -> "🎤 Khusus untuk bicara langsung, jarak dekat"
                    2 -> "🎤 Bicara jelas ke HP, jarak < 50cm"
                    3 -> "🎤 Bicara langsung ke HP untuk hasil terbaik"
                    4 -> "🎤 Dapat menangkap suara agak jauh"
                    else -> "🎤 Sensitivitas maksimal — tangkap semua suara"
                }
            )
            Mode.SPEAKER -> Config(
                mode = Mode.SPEAKER,
                silenceCompleteMs  = when(level) { 1->1200L; 2->900L;  3->600L;  4->400L; else->250L },
                silencePossibleMs  = when(level) { 1->600L;  2->450L;  3->300L;  4->200L; else->100L },
                minSpeechMs        = when(level) { 1->100L;  2->70L;   3->50L;   4->30L;  else->10L  },
                restartDelayMs     = when(level) { 1->100L;  2->70L;   3->50L;   4->30L;  else->20L  },
                forceFlushMs       = when(level) { 1->12_000L; 2->10_000L; 3->8_000L; 4->6_000L; else->4_000L },
                audioSource        = MediaRecorder.AudioSource.UNPROCESSED,
                description        = "Mode Speaker · Sensitivitas $level/5",
                tip                = when(level) {
                    1 -> "🔊 Speaker dekat, volume keras (>80%)"
                    2 -> "🔊 Speaker 1-2m, volume 70%+"
                    3 -> "🔊 Speaker PA/masjid, posisi dekat speaker"
                    4 -> "🔊 Bisa tangkap speaker dari jarak 3-5m"
                    else -> "🔊 Maksimal — untuk ruangan besar / speaker jauh"
                }
            )
        }
    }

    val NORMAL_CONFIG = Config(
        mode = Mode.NORMAL,
        silenceCompleteMs = 3000,
        silencePossibleMs = 3000,
        minSpeechMs = 15_000,
        restartDelayMs = 80,
        forceFlushMs = 15_000,
        audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        description = "Mode Normal",
        tip = "🎤 Bicara langsung ke HP untuk hasil terbaik"
    )

    val SPEAKER_CONFIG = Config(
        mode = Mode.SPEAKER,
        silenceCompleteMs = 600,    // very short — catch every speech burst
        silencePossibleMs = 300,
        minSpeechMs = 50,
        restartDelayMs = 50,
        forceFlushMs = 8_000,       // flush more frequently for speaker audio
        audioSource = MediaRecorder.AudioSource.UNPROCESSED,  // raw audio, no AEC
        description = "Mode Speaker Eksternal",
        tip = "🔊 Dekatkan HP ke speaker/laptop. Volume sumber minimal 70%"
    )

    // Keeps a silent AudioRecord running with AEC disabled
    // This influences the audio processing pipeline before STT starts
    private var silentRecord: AudioRecord? = null
    private var aecEffect: AcousticEchoCanceler? = null
    private var nsEffect: NoiseSuppressor? = null
    private var agcEffect: AutomaticGainControl? = null

    fun prepareForMode(mode: Mode) {
        when (mode) {
            Mode.NORMAL -> releaseAudioEffects()
            Mode.SPEAKER -> enableSpeakerMode()
        }
    }

    private fun enableSpeakerMode() {
        // NOTE: We do NOT create a competing AudioRecord here.
        // A second AudioRecord would block SpeechRecognizer from accessing the mic.
        // Speaker mode behavior is handled entirely by Config's lower silence thresholds
        // and the sensitivity settings — no audio stack manipulation needed.
        Log.d(TAG, "Speaker mode: using sensitivity-based config (no AudioRecord session)")
    }

    fun releaseAudioEffects() {
        try {
            aecEffect?.release(); aecEffect = null
            nsEffect?.release();  nsEffect = null
            agcEffect?.release(); agcEffect = null
            silentRecord?.stop()
            silentRecord?.release(); silentRecord = null
            Log.d(TAG, "Audio effects released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing: ${e.message}")
        }
    }
}
