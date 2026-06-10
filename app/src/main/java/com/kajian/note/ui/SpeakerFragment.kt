package com.kajian.note.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kajian.note.R
import com.kajian.note.databinding.FragmentSpeakerBinding
import com.kajian.note.databinding.ItemSpeakerSegmentBinding
import com.kajian.note.model.Note
import com.kajian.note.utils.AssemblyAITranscriber
import com.kajian.note.utils.AudioRecordManager
import com.kajian.note.utils.PreferencesManager
import com.kajian.note.utils.UserManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SpeakerFragment : Fragment() {

    private var _b: FragmentSpeakerBinding? = null
    private val b get() = _b!!
    private val vm: RecordViewModel by activityViewModels()
    private lateinit var prefs: PreferencesManager

    // Recording
    private var audioRec: AudioRecordManager? = null
    private var isRecording = false
    private var recordingStartMs = 0L
    private var savedAudioPath = ""

    // Speaker labels — user-defined names, default ke "Speaker A", "Speaker B", dll
    private val speakerLabels = mutableMapOf<String, String>()

    // Timer
    private val handler = Handler(Looper.getMainLooper())
    private var startElapsed = 0L

    // Segments adapter
    private lateinit var segmentsAdapter: SpeakerSegmentsAdapter

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(), getString(R.string.error_mic_permission), Toast.LENGTH_LONG).show()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSpeakerBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = PreferencesManager(requireContext())
        setupSpeakerCount()
        setupLangChips()
        setupRecordButton()
        setupSegmentsList()
        setupSaveButton()
        checkApiKey()
    }

    // ── API Key check ─────────────────────────────────────────────────────

    private fun checkApiKey() {
        if (!AssemblyAITranscriber.hasApiKey(requireContext())) {
            b.cardApiKeyWarning.visibility = View.VISIBLE
            b.btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        } else {
            b.cardApiKeyWarning.visibility = View.GONE
        }
    }

    private fun showApiKeyDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Masukkan AssemblyAI API Key"
            val current = AssemblyAITranscriber.getApiKey(requireContext())
            setText(current)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("AssemblyAI API Key")
            .setMessage("Dapatkan gratis di assemblyai.com\n→ Login → API Key")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val key = input.text.toString().trim()
                AssemblyAITranscriber.saveApiKey(requireContext(), key)
                if (key.isNotBlank()) {
                    b.cardApiKeyWarning.visibility = View.GONE
                    Toast.makeText(requireContext(), "✅ API Key tersimpan", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Speaker count chips ───────────────────────────────────────────────

    private fun setupSpeakerCount() {
        val chips = listOf(b.chipSpeakerAuto, b.chipSpeaker2, b.chipSpeaker3, b.chipSpeaker4)
        val values = listOf(0, 2, 3, 4)

        fun select(idx: Int) {
            chips.forEachIndexed { i, chip ->
                chip.isSelected = i == idx
                chip.setBackgroundResource(
                    if (i == idx) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive
                )
            }
            val count = values[idx]
            updateSpeakerLabels(count)
        }

        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener { select(i) }
        }
        select(1) // default: 2 speaker
    }

    private fun updateSpeakerLabels(count: Int) {
        // Reset labels
        speakerLabels.clear()
        val defaults = mapOf(
            "A" to "Speaker 1",
            "B" to "Speaker 2",
            "C" to "Speaker 3",
            "D" to "Speaker 4"
        )

        // Container untuk input fields
        b.layoutSpeakerLabels.removeAllViews()

        val letters = if (count == 0) listOf("A", "B") else ('A' until 'A' + count).map { it.toString() }

        letters.forEach { letter ->
            speakerLabels[letter] = defaults[letter] ?: "Speaker $letter"

            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_speaker_label_input, b.layoutSpeakerLabels, false)

            val dot = row.findViewById<View>(R.id.viewSpeakerDot)
            val input = row.findViewById<android.widget.EditText>(R.id.etSpeakerLabel)

            // Warna dot per speaker
            val dotColor = when (letter) {
                "A" -> R.color.speaker_a
                "B" -> R.color.speaker_b
                "C" -> R.color.speaker_c
                else -> R.color.speaker_d
            }
            dot.setBackgroundResource(R.drawable.bg_speaker_dot)
            dot.background.setTint(ContextCompat.getColor(requireContext(), dotColor))

            input.setText(speakerLabels[letter])
            input.hint = "Nama speaker $letter (opsional)"
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    speakerLabels[letter] = s?.toString()?.ifBlank { defaults[letter] ?: "Speaker $letter" }
                        ?: defaults[letter] ?: "Speaker $letter"
                    segmentsAdapter.updateLabels(speakerLabels.toMap())
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            })

            b.layoutSpeakerLabels.addView(row)
        }
    }

    // ── Language chips ────────────────────────────────────────────────────

    private fun setupLangChips() {
        val chips = listOf(b.chipLangId, b.chipLangAr, b.chipLangAuto)
        val langs = listOf("id", "ar", "auto")

        fun select(idx: Int) {
            chips.forEachIndexed { i, chip ->
                chip.isSelected = i == idx
                chip.setBackgroundResource(
                    if (i == idx) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive
                )
            }
        }

        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener { select(i) }
        }
        select(0) // default: Indonesia
    }

    // ── Record button ─────────────────────────────────────────────────────

    private fun setupRecordButton() {
        b.btnRecord.setOnClickListener {
            if (isRecording) stopRecording()
            else {
                val hasPerm = ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) startRecording()
                else micPerm.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startRecording() {
        if (!AssemblyAITranscriber.hasApiKey(requireContext())) {
            showApiKeyDialog()
            return
        }

        val outputFile = File(
            requireContext().cacheDir,
            "speaker_${System.currentTimeMillis()}.wav"
        )

        audioRec = AudioRecordManager(requireContext())
        audioRec?.startRecording(outputFile.absolutePath)
        savedAudioPath = outputFile.absolutePath

        isRecording = true
        recordingStartMs = SystemClock.elapsedRealtime()
        startElapsed = 0L

        // Update UI
        b.btnRecord.setImageResource(R.drawable.ic_stop)
        b.btnRecord.setColorFilter(ContextCompat.getColor(requireContext(), R.color.recording_red))
        b.tvRecordStatus.text = "Merekam..."
        b.tvRecordStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.recording_red))
        b.segmentsContainer.visibility = View.GONE
        b.tvPlaceholder.visibility = View.VISIBLE
        b.tvPlaceholder.text = "Rekaman sedang berjalan...\nHasil akan muncul setelah selesai."

        // Disable config UI saat rekam
        b.chipSpeakerAuto.isEnabled = false
        b.chipSpeaker2.isEnabled = false
        b.chipSpeaker3.isEnabled = false
        b.chipSpeaker4.isEnabled = false

        startTimer()
    }

    private fun stopRecording() {
        audioRec?.stopRecording()
        isRecording = false
        stopTimer()

        b.btnRecord.setImageResource(R.drawable.ic_mic)
        b.btnRecord.clearColorFilter()
        b.tvRecordStatus.text = "Memproses..."
        b.tvRecordStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        b.chipSpeakerAuto.isEnabled = true
        b.chipSpeaker2.isEnabled = true
        b.chipSpeaker3.isEnabled = true
        b.chipSpeaker4.isEnabled = true

        if (savedAudioPath.isNotBlank()) {
            processAudio(File(savedAudioPath))
        }
    }

    private fun processAudio(file: File) {
        // Determine selected language
        val lang = when {
            b.chipLangAr.isSelected -> "ar"
            b.chipLangAuto.isSelected -> "id"
            else -> "id"
        }

        // Determine max speakers
        val maxSpeakers = when {
            b.chipSpeaker2.isSelected -> 2
            b.chipSpeaker3.isSelected -> 3
            b.chipSpeaker4.isSelected -> 4
            else -> 0
        }

        b.progressBar.visibility = View.VISIBLE
        b.tvPlaceholder.text = "Mengunggah audio ke AssemblyAI..."

        lifecycleScope.launch {
            try {
                val result = AssemblyAITranscriber.transcribe(
                    ctx = requireContext(),
                    audioFile = file,
                    language = lang,
                    maxSpeakers = maxSpeakers
                ) { progress, message ->
                    activity?.runOnUiThread {
                        b.progressBar.progress = progress
                        b.tvPlaceholder.text = message
                    }
                }

                activity?.runOnUiThread {
                    b.progressBar.visibility = View.GONE
                    b.tvRecordStatus.text = "Selesai — ${result.speakerCount} speaker teridentifikasi"
                    b.tvRecordStatus.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.primary_green)
                    )
                    showSegments(result)
                }

                // Clean up temp file
                file.delete()

            } catch (e: Exception) {
                activity?.runOnUiThread {
                    b.progressBar.visibility = View.GONE
                    b.tvPlaceholder.visibility = View.VISIBLE
                    b.tvPlaceholder.text = "Error: ${e.message}"
                    b.tvRecordStatus.text = "Gagal memproses"
                    b.tvRecordStatus.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.recording_red)
                    )
                }
            }
        }
    }

    private fun showSegments(result: AssemblyAITranscriber.DiarizationResult) {
        b.tvPlaceholder.visibility = View.GONE
        b.segmentsContainer.visibility = View.VISIBLE
        b.btnSaveNote.isEnabled = true

        segmentsAdapter.submitSegments(result.segments, speakerLabels.toMap())

        // Store full text for saving
        b.btnSaveNote.tag = result
    }

    // ── Segments RecyclerView ─────────────────────────────────────────────

    private fun setupSegmentsList() {
        segmentsAdapter = SpeakerSegmentsAdapter()
        b.rvSegments.layoutManager = LinearLayoutManager(requireContext())
        b.rvSegments.adapter = segmentsAdapter
        b.btnSaveNote.isEnabled = false
    }

    // ── Save note ─────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        b.btnSaveNote.setOnClickListener {
            val result = b.btnSaveNote.tag as? AssemblyAITranscriber.DiarizationResult ?: return@setOnClickListener

            // Build formatted transcript
            val sb = StringBuilder()
            result.segments.forEach { seg ->
                val label = speakerLabels[seg.speaker] ?: "Speaker ${seg.speaker}"
                sb.append("[$label]: ${seg.text}\n\n")
            }

            val note = Note(
                title = "Kajian ${SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date())}",
                transcript = sb.toString().trim(),
                summary = "",
                language = "id",
                duration = (SystemClock.elapsedRealtime() - recordingStartMs) / 1000,
                wordCount = result.fullText.split(" ").size,
                speakerCount = result.speakerCount
            )

            lifecycleScope.launch {
                vm.saveNote(note)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "✅ Catatan tersimpan!", Toast.LENGTH_SHORT).show()
                    // Reset UI
                    segmentsAdapter.clear()
                    b.segmentsContainer.visibility = View.GONE
                    b.tvPlaceholder.visibility = View.VISIBLE
                    b.tvPlaceholder.text = "Tap tombol rekam untuk mulai mencatat kajian."
                    b.tvRecordStatus.text = "Siap merekam"
                    b.tvRecordStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    b.tvTimer.text = "00:00"
                    b.btnSaveNote.isEnabled = false
                    b.btnSaveNote.tag = null
                }
            }
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - recordingStartMs
            val min = (elapsed / 60000).toInt()
            val sec = ((elapsed % 60000) / 1000).toInt()
            b.tvTimer.text = "%02d:%02d".format(min, sec)
            handler.postDelayed(this, 1000)
        }
    }

    private fun startTimer() {
        b.tvTimer.text = "00:00"
        handler.post(timerRunnable)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTimer()
        audioRec?.stopRecording()
        _b = null
    }
}

// ── SpeakerSegmentsAdapter ────────────────────────────────────────────────────

class SpeakerSegmentsAdapter : RecyclerView.Adapter<SpeakerSegmentsAdapter.VH>() {

    private val items = mutableListOf<AssemblyAITranscriber.SpeakerSegment>()
    private var labels = mapOf<String, String>()

    private val SPEAKER_COLORS = listOf(
        R.color.speaker_a,
        R.color.speaker_b,
        R.color.speaker_c,
        R.color.speaker_d
    )

    fun submitSegments(
        segments: List<AssemblyAITranscriber.SpeakerSegment>,
        speakerLabels: Map<String, String>
    ) {
        items.clear()
        items.addAll(segments)
        labels = speakerLabels
        notifyDataSetChanged()
    }

    fun updateLabels(newLabels: Map<String, String>) {
        labels = newLabels
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemSpeakerSegmentBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemSpeakerSegmentBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val seg = items[pos]
        val ctx = h.itemView.context

        // Speaker letter → index (A=0, B=1, dll)
        val letterIdx = (seg.speaker.firstOrNull()?.code ?: 65) - 65
        val colorRes = SPEAKER_COLORS.getOrElse(letterIdx) { R.color.speaker_a }
        val color = ContextCompat.getColor(ctx, colorRes)

        val label = labels[seg.speaker] ?: "Speaker ${seg.speaker}"

        h.b.apply {
            tvSpeakerLabel.text = label
            tvSpeakerLabel.setTextColor(color)
            tvSpeakerLabel.background.setTint(
                ContextCompat.getColor(ctx, R.color.surface_variant)
            )
            viewSpeakerAccent.setBackgroundColor(color)
            tvSegmentText.text = seg.text

            // Timestamp
            val startSec = seg.startMs / 1000
            tvTimestamp.text = "%d:%02d".format(startSec / 60, startSec % 60)
        }
    }
}
