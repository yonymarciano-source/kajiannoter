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

        setupChips()
        setupInfoButtons()
        setupRecordButton()
        setupSaveAndClear()
        setupTranscriptEdit()
        observeVM()
    }

    // ── Chip Selector UI ───────────────────────────────────────────────────

    private fun setupChips() {
        // Restore saved state
        val savedLang = prefs.getRecordingLanguage()
        val savedMode = prefs.getRecordMode()
        val savedAudio = prefs.getAudioMode()
        val hasGroqKey = GroqTranscriber.hasApiKey(requireContext())

        // Init recordMode
        recordMode = when (savedMode) {
            "REKAM" -> RecordMode.REKAM
            "STT"   -> RecordMode.LANGSUNG
            else    -> if (hasGroqKey) RecordMode.LANGSUNG else RecordMode.LANGSUNG
        }

        // Set initial chip states
        setLangChip(savedLang)
        setModeChip(savedMode, hasGroqKey)
        setAudioChip(savedAudio)

        // Apply initial language to speech engine
        val resolved = if (savedLang == "auto")
            LanguageDetector.detectDeviceLanguage(requireContext()) else savedLang
        speech?.currentLanguage = resolved
        vm.setLanguage(resolved)

        // Language chips
        b.chipLangId.setOnClickListener   { selectLang("id") }
        b.chipLangEn.setOnClickListener   { selectLang("en") }
        b.chipLangAr.setOnClickListener   { selectLang("ar") }
        b.chipLangAuto.setOnClickListener { selectLang("auto") }

        // Mode chips
        b.chipModeGroq.setOnClickListener  { selectMode("GROQ") }
        b.chipModeStt.setOnClickListener   { selectMode("STT") }
        b.chipModeRekam.setOnClickListener { selectMode("REKAM") }

        // Audio chips
        b.chipAudioNormal.setOnClickListener  { selectAudio("NORMAL") }
        b.chipAudioSpeaker.setOnClickListener { selectAudio("SPEAKER") }
    }

    private fun selectLang(langCode: String) {
        prefs.setRecordingLanguage(langCode)
        val resolved = if (langCode == "auto")
            LanguageDetector.detectDeviceLanguage(requireContext()) else langCode
        speech?.currentLanguage = resolved
        vm.setLanguage(resolved)
        setLangChip(langCode)
    }

    private fun selectMode(mode: String) {
        prefs.setRecordMode(mode)
        recordMode = when (mode) {
            "REKAM" -> RecordMode.REKAM
            else    -> RecordMode.LANGSUNG
        }
        val hasGroqKey = GroqTranscriber.hasApiKey(requireContext())
        setModeChip(mode, hasGroqKey)
    }

    private fun selectAudio(mode: String) {
        val sens = prefs.getMicSensitivity()
        val audioMode = if (mode == "SPEAKER") AudioSourceManager.Mode.SPEAKER
                        else AudioSourceManager.Mode.NORMAL
        speech?.audioConfig = AudioSourceManager.buildConfig(audioMode, sens)
        prefs.setAudioMode(mode)
        setAudioChip(mode)
    }

    private fun setLangChip(savedLang: String) {
        val allChips = listOf(b.chipLangId, b.chipLangEn, b.chipLangAr, b.chipLangAuto)
        allChips.forEach { setChipInactive(it) }
        when (savedLang) {
            "id", "id-ID" -> setChipActive(b.chipLangId)
            "en", "en-US" -> setChipActive(b.chipLangEn)
            "ar", "ar-SA" -> setChipActive(b.chipLangAr)
            "auto"        -> setChipActive(b.chipLangAuto)
            // Bahasa lain dari Settings → tampilkan Auto aktif dengan tanda
            else          -> setChipActive(b.chipLangAuto)
        }
    }

    private fun setModeChip(mode: String, hasGroqKey: Boolean) {
        val allChips = listOf(b.chipModeGroq, b.chipModeStt, b.chipModeRekam)
        allChips.forEach { setChipInactive(it) }
        when (mode) {
            "REKAM" -> setChipActive(b.chipModeRekam)
            "STT"   -> setChipActive(b.chipModeStt)
            else    -> if (hasGroqKey) setChipActive(b.chipModeGroq)
                       else setChipActive(b.chipModeStt)
        }
        // Sembunyikan chip Groq jika tidak ada key
        b.chipModeGroq.visibility = if (hasGroqKey) View.VISIBLE else View.GONE
    }

    private fun setAudioChip(mode: String) {
        val allChips = listOf(b.chipAudioNormal, b.chipAudioSpeaker)
        allChips.forEach { setChipInactive(it) }
        if (mode == "SPEAKER") setChipActive(b.chipAudioSpeaker)
        else setChipActive(b.chipAudioNormal)
    }

    private fun setChipActive(chip: android.widget.TextView) {
        chip.setBackgroundResource(R.drawable.bg_chip_active)
        chip.setTextColor(resources.getColor(R.color.primary_green, null))
    }

    private fun setChipInactive(chip: android.widget.TextView) {
        chip.setBackgroundResource(R.drawable.bg_chip_inactive)
        chip.setTextColor(0xFF666666.toInt())
    }

    private fun setupInfoButtons() {
        b.btnInfoLang.setOnClickListener {
            showInfoDialog("Bahasa Transkripsi",
                "🌐 Auto: Whisper otomatis deteksi bahasa termasuk campuran Indonesia-Arab.\n\n" +
                "🇮🇩 Indonesia: Optimal untuk kajian Islam Bahasa Indonesia.\n\n" +
                "🌙 Arab: Untuk kajian full Bahasa Arab.\n\n" +
                "💡 Tip: Pakai Auto jika kajian campur Arab-Indonesia.")
        }
        b.btnInfoMode.setOnClickListener {
            val hasGroq = GroqTranscriber.hasApiKey(requireContext())
            showInfoDialog("Mode Rekam",
                if (hasGroq)
                    "⚡ Langsung (Groq): Rekam + kirim ke Groq Whisper tiap ~25 detik. Paling akurat, support Arab otomatis.\n\n" +
                    "🎤 Langsung (STT): Real-time via Android STT. Teks muncul langsung tapi kurang akurat untuk Arab.\n\n" +
                    "🔴 Rekam dulu: Rekam audio penuh → transkripsi setelah selesai. Cocok untuk kajian panjang."
                else
                    "🎤 Langsung (STT): Real-time via Android STT.\n\n" +
                    "🔴 Rekam dulu: Rekam audio penuh → transkripsi setelah selesai.\n\n" +
                    "💡 Set Groq API Key di Settings untuk mode Langsung (Groq) yang lebih akurat.")
        }
        b.btnInfoAudio.setOnClickListener {
            showInfoDialog("Sumber Audio",
                "🎤 Normal: Mikrofon HP langsung. Cocok untuk bicara dekat dengan HP.\n\n" +
                "📢 Speaker: Sensitivitas tinggi untuk merekam dari speaker/proyektor. Cocok di masjid atau ruangan besar.")
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
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
        val savedMode = prefs.getRecordMode()

        when {
            savedMode == "REKAM" -> {
                useContinuousGroq = false
                b.etTranscript.setText("🔴 Merekam audio...\nTranskripsi akan diproses setelah recording selesai.")
                b.tvPartial.text = "🔴 Merekam..."
                audioRec = AudioRecordManager { rms ->
                    activity?.runOnUiThread { b.waveformView.setLevel(rms) }
                }
                audioRec?.start(requireContext().cacheDir)
            }
            hasGroqKey && savedMode != "STT" -> {
                // Mode Langsung Groq
                useContinuousGroq = true
                b.tvPartial.text = "🎙️ Merekam... teks muncul tiap ~25 detik"
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
            }
            else -> {
                // Mode Langsung STT (fallback)
                useContinuousGroq = false
                speech?.start(lang)
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
        val savedMode = prefs.getRecordMode()

        when {
            savedMode != "REKAM" -> {
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
            else -> {
                val lang2 = vm.language.value ?: LanguageDetector.detectDeviceLanguage(requireContext())
                b.tvStatus.text = "Menyimpan rekaman..."
                audioRec?.stop { wavFile ->
                    audioRec?.destroy(); audioRec = null
                    if (wavFile != null) {
                        startActivity(android.content.Intent(requireContext(), TranscribeActivity::class.java).apply {
                            putExtra(TranscribeActivity.EXTRA_WAV_PATH, wavFile.absolutePath)
                            putExtra(TranscribeActivity.EXTRA_LANGUAGE, lang2)
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

    override fun onResume() {
        super.onResume()
        // Sync chip bahasa kalau user ubah di Settings
        val savedLang = prefs.getRecordingLanguage()
        setLangChip(savedLang)
        val resolved = if (savedLang == "auto")
            LanguageDetector.detectDeviceLanguage(requireContext()) else savedLang
        speech?.currentLanguage = resolved
        vm.setLanguage(resolved)
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
