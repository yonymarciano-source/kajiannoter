package com.kajian.note.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.kajian.note.R
import com.kajian.note.databinding.FragmentRecordBinding
import com.kajian.note.model.TranscriptEntry
import com.kajian.note.utils.*

class RecordFragment : Fragment(), SpeechHelper.Callback {

    private var _b: FragmentRecordBinding? = null
    private val b get() = _b!!
    private val vm: RecordViewModel by activityViewModels()
    private lateinit var prefs: PreferencesManager

    // Engines
    private var speech: SpeechHelper? = null          // Mode Langsung (SpeechRecognizer)
    private var audioRec: AudioRecordManager? = null   // Mode Rekam → Groq/Offline
    private var continuousRec: ContinuousGroqRecorder? = null  // Mode Langsung → Groq (akurat)

    private var recordMode = RecordMode.LANGSUNG
    private var useContinuousGroq = false   // true jika Groq key ada dan Mode Langsung
    private var recordingStartMs = 0L
    private var savedAudioPath = ""         // path audio tersimpan sementara

    enum class RecordMode { LANGSUNG, REKAM }

    private val handler = Handler(Looper.getMainLooper())
    private var startElapsed = 0L
    private var timerRunning = false
    private var pulseAnim: ObjectAnimator? = null
    private val appendedChunks = StringBuilder()

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(),
            getString(R.string.error_mic_permission), Toast.LENGTH_LONG).show()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentRecordBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = PreferencesManager(requireContext())
        speech = SpeechHelper(requireContext(), this)

        setupLanguage()
        setupAudioMode()
        setupRecordModeToggle()
        setupRecordButton()
        setupSaveAndClear()
        setupTranscriptEdit()
        observeVM()
    }

    // ── Record Mode ────────────────────────────────────────────────────────

    private fun setupRecordModeToggle() {
        recordMode = if (prefs.getRecordMode() == "REKAM") RecordMode.REKAM else RecordMode.LANGSUNG
        updateRecordModeUI()
        b.btnRecordMode.setOnClickListener {
            recordMode = if (recordMode == RecordMode.LANGSUNG) RecordMode.REKAM else RecordMode.LANGSUNG
            prefs.setRecordMode(recordMode.name)
            updateRecordModeUI()
        }
        // Info buttons (#1)
        b.btnInfoMode.setOnClickListener {
            val hasGroq = GroqTranscriber.hasApiKey(requireContext())
            val msg = if (hasGroq)
                "🎙 Langsung (Groq): Rekam + kirim ke Groq Whisper tiap ~30 detik. Akurat untuk bicara cepat & istilah Arab.\n\n🔴 Rekam dulu: Rekam audio penuh → transkripsi setelah selesai. Cocok untuk kajian panjang."
            else
                "🎙 Langsung (STT): Transkripsi real-time via Android STT. Teks muncul saat bicara.\n\n🔴 Rekam dulu: Rekam audio penuh → transkripsi setelah selesai.\n\n💡 Set Groq API Key di Settings untuk akurasi lebih baik."
            showInfoDialog("Mode Rekam", msg)
        }
        b.btnInfoLang.setOnClickListener {
            showInfoDialog("Bahasa Transkripsi",
                "Pilih bahasa yang digunakan saat kajian.\n\n" +
                "🌐 Auto Detect: Whisper otomatis deteksi bahasa termasuk campuran Indonesia-Arab.\n\n" +
                "🇮🇩 Indonesia: Dioptimalkan untuk konten kajian Islam dalam Bahasa Indonesia.\n\n" +
                "🌙 Arab: Untuk kajian full Bahasa Arab. Groq Whisper akan tulis teks Arab asli.")
        }
        b.btnInfoAudio.setOnClickListener {
            showInfoDialog("Sumber Audio",
                "🎤 Normal: Rekam suara langsung dari mikrofon HP. Cocok untuk bicara ke HP.\n\n" +
                "🔊 Speaker: Mode sensitivitas tinggi untuk merekam suara dari speaker/proyektor, cocok untuk kajian di masjid atau ruangan besar.")
        }
    }

    private fun updateRecordModeUI() {
        val hasGroqKey = GroqTranscriber.hasApiKey(requireContext())
        if (recordMode == RecordMode.REKAM) {
            b.tvRecordModeLabel.text = "Rekam dulu"
        } else {
            b.tvRecordModeLabel.text = if (hasGroqKey) "Langsung (Groq)" else "Langsung (STT)"
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Language ───────────────────────────────────────────────────────────

    private fun setupLanguage() {
        val saved = prefs.getRecordingLanguage()
        val resolved = if (saved == "auto")
            LanguageDetector.detectDeviceLanguage(requireContext()) else saved
        speech?.currentLanguage = resolved
        vm.setLanguage(resolved)
        updateLangDisplay(resolved)
        b.btnLanguage.setOnClickListener { showLanguagePicker() }
    }

    private fun updateLangDisplay(lang: String) {
        // Tampilkan "Auto Detect" jika auto, bukan bahasa device
        val savedPref = prefs.getRecordingLanguage()
        b.tvCurrentLang.text = if (savedPref == "auto") "🌐 Auto Detect"
                               else LanguageDetector.getDisplayName(lang)
    }

    private fun showLanguagePicker() {
        val langs = PreferencesManager.RECORDING_LANGUAGES
        val names = langs.map { it.second }.toTypedArray()
        val cur = langs.indexOfFirst { it.first == prefs.getRecordingLanguage() }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.lang_audio))
            .setSingleChoiceItems(names as Array<CharSequence>, cur) { dlg, i ->
                val sel = langs[i]
                val code = if (sel.first == "auto")
                    LanguageDetector.detectDeviceLanguage(requireContext()) else sel.first
                prefs.setRecordingLanguage(sel.first)
                speech?.currentLanguage = code
                vm.setLanguage(code)
                updateLangDisplay(code)
                updateRecordModeUI()
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ── Audio Mode ─────────────────────────────────────────────────────────

    private fun setupAudioMode() {
        val savedMode = prefs.getAudioMode()
        val savedSens = prefs.getMicSensitivity()
        val mode = if (savedMode == AudioSourceManager.Mode.SPEAKER.name)
            AudioSourceManager.Mode.SPEAKER else AudioSourceManager.Mode.NORMAL
        speech?.audioConfig = AudioSourceManager.buildConfig(mode, savedSens)
        updateAudioModeUI()
        b.btnAudioMode.setOnClickListener { showAudioModePicker() }
    }

    private fun updateAudioModeUI() {
        val config = speech?.audioConfig ?: AudioSourceManager.NORMAL_CONFIG
        b.tvAudioModeLabel.text = config.description
        b.tvAudioModeLabel.text = config.tip
        b.btnAudioMode.text = if (config.mode == AudioSourceManager.Mode.SPEAKER)
            "🔊 Speaker" else "🎤 Normal"
        b.tvAudioModeLabel.text = "Sens: ${prefs.getMicSensitivity()}/5"
    }

    private fun showAudioModePicker() {
        val modes = listOf(AudioSourceManager.NORMAL_CONFIG, AudioSourceManager.SPEAKER_CONFIG)
        val current = speech?.audioConfig?.mode ?: AudioSourceManager.Mode.NORMAL
        val currentIdx = modes.indexOfFirst { it.mode == current }
        val names: Array<CharSequence> = modes.map { "${it.description}\n${it.tip}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🎚️ Mode Audio")
            .setSingleChoiceItems(names, currentIdx) { dlg, which ->
                val selected = modes[which]
                val sensitivity = prefs.getMicSensitivity()
                speech?.audioConfig = AudioSourceManager.buildConfig(selected.mode, sensitivity)
                prefs.setAudioMode(selected.mode.name)
                updateAudioModeUI()
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ── Record Button ──────────────────────────────────────────────────────

    private fun setupRecordButton() {
        b.fabRecord.setOnClickListener {
            if (vm.isRecording.value == true) stopRecording() else checkAndRecord()
        }
    }

    private fun checkAndRecord() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startRecording()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        recordingStartMs = System.currentTimeMillis()
        savedAudioPath = ""
        appendedChunks.clear()
        vm.setRecording(true)
        startElapsed = SystemClock.elapsedRealtime()
        timerRunning = true
        handler.post(timerTick)

        b.fabRecord.setImageResource(R.drawable.ic_stop)
        b.fabRecord.backgroundTintList =
            resources.getColorStateList(R.color.recording_red, null)
        b.tvStatus.text = getString(R.string.status_recording)
        b.waveformView.isVisible = true
        b.tvPartial.isVisible = true
        b.cardHint.isVisible = false
        startPulse()

        val lang = vm.language.value ?: LanguageDetector.detectDeviceLanguage(requireContext())
        val hasGroqKey = GroqTranscriber.hasApiKey(requireContext())

        when (recordMode) {
            RecordMode.LANGSUNG -> {
                b.etTranscript.setText("")
                speech?.clearAll()
                if (hasGroqKey) {
                    // Mode Langsung Groq — akurat untuk bicara cepat
                    useContinuousGroq = true
                    b.tvPartial.text = "🎙️ Merekam... teks muncul tiap ~30 detik"
                    continuousRec = ContinuousGroqRecorder(
                        ctx       = requireContext(),
                        language  = lang,
                        onPartial = { chunkText ->
                            appendedChunks.append(" ").append(chunkText)
                            val full = appendedChunks.toString().trim()
                            activity?.runOnUiThread {
                                b.etTranscript.setText(full)
                                b.etTranscript.setSelection(full.length)
                                b.tvPartial.text = "✅ Chunk terakhir: $chunkText"
                                vm.updateText(full)
                            }
                        },
                        onAmplitude = { rms ->
                            activity?.runOnUiThread { b.waveformView.setLevel(rms) }
                        }
                    )
                    continuousRec?.start(requireContext().cacheDir)
                } else {
                    // Fallback ke Android SpeechRecognizer
                    useContinuousGroq = false
                    speech?.start(lang)
                }
            }
            RecordMode.REKAM -> {
                useContinuousGroq = false
                b.etTranscript.setText("🔴 Merekam audio...\nTranskripsi akan diproses setelah recording selesai.")
                b.tvPartial.text = "🔴 Merekam..."
                audioRec = AudioRecordManager { rms ->
                    activity?.runOnUiThread { b.waveformView.setLevel(rms) }
                }
                audioRec?.start(requireContext().cacheDir)
            }
        }
    }

    private fun stopRecording() {
        vm.setRecording(false)
        timerRunning = false
        handler.removeCallbacks(timerTick)
        stopPulse()

        b.fabRecord.setImageResource(R.drawable.ic_mic)
        b.fabRecord.backgroundTintList =
            resources.getColorStateList(R.color.primary_green, null)
        b.tvStatus.text = getString(R.string.status_idle)
        b.waveformView.isVisible = false
        b.tvPartial.isVisible = false

        val dur = System.currentTimeMillis() - recordingStartMs

        when (recordMode) {
            RecordMode.LANGSUNG -> {
                if (useContinuousGroq) {
                    b.tvStatus.text = "⏳ Memproses sisa audio..."
                    b.fabRecord.isEnabled = false
                    continuousRec?.stop { fullText, audioFile ->
                        savedAudioPath = audioFile?.absolutePath ?: ""
                        val text = fullText.ifBlank { appendedChunks.toString().trim() }
                        b.etTranscript.setText(text)
                        b.etTranscript.setSelection(text.length)
                        vm.updateText(text)
                        b.tvStatus.text = getString(R.string.status_idle)
                        b.fabRecord.isEnabled = true
                        b.btnSave.isEnabled = text.isNotBlank()
                        continuousRec = null
                    }
                } else {
                    speech?.stop()
                    b.btnSave.isEnabled = b.etTranscript.text?.isNotBlank() == true
                }
            }
            RecordMode.REKAM -> {
                val lang = vm.language.value ?: LanguageDetector.detectDeviceLanguage(requireContext())
                b.tvStatus.text = "Menyimpan rekaman..."
                audioRec?.stop { wavFile ->
                    audioRec?.destroy(); audioRec = null
                    if (wavFile != null) {
                        startActivity(android.content.Intent(requireContext(), TranscribeActivity::class.java).apply {
                            putExtra(TranscribeActivity.EXTRA_WAV_PATH, wavFile.absolutePath)
                            putExtra(TranscribeActivity.EXTRA_LANGUAGE, lang)
                            putExtra(TranscribeActivity.EXTRA_DURATION_MS, dur)
                        })
                    } else {
                        b.tvStatus.text = getString(R.string.status_idle)
                        Toast.makeText(requireContext(), "File rekaman tidak ditemukan", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ── Save / Clear ───────────────────────────────────────────────────────

    private fun setupSaveAndClear() {
        b.btnSave.setOnClickListener {
            val content = b.etTranscript.text?.toString() ?: return@setOnClickListener
            if (content.isBlank()) return@setOnClickListener
            showSaveDialog(content)
        }
        b.btnClear.setOnClickListener {
            if (b.etTranscript.text?.isNotBlank() == true) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.clear_confirm_title))
                    .setMessage(getString(R.string.clear_confirm_msg))
                    .setPositiveButton(getString(R.string.clear)) { _, _ ->
                        b.etTranscript.setText("")
                        speech?.clearAll()
                        b.tvTimer.text = "00:00"
                        b.btnSave.isEnabled = false
                        b.cardHint.isVisible = true
                        savedAudioPath = ""
                        appendedChunks.clear()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }

    private fun showSaveDialog(content: String) {
        val autoTitle = content.trim().split("\\s+".toRegex()).take(7).joinToString(" ")
            .let { if (content.split(" ").size > 7) "$it…" else it }
        val input = EditText(requireContext()).apply {
            setText(autoTitle); selectAll(); setPadding(48, 24, 48, 24)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.save_note))
            .setView(input)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val title = input.text.toString().trim()
                val dur = System.currentTimeMillis() - recordingStartMs
                vm.saveNote(
                    title       = title,
                    entries     = speech?.getEntries() ?: emptyList(),
                    plainText   = content,
                    language    = vm.language.value ?: "id-ID",
                    speakerNames = speech?.speakerNames?.toMap() ?: emptyMap(),
                    durationMs  = dur,
                    audioPath   = savedAudioPath
                )
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun setupTranscriptEdit() {
        b.etTranscript.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val words = s?.trim()?.split("\\s+".toRegex())?.filter { it.isNotEmpty() }?.size ?: 0
                b.tvWordCount.text = "$words ${getString(R.string.words)}"
                b.btnSave.isEnabled = s?.isNotBlank() == true && vm.isRecording.value != true
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
        })
    }

    private fun observeVM() {
        vm.saveResult.observe(viewLifecycleOwner) { id ->
            if (id > 0) {
                Toast.makeText(requireContext(), getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                b.etTranscript.setText("")
                speech?.clearAll()
                b.btnSave.isEnabled = false
                b.cardHint.isVisible = true
                b.tvTimer.text = "00:00"
                savedAudioPath = ""
                appendedChunks.clear()
            }
        }
        vm.error.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── SpeechHelper Callbacks (Mode Langsung STT fallback) ───────────────

    override fun onPartialResult(text: String) {
        activity?.runOnUiThread { b.tvPartial.text = "…$text" }
    }

    override fun onResult(entry: TranscriptEntry, plainText: String) {
        activity?.runOnUiThread {
            b.etTranscript.setText(plainText)
            b.etTranscript.setSelection(plainText.length)
            b.tvPartial.text = ""
            vm.updateText(plainText)
        }
    }

    override fun onTimestamp(entry: TranscriptEntry) {}
    override fun onError(message: String) {
        if (message.isNotBlank())
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
    }
    override fun onRmsChanged(rms: Float) {
        activity?.runOnUiThread { b.waveformView.setLevel(rms) }
    }
    override fun onListeningChanged(listening: Boolean) {}

    // ── Timer ──────────────────────────────────────────────────────────────

    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val ms = SystemClock.elapsedRealtime() - startElapsed
            val m = (ms / 60000).toInt()
            val s = ((ms % 60000) / 1000).toInt()
            b.tvTimer.text = String.format("%02d:%02d", m, s)
            handler.postDelayed(this, 1000)
        }
    }

    // ── Animations ─────────────────────────────────────────────────────────

    private fun startPulse() {
        pulseAnim = ObjectAnimator.ofFloat(b.fabRecord, "scaleX", 1f, 0.9f).apply {
            duration = 600; repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator(); start()
        }
        ObjectAnimator.ofFloat(b.fabRecord, "scaleY", 1f, 0.9f).apply {
            duration = 600; repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator(); start()
        }
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        b.fabRecord.scaleX = 1f; b.fabRecord.scaleY = 1f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speech?.destroy()
        audioRec?.destroy()
        continuousRec?.destroy()
        handler.removeCallbacks(timerTick)
        _b = null
    }
}
