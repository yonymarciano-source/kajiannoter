package com.kajian.note.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.kajian.note.model.TranscriptEntry
import kotlinx.coroutines.*

/**
 * SpeechHelper — Robust continuous speech recognition.
 *
 * Fixes for intermittent recording + stuck state:
 * 1. @Volatile isStarting flag  → prevents concurrent createAndListen() calls
 * 2. flushJob tracked separately → cancelled before onResults restart
 * 3. Watchdog timer             → detects permanent stuck, auto-resets
 * 4. Fresh recognizer every cycle → never reuse a potentially bad instance
 * 5. Max consecutive error limit → informs user instead of spinning forever
 */
class SpeechHelper(private val context: Context, private val listener: Callback) {

    companion object {
        private const val TAG = "SpeechHelper"
        private const val MAX_CONSECUTIVE_ERRORS = 8
        private const val WATCHDOG_MS = 45_000L   // reset if silent for 45s
        const val TIMESTAMP_EVERY_MS = 2 * 60 * 1000L
    }

    interface Callback {
        fun onPartialResult(text: String)
        fun onResult(entry: TranscriptEntry, plainText: String)
        fun onTimestamp(entry: TranscriptEntry)
        fun onError(message: String)
        fun onRmsChanged(rms: Float)
        fun onListeningChanged(listening: Boolean)
    }

    // ── Public state ──────────────────────────────────────────────────────
    var isActive   = false
    var isPaused   = false
    var currentLanguage = "id-ID"
    var currentSpeakerIndex = 0
    var audioConfig = AudioSourceManager.NORMAL_CONFIG
    val speakerNames = mutableMapOf<Int, String>()
    val dictionary   = DictionaryManager(context)

    // ── Internal state ────────────────────────────────────────────────────
    private var recognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // CRITICAL: prevents two concurrent createAndListen() calls
    @Volatile private var isStarting = false

    private var flushJob: Job? = null
    private var watchdogJob: Job? = null
    private var consecutiveErrors = 0

    private val entries    = mutableListOf<TranscriptEntry>()
    private val plainText  = StringBuilder()
    private var lastPartialText = ""

    private var recordingStartMs  = 0L
    private var lastResultMs      = 0L
    private var lastTimestampMs   = 0L
    private var lastActivityMs    = 0L   // for watchdog

    private val rmsBuffer = ArrayDeque<Float>(30)
    private var utteranceMaxRms = 0f

    // ── Public API ────────────────────────────────────────────────────────

    fun start(language: String) {
        currentLanguage = language
        recordingStartMs = System.currentTimeMillis()
        lastResultMs = 0L
        lastTimestampMs = 0L
        lastActivityMs = System.currentTimeMillis()
        consecutiveErrors = 0
        isActive = true
        isPaused = false
        isStarting = false

        AudioSourceManager.prepareForMode(audioConfig.mode)
        createAndListen()
        startFlushTimer()
        startWatchdog()
        listener.onListeningChanged(true)
    }

    fun stop() {
        isActive = false
        isPaused = false
        isStarting = false
        flushJob?.cancel(); flushJob = null
        watchdogJob?.cancel(); watchdogJob = null
        scope.coroutineContext.cancelChildren()
        destroyRecognizer()
        AudioSourceManager.releaseAudioEffects()
        listener.onListeningChanged(false)
    }

    fun pause() {
        if (isPaused) return
        isPaused = true
        flushJob?.cancel()
        watchdogJob?.cancel()
        destroyRecognizer()
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        isStarting = false
        lastActivityMs = System.currentTimeMillis()
        createAndListen()
        startFlushTimer()
        startWatchdog()
    }

    fun clearAll() {
        entries.clear()
        plainText.clear()
        lastResultMs = 0L
        lastTimestampMs = 0L
        currentSpeakerIndex = 0
        lastPartialText = ""
        consecutiveErrors = 0
    }

    // ── Speaker management ────────────────────────────────────────────────

    fun nextSpeaker() = changeSpeaker(currentSpeakerIndex + 1)
    fun prevSpeaker() { if (currentSpeakerIndex > 0) changeSpeaker(currentSpeakerIndex - 1) }

    private fun changeSpeaker(newIndex: Int) {
        currentSpeakerIndex = newIndex.coerceAtLeast(0)
        val name = speakerName(currentSpeakerIndex)
        entries.add(TranscriptEntry(
            type = TranscriptEntry.TYPE_SPEAKER_CHANGE,
            speakerIndex = currentSpeakerIndex,
            speakerName = name,
            timeMs = elapsed()
        ))
        plainText.append("\n\n▶ $name\n")
    }

    fun setSpeakerName(index: Int, name: String) {
        speakerNames[index] = name
        for (i in entries.indices) {
            val e = entries[i]
            if (e.speakerIndex == index && e.type != TranscriptEntry.TYPE_TIMESTAMP)
                entries[i] = e.copy(speakerName = name)
        }
    }

    fun speakerName(index: Int) = speakerNames[index] ?: TranscriptEntry.defaultName(index)
    fun currentSpeakerName() = speakerName(currentSpeakerIndex)
    fun getEntries() = entries.toList()
    fun getPlainText() = plainText.toString().trim()

    // ── Core: createAndListen with mutex ──────────────────────────────────

    private fun createAndListen() {
        if (!isActive || isPaused) return

        // MUTEX: prevent concurrent calls
        if (isStarting) {
            Log.w(TAG, "createAndListen() skipped — already starting")
            return
        }
        isStarting = true

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Speech recognition tidak tersedia di perangkat ini")
            isStarting = false
            return
        }

        // Always destroy first — never reuse a potentially broken instance
        destroyRecognizer()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                audioConfig.silenceCompleteMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                audioConfig.silencePossibleMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                audioConfig.minSpeechMs)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        try {
            recognizer?.startListening(intent)
            Log.d(TAG, "startListening() called")
        } catch (e: Exception) {
            Log.e(TAG, "startListening error: ${e.message}")
            isStarting = false
            scheduleRestart(500)
        }
        // isStarting = false will be set in onReadyForSpeech or after delay
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }

    private fun scheduleRestart(delayMs: Long = audioConfig.restartDelayMs) {
        if (!isActive || isPaused) return
        scope.launch {
            delay(delayMs)
            if (isActive && !isPaused) createAndListen()
        }
    }

    // ── Force flush timer (separate job, cancellable) ─────────────────────

    private fun startFlushTimer() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive && !isPaused) {
                delay(audioConfig.forceFlushMs)
                if (!isActive || isPaused) break
                val partial = lastPartialText
                if (partial.isNotBlank()) {
                    Log.d(TAG, "Force flush: $partial")
                    lastPartialText = ""
                    processResult(partial, 0.5f)
                    // Restart recognizer after flush
                    isStarting = false
                    destroyRecognizer()
                    delay(200)
                    if (isActive && !isPaused) createAndListen()
                }
            }
        }
    }

    // ── Watchdog: detect permanently stuck state ──────────────────────────

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && !isPaused) {
                delay(WATCHDOG_MS)
                if (!isActive || isPaused) break
                val sinceActivity = System.currentTimeMillis() - lastActivityMs
                if (sinceActivity > WATCHDOG_MS) {
                    Log.w(TAG, "Watchdog: stuck for ${sinceActivity}ms, hard reset")
                    consecutiveErrors = 0
                    isStarting = false
                    flushJob?.cancel()
                    destroyRecognizer()
                    delay(500)
                    if (isActive && !isPaused) {
                        createAndListen()
                        startFlushTimer()
                    }
                }
            }
        }
    }

    // ── Elapsed / timestamp helpers ───────────────────────────────────────

    private fun elapsed() = if (recordingStartMs > 0)
        System.currentTimeMillis() - recordingStartMs else 0L

    private fun checkAndInsertTimestamp(elapsedMs: Long) {
        val next = if (lastTimestampMs == 0L) TIMESTAMP_EVERY_MS
                   else lastTimestampMs + TIMESTAMP_EVERY_MS
        if (elapsedMs >= next) {
            var t = next
            while (t <= elapsedMs) {
                val ts = TranscriptEntry(type = TranscriptEntry.TYPE_TIMESTAMP, timeMs = t)
                entries.add(ts)
                plainText.append("\n\n⏱ [${ts.getFormattedTime()}]\n\n")
                listener.onTimestamp(ts)
                lastTimestampMs = t
                t += TIMESTAMP_EVERY_MS
            }
        }
    }

    // ── Process final result ──────────────────────────────────────────────

    private fun processResult(text: String, confidence: Float) {
        if (text.isBlank()) return
        lastPartialText = ""
        lastActivityMs = System.currentTimeMillis()
        consecutiveErrors = 0

        val elapsedMs = elapsed()
        val now = System.currentTimeMillis()
        val pauseMs = if (lastResultMs > 0) now - lastResultMs else 0L
        val myMaxRms = utteranceMaxRms
        val myAvgRms = if (rmsBuffer.isNotEmpty()) rmsBuffer.average().toFloat() else 0f
        rmsBuffer.clear(); utteranceMaxRms = 0f

        checkAndInsertTimestamp(elapsedMs)

        // Dictionary correction
        val corrected = dictionary.correct(text)
        val prevText  = entries.lastOrNull { it.type == TranscriptEntry.TYPE_SPEECH }?.text ?: ""
        val isFirst   = entries.none { it.type == TranscriptEntry.TYPE_SPEECH }

        // Smart punctuation
        val punct = PunctuationEngine.analyze(
            text = corrected, pauseMs = pauseMs, avgRms = myAvgRms,
            maxRms = myMaxRms, prevText = prevText, isFirst = isFirst,
            language = currentLanguage
        )

        val finalText = if (punct.capitalize && !currentLanguage.startsWith("ar"))
            PunctuationEngine.capitalize(corrected, currentLanguage) + punct.suffix
        else corrected + punct.suffix

        val entry = TranscriptEntry(
            type = TranscriptEntry.TYPE_SPEECH,
            speakerIndex = currentSpeakerIndex,
            speakerName = speakerName(currentSpeakerIndex),
            timeMs = elapsedMs, text = finalText,
            confidence = confidence, avgRms = myAvgRms, pauseBefore = pauseMs
        )
        entries.add(entry)
        plainText.append(punct.prefix).append(finalText)
        lastResultMs = now
        listener.onResult(entry, plainText.toString())
    }

    // ── RecognitionListener ───────────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(p: Bundle?) {
            // Release mutex — recognizer is ready
            isStarting = false
            lastActivityMs = System.currentTimeMillis()
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            utteranceMaxRms = 0f
            lastActivityMs = System.currentTimeMillis()
        }

        override fun onRmsChanged(rms: Float) {
            val norm = ((rms + 2f) * 1.8f).coerceIn(0f, 30f)
            if (rmsBuffer.size >= 30) rmsBuffer.removeFirst()
            rmsBuffer.addLast(norm)
            if (norm > utteranceMaxRms) utteranceMaxRms = norm
            lastActivityMs = System.currentTimeMillis()
            listener.onRmsChanged(norm)
        }

        override fun onPartialResults(r: Bundle?) {
            val p = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) {
                lastPartialText = p
                lastActivityMs = System.currentTimeMillis()
                listener.onPartialResult(p)
            }
        }

        override fun onResults(r: Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            val conf = r?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: 0f

            if (!text.isNullOrBlank()) processResult(text, conf)

            // Cancel flush job to avoid race condition then restart
            flushJob?.cancel()
            isStarting = false

            if (isActive && !isPaused) {
                scope.launch {
                    delay(audioConfig.restartDelayMs)
                    if (isActive && !isPaused) {
                        createAndListen()
                        startFlushTimer()  // restart flush timer too
                    }
                }
            }
        }

        override fun onError(err: Int) {
            isStarting = false  // always release mutex on error
            Log.w(TAG, "STT error: $err (consecutive: $consecutiveErrors)")

            val fatalMsg = when (err) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Izin mikrofon diperlukan"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Koneksi internet diperlukan"
                else -> null
            }

            if (fatalMsg != null) {
                listener.onError(fatalMsg)
                if (err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
            }

            // Count consecutive non-result errors
            val isContentError = err == SpeechRecognizer.ERROR_NO_MATCH ||
                                  err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!isContentError) consecutiveErrors++

            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                consecutiveErrors = 0
                listener.onError("Mikrofon tidak merespons. Coba tap Stop lalu Record lagi.")
                return
            }

            if (!isActive || isPaused) return

            val delay = when (err) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY   -> 2000L
                SpeechRecognizer.ERROR_NETWORK           -> 4000L
                SpeechRecognizer.ERROR_NO_MATCH          -> audioConfig.restartDelayMs
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT    -> audioConfig.restartDelayMs
                else -> 500L
            }
            scheduleRestart(delay)
        }

        override fun onEndOfSpeech() { lastActivityMs = System.currentTimeMillis() }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    fun destroy() {
        scope.cancel()
        destroyRecognizer()
        AudioSourceManager.releaseAudioEffects()
    }
}
