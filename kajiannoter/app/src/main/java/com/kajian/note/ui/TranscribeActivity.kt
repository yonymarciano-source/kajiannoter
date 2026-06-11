package com.kajian.note.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kajian.note.KajianApp
import com.kajian.note.databinding.ActivityTranscribeBinding
import com.kajian.note.db.NoteRepository
import com.kajian.note.model.Note
import com.kajian.note.utils.GroqTranscriber
import com.kajian.note.utils.PreferencesManager
import com.kajian.note.utils.WavChunker
import com.kajian.note.utils.WhisperTranscriber
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class TranscribeActivity : AppCompatActivity() {

    private lateinit var b: ActivityTranscribeBinding
    private lateinit var prefs: PreferencesManager
    private var isProcessing = false

    companion object {
        const val EXTRA_WAV_PATH    = "wav_path"
        const val EXTRA_LANGUAGE    = "language"
        const val EXTRA_DURATION_MS = "duration_ms"
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(KajianApp.applyLocale(base, PreferencesManager(base).getAppLanguage()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTranscribeBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = PreferencesManager(this)

        val wavPath    = intent.getStringExtra(EXTRA_WAV_PATH) ?: ""
        val language   = intent.getStringExtra(EXTRA_LANGUAGE) ?: "id-ID"
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)

        b.btnBack.setOnClickListener {
            if (!isProcessing) finish()
            else Toast.makeText(this, "Sedang memproses...", Toast.LENGTH_SHORT).show()
        }

        if (wavPath.isBlank() || !File(wavPath).exists()) {
            showError("File rekaman tidak ditemukan"); return
        }

        val wavFile  = File(wavPath)
        val langCode = language.take(2)
        val engine   = prefs.getTranscribeEngine()

        when {
            engine == "GROQ" && GroqTranscriber.hasApiKey(this) ->
                startGroqTranscription(wavFile, language, durationMs, langCode)

            engine == "GROQ" && !GroqTranscriber.hasApiKey(this) ->
                showApiKeyInput(wavFile, language, durationMs, langCode)

            !WhisperTranscriber.isModelReady(this, langCode) ->
                downloadModel(wavFile, language, durationMs, langCode)

            else ->
                startOfflineTranscription(wavFile, language, durationMs, langCode)
        }
    }

    // ── GROQ: Minta API Key jika belum ada ──────────────────────────────
    private fun showApiKeyInput(wavFile: File, lang: String, dur: Long, code: String) {
        b.tvTitle.text = "🔑 Groq API Key"
        b.tvSubtitle.text = "Masukkan Groq API Key untuk transkripsi online.\n\nDapatkan gratis di: console.groq.com"
        b.tvSubtitle.visibility = View.VISIBLE
        b.cardInfo.visibility   = View.GONE
        b.progressBar.visibility = View.GONE

        // Tampilkan input API key via EditText yang ada di layout (btnRetry jadi tombol Simpan)
        b.btnRetry.visibility = View.VISIBLE
        b.btnRetry.text = "Gunakan Offline (Whisper)"
        b.btnRetry.setOnClickListener {
            // Fallback ke offline
            prefs.setTranscribeEngine("OFFLINE")
            b.btnRetry.visibility = View.GONE
            b.tvSubtitle.visibility = View.GONE
            if (!WhisperTranscriber.isModelReady(this, code))
                downloadModel(wavFile, lang, dur, code)
            else
                startOfflineTranscription(wavFile, lang, dur, code)
        }

        // Tampilkan hint di tvStatus sebagai input panduan
        b.tvStatus.text = "💡 Tip: Buka console.groq.com → API Keys → Create Key\nLalu masukkan key di Pengaturan app sebelum merekam."
        b.cardInfo.visibility = View.VISIBLE

        // Navigasi ke Settings untuk input key
        b.btnBack.visibility = View.VISIBLE
        Toast.makeText(this,
            "Masukkan Groq API Key di Pengaturan terlebih dahulu",
            Toast.LENGTH_LONG).show()

        // Auto buka Settings
        android.os.Handler(mainLooper).postDelayed({
            startActivity(Intent(this, AppSettingsActivity::class.java))
            finish()
        }, 2000)
    }

    // ── GROQ: Transkripsi online ─────────────────────────────────────────
    private fun startGroqTranscription(wavFile: File, lang: String, dur: Long, code: String) {
        isProcessing = true
        b.tvTitle.text    = "🌐 Groq AI Transcription"
        b.tvSubtitle.text = "Whisper large-v3-turbo via Groq\nAkurasi tinggi — butuh internet"
        b.progressBar.progress   = 0
        b.progressBar.visibility = View.VISIBLE
        b.cardInfo.visibility    = View.VISIBLE
        b.tvSubtitle.visibility  = View.VISIBLE

        lifecycleScope.launch {
            var lastMsg = "(belum mulai)"
            val result = runCatching {
                // Cek durasi rekaman — pakai chunked untuk file panjang
                val wavInfo   = WavChunker.parseHeader(wavFile)
                val durationS = wavInfo?.durationSec ?: 0.0
                val isLong    = durationS > WavChunker.MAX_CHUNK_SEC

                if (isLong) {
                    runOnUiThread {
                        b.tvSubtitle.text = "Rekaman panjang (${durationS.toInt()}s) — split per ${WavChunker.MAX_CHUNK_SEC / 60} menit"
                    }
                    GroqTranscriber.transcribeChunked(
                        ctx        = this@TranscribeActivity,
                        wavFile    = wavFile,
                        lang       = code,
                        onProgress = { pct, msg ->
                            lastMsg = msg
                            runOnUiThread { b.progressBar.progress = pct; b.tvStatus.text = msg }
                        }
                    )
                } else {
                    GroqTranscriber.transcribe(
                        ctx        = this@TranscribeActivity,
                        wavFile    = wavFile,
                        lang       = code,
                        onProgress = { pct, msg ->
                            lastMsg = msg
                            runOnUiThread { b.progressBar.progress = pct; b.tvStatus.text = msg }
                        }
                    )
                }
            }
            isProcessing = false

            val text = result.getOrDefault("")

            when {
                result.isFailure -> {
                    showError(
                        "Groq error: ${result.exceptionOrNull()?.message}\n\nStep: $lastMsg",
                        retryAction = { startGroqTranscription(wavFile, lang, dur, code) }
                    )
                }
                text == "ERROR_NO_KEY" -> {
                    showApiKeyInput(wavFile, lang, dur, code)
                }
                text.startsWith("ERROR:") -> {
                    showError(
                        "$text\n\nCek: API key valid? Koneksi internet aktif?",
                        retryAction = { startGroqTranscription(wavFile, lang, dur, code) },
                        offlineAction = { startOfflineTranscription(wavFile, lang, dur, code) }
                    )
                }
                text.isNotBlank() -> {
                    saveAndOpen(text, lang, dur, wavFile)
                }
                else -> {
                    showError(
                        "Groq mengembalikan teks kosong.\nStep: $lastMsg",
                        retryAction = { startGroqTranscription(wavFile, lang, dur, code) }
                    )
                }
            }
        }
    }

    // ── OFFLINE: Download model ──────────────────────────────────────────
    private fun downloadModel(wavFile: File, lang: String, dur: Long, code: String) {
        isProcessing = true
        b.tvTitle.text = "⬇️ Download Model Bahasa"
        b.tvSubtitle.text = "Model AI Indonesia (~74MB) — sekali saja"
        b.progressBar.visibility = View.VISIBLE
        b.cardInfo.visibility = View.VISIBLE
        b.tvStatus.text = "Menghubungi server..."

        lifecycleScope.launch {
            val ok = WhisperTranscriber.downloadModel(this@TranscribeActivity, code) { pct, msg ->
                runOnUiThread { b.progressBar.progress = pct; b.tvStatus.text = msg }
            }
            if (ok) startOfflineTranscription(wavFile, lang, dur, code)
            else {
                isProcessing = false
                showError("Download model gagal.\nPastikan WiFi aktif dan coba lagi.",
                    retryAction = { downloadModel(wavFile, lang, dur, code) })
            }
        }
    }

    // ── OFFLINE: Transkripsi lokal ───────────────────────────────────────
    private fun startOfflineTranscription(wavFile: File, lang: String, dur: Long, code: String) {
        isProcessing = true
        b.tvTitle.text    = "🧠 Transkripsi Offline"
        b.tvSubtitle.text = "Whisper base — sepenuhnya offline"
        b.progressBar.progress   = 0
        b.progressBar.visibility = View.VISIBLE
        b.cardInfo.visibility    = View.VISIBLE
        b.tvSubtitle.visibility  = View.VISIBLE

        lifecycleScope.launch {
            var lastMsg = "(belum mulai)"
            val result = runCatching {
                WhisperTranscriber.transcribe(
                    ctx        = this@TranscribeActivity,
                    wavFile    = wavFile,
                    lang       = code,
                    onProgress = { pct, msg ->
                        lastMsg = msg
                        runOnUiThread { b.progressBar.progress = pct; b.tvStatus.text = msg }
                    }
                )
            }
            isProcessing = false

            if (result.isFailure) {
                showError("ERROR: ${result.exceptionOrNull()?.message}\nStep: $lastMsg",
                    retryAction = { startOfflineTranscription(wavFile, lang, dur, code) })
                return@launch
            }

            val text = result.getOrDefault("")
            if (text.isNotBlank()) {
                saveAndOpen(text, lang, dur, wavFile)
            } else {
                showError("Transkripsi kosong.\nStep: $lastMsg",
                    retryAction = { startOfflineTranscription(wavFile, lang, dur, code) })
            }
        }
    }

    // ── Save & open note ─────────────────────────────────────────────────
    private suspend fun saveAndOpen(text: String, lang: String, dur: Long, wavFile: File? = null) {
        val title = text.trim().split("\\s+".toRegex()).take(7).joinToString(" ")
            .let { if (it.split(" ").size >= 7) "$it…" else it }
            .ifBlank { "Kajian ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())}" }

        val repo = NoteRepository(this)
        val noteId = repo.insert(Note(
            title            = title,
            plainText        = text,
            transcriptJson   = "",
            detectedLanguage = lang,
            durationMs       = dur,
            wordCount        = text.split("\\s+".toRegex()).count { it.isNotBlank() },
            speakerCount     = 1,
            audioPath        = ""   // akan diupdate setelah insert
        ))

        // Simpan audio ke storage permanen, update note dengan path-nya
        if (wavFile != null && wavFile.exists()) {
            val savedPath = com.kajian.note.utils.AudioStorage.saveAudio(this, wavFile, noteId)
            if (savedPath.isNotBlank()) {
                val saved = repo.getById(noteId)
                if (saved != null) repo.update(saved.copy(audioPath = savedPath))
            }
        }

        runOnUiThread {
            startActivity(android.content.Intent(this, NoteDetailActivity::class.java).apply {
                putExtra(NoteDetailActivity.EXTRA_ID, noteId)
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            finish()
        }
    }

    // ── Show error ───────────────────────────────────────────────────────
    private fun showError(
        msg: String,
        retryAction: (() -> Unit)? = null,
        offlineAction: (() -> Unit)? = null
    ) {
        b.tvTitle.text = "❌ Gagal"
        b.tvSubtitle.text = msg
        b.tvSubtitle.visibility  = View.VISIBLE
        b.tvStatus.text          = ""
        b.progressBar.visibility = View.GONE
        b.btnBack.visibility     = View.VISIBLE
        b.cardInfo.visibility    = View.GONE

        if (offlineAction != null) {
            // Tampilkan tombol fallback offline
            b.btnRetry.visibility = View.VISIBLE
            b.btnRetry.text = "🔄 Coba Offline"
            b.btnRetry.setOnClickListener {
                b.btnRetry.visibility    = View.GONE
                b.tvSubtitle.visibility  = View.GONE
                b.progressBar.visibility = View.VISIBLE
                offlineAction()
            }
        } else if (retryAction != null) {
            b.btnRetry.visibility = View.VISIBLE
            b.btnRetry.text = "🔄 Coba Lagi"
            b.btnRetry.setOnClickListener {
                b.btnRetry.visibility    = View.GONE
                b.tvSubtitle.visibility  = View.GONE
                b.progressBar.visibility = View.VISIBLE
                retryAction()
            }
        }
    }

    override fun onBackPressed() {
        if (!isProcessing) super.onBackPressed()
        else Toast.makeText(this, "Sedang memproses...", Toast.LENGTH_SHORT).show()
    }
}
