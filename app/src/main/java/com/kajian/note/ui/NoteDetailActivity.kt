package com.kajian.note.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.*
import android.text.style.*
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kajian.note.R
import com.kajian.note.databinding.ActivityNoteDetailBinding
import com.kajian.note.model.Note
import com.kajian.note.model.TranscriptEntry
import com.kajian.note.utils.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File

class NoteDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "note_id"
        private const val REQ_SUMMARY_EDIT = 1001
    }

    private lateinit var b: ActivityNoteDetailBinding
    private val vm: RecordViewModel by viewModels()
    private var note: Note? = null
    private val gson = Gson()
    private lateinit var prefs: PreferencesManager
    private val player = AudioPlayer()
    private var playerLoaded = false
    private var currentSpeed = 1.0f
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 1

    // Summary editor state
    private var summaryEdited = false
    private var summaryDraft = ""

    // Current tab
    private var currentTab = 0  // 0=transcript, 1=summary, 2=mindmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNoteDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = PreferencesManager(this)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id == -1L) { finish(); return }

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupTabs()
        setupPlayerControls()
        setupBackPressHandler()

        loadNote(id)
    }

    // ── Back press — discard confirm if summary edited ─────────────────────

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (summaryEdited) {
                    showDiscardDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showDiscardDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard Changes?")
            .setMessage("Do you want to go back? Your changes will not be saved.")
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNegativeButton("No", null)
            .show()
    }

    // ── Tabs ──────────────────────────────────────────────────────────────

    private fun setupTabs() {
        b.tabLayout.addTab(b.tabLayout.newTab().setText("📝 Transcript"))
        b.tabLayout.addTab(b.tabLayout.newTab().setText("✦ Summary"))
        b.tabLayout.addTab(b.tabLayout.newTab().setText("🗺 Mindmap"))

        b.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                showTab(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showTab(index: Int) {
        val n = note ?: return
        b.panelTranscript.isVisible = index == 0
        b.panelSummary.isVisible    = index == 1
        b.panelMindmap.isVisible    = index == 2

        when (index) {
            1 -> loadSummaryPanel(n)
            2 -> loadMindmapPanel(n)
        }
    }

    // ── Load note ─────────────────────────────────────────────────────────

    private fun loadNote(id: Long) {
        vm.allNotes.observe(this) { list ->
            val n = list.find { it.id == id }
            if (n != null && note?.updatedAt != n.updatedAt) {
                note = n
                renderNote(n)
            }
        }
    }

    private fun renderNote(n: Note) {
        supportActionBar?.title = n.title.ifBlank { "Note ${n.id}" }
        b.tvTitle.text  = n.title.ifBlank { "Note ${n.id}" }
        b.tvMeta.text   = "${n.getFormattedDate()} · ${n.getLanguageLabel()} · ${n.getFormattedDuration()} · ${n.wordCount} kata"

        if (n.speakerCount > 1) {
            b.tvSpeakerCount.text = "👥 ${n.speakerCount} speakers"
            b.tvSpeakerCount.isVisible = true
        }

        if (n.hasAudio()) {
            setupAudioPlayer(n)
            b.cardPlayer.isVisible = true
        } else {
            b.cardPlayer.isVisible = false
        }

        // Transcript panel — dengan segmentasi per 3 kalimat
        try {
            val type = object : TypeToken<List<TranscriptEntry>>() {}.type
            val entries: List<TranscriptEntry> = gson.fromJson(n.transcriptJson, type)
            if (entries.isNotEmpty()) {
                b.tvTranscript.text = buildRichTranscript(entries, n)
            } else {
                val raw = n.plainText.ifBlank { "(Belum ada transkripsi)" }
                b.tvTranscript.text = buildSegmentedTranscript(raw)
            }
        } catch (e: Exception) {
            val raw = n.plainText.ifBlank { "(Belum ada transkripsi)" }
            b.tvTranscript.text = buildSegmentedTranscript(raw)
        }

        b.btnShare.setOnClickListener  { shareNote(n) }
        b.btnCopy.setOnClickListener   { copyNote(n) }
        b.btnEdit.setOnClickListener   { toggleEdit(n) }
        b.btnExport.setOnClickListener { showExportDialog(n) }

        if (currentTab != 0) showTab(currentTab)
    }

    // ── Summary Panel (#8 editor + #9 save + #10 discard) ────────────────

    private fun loadSummaryPanel(n: Note) {
        val existingSummary = n.summaryText
        summaryDraft = existingSummary
        summaryEdited = false

        if (existingSummary.isNotBlank()) {
            showSummaryContent(existingSummary, n)
        } else {
            b.tvSummaryEmpty.isVisible   = true
            b.cardSummaryContent.isVisible = false
            b.tvSummaryEmpty.text = "Belum ada ringkasan.\n\nTap tombol di bawah untuk generate otomatis via Groq AI."
            b.btnGenerateSummary.isVisible = true
        }

        b.btnGenerateSummary.setOnClickListener { generateSummary(n) }
        b.btnSaveSummary.setOnClickListener     { saveSummary(n) }
        b.btnEditSummary.setOnClickListener     { openSummaryEditor(n) }
        b.btnRedoSummary.setOnClickListener     { generateSummary(n) }
    }

    private fun showSummaryContent(text: String, n: Note) {
        b.tvSummaryEmpty.isVisible     = false
        b.btnGenerateSummary.isVisible = false
        b.cardSummaryContent.isVisible = true
        b.tvSummaryContent.text = text
        b.btnSaveSummary.isVisible = summaryEdited
        b.btnEditSummary.isVisible = true
        b.btnRedoSummary.isVisible = true
    }

    private fun generateSummary(n: Note) {
        b.btnGenerateSummary.isEnabled = false
        b.btnRedoSummary.isEnabled = false
        b.tvSummaryEmpty.text = "⏳ Membuat ringkasan via Groq AI..."
        b.tvSummaryEmpty.isVisible = true
        b.cardSummaryContent.isVisible = false

        lifecycleScope.launch {
            val result = GroqSummarizer.summarize(
                ctx          = this@NoteDetailActivity,
                title        = n.title,
                plainText    = n.plainText,
                detectedLang = n.detectedLanguage
            )
            b.btnGenerateSummary.isEnabled = true
            b.btnRedoSummary.isEnabled = true

            result.onSuccess { summary ->
                summaryDraft = summary
                summaryEdited = true
                showSummaryContent(summary, n)
                // Auto-save summary to note
                saveSummaryToNote(n, summary)
            }.onFailure { e ->
                b.tvSummaryEmpty.text = "❌ Gagal: ${e.message}\n\nPastikan Groq API Key sudah diset di Settings."
                b.btnGenerateSummary.isVisible = true
            }
        }
    }

    private fun saveSummary(n: Note) {
        saveSummaryToNote(n, summaryDraft)
        summaryEdited = false
        b.btnSaveSummary.isVisible = false
        Toast.makeText(this, "✅ Ringkasan tersimpan", Toast.LENGTH_SHORT).show()
    }

    private fun saveSummaryToNote(n: Note, summary: String) {
        vm.updateNote(n.copy(summaryText = summary, updatedAt = System.currentTimeMillis()))
    }

    private fun openSummaryEditor(n: Note) {
        val intent = Intent(this, SummaryEditorActivity::class.java).apply {
            putExtra(SummaryEditorActivity.EXTRA_NOTE_ID, n.id)
            putExtra(SummaryEditorActivity.EXTRA_SUMMARY, summaryDraft.ifBlank { n.summaryText })
        }
        startActivityForResult(intent, REQ_SUMMARY_EDIT)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SUMMARY_EDIT && resultCode == RESULT_OK) {
            val saved = data?.getStringExtra(SummaryEditorActivity.RESULT_SUMMARY) ?: return
            val n = note ?: return
            summaryDraft = saved
            summaryEdited = false
            saveSummaryToNote(n, saved)
            showSummaryContent(saved, n)
        }
    }

    // ── Mindmap Panel (#7 mindmap via Groq) ───────────────────────────────

    private fun loadMindmapPanel(n: Note, retryCount: Int = 0) {
        if (b.mindmapView.tag == n.id) return

        b.tvMindmapLoading.isVisible = true
        b.tvMindmapLoading.text = if (retryCount > 0)
            "⏳ Mencoba ulang... ($retryCount/3)"
        else "⏳ Generating mindmap via Groq AI..."
        b.mindmapView.isVisible = false
        b.btnGenerateMindmap.isVisible = false

        lifecycleScope.launch {
            val result = MindmapGenerator.generate(
                ctx          = this@NoteDetailActivity,
                title        = n.title,
                plainText    = n.plainText,
                detectedLang = n.detectedLanguage
            )
            b.tvMindmapLoading.isVisible = false
            result.onSuccess { mindmap ->
                if (mindmap.nodes.isEmpty() && retryCount < 3) {
                    // Retry jika nodes kosong
                    delay(1000)
                    loadMindmapPanel(n, retryCount + 1)
                    return@launch
                }
                b.mindmapView.setMindmap(mindmap)
                b.mindmapView.isVisible = true
                b.mindmapView.tag = n.id
            }.onFailure {
                if (retryCount < 2) {
                    // Auto retry 2x sebelum tampil error
                    delay(1500)
                    loadMindmapPanel(n, retryCount + 1)
                } else {
                    b.tvMindmapLoading.text = "❌ Gagal generate mindmap setelah 3x percobaan.\nPastikan Groq key diset di Settings."
                    b.tvMindmapLoading.isVisible = true
                    b.btnGenerateMindmap.isVisible = true
                    b.btnGenerateMindmap.text = "↻ Coba Lagi"
                    b.btnGenerateMindmap.setOnClickListener {
                        b.mindmapView.tag = null
                        loadMindmapPanel(n, 0)
                    }
                }
            }
        }
    }

    // ── Audio Player (#4 fix + #5 highlight) ─────────────────────────────

    private fun setupAudioPlayer(n: Note) {
        val file = File(n.audioPath)
        if (!file.exists()) { b.cardPlayer.isVisible = false; return }

        if (!playerLoaded) {
            playerLoaded = player.load(file,
                onProgress = { cur, total ->
                    if (total > 0) {
                        b.seekBar.max      = total
                        b.seekBar.progress = cur
                        b.tvCurrentTime.text = player.formatTime(cur)
                        b.tvTotalTime.text   = player.formatTime(total)
                        // #5 highlight teks sesuai posisi audio
                        highlightTranscriptAt(cur, n)
                    }
                },
                onComplete = {
                    b.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    b.seekBar.progress = 0
                }
            )
            b.tvTotalTime.text = player.formatTime(player.duration)
        }
    }

    private fun setupPlayerControls() {
        b.btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                b.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                player.play()
                b.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
        b.btnSkipBack.setOnClickListener    { player.skipBackward(5) }
        b.btnSkipForward.setOnClickListener { player.skipForward(5) }
        b.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            currentSpeed = speeds[speedIndex]
            player.setSpeed(currentSpeed)
            b.btnSpeed.text = "${currentSpeed}x"
        }
        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { player.seekTo(p); b.tvCurrentTime.text = player.formatTime(p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // #5 — Highlight teks transcript berdasarkan posisi audio
    private fun highlightTranscriptAt(posMs: Int, n: Note) {
        if (currentTab != 0) return
        try {
            val type = object : TypeToken<List<TranscriptEntry>>() {}.type
            val entries: List<TranscriptEntry> = gson.fromJson(n.transcriptJson, type)
            if (entries.isEmpty()) return

            // Cari entry terdekat dengan posisi audio
            val target = entries.filter { it.timeMs > 0 }
                .lastOrNull { it.timeMs <= posMs } ?: entries.firstOrNull() ?: return

            // Rebuild text dengan highlight pada entry target
            val ssb = buildRichTranscriptHighlighted(entries, n, target)
            b.tvTranscript.text = ssb

            // Scroll ke posisi entry
            val charPos = ssb.toString().indexOf(target.text.take(10))
            if (charPos > 0) {
                val layout = b.tvTranscript.layout ?: return
                val line = layout.getLineForOffset(charPos)
                val lineTop = layout.getLineTop(line)
                b.scrollTranscript.smoothScrollTo(0, lineTop)
            }
        } catch (_: Exception) {}
    }

    // ── Rich transcript ───────────────────────────────────────────────────

    private fun buildRichTranscript(entries: List<TranscriptEntry>, note: Note) =
        buildRichTranscriptHighlighted(entries, note, null)

    private fun buildRichTranscriptHighlighted(
        entries: List<TranscriptEntry>, note: Note, highlightEntry: TranscriptEntry?
    ): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val speakerOverrides: Map<String, String> = try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(note.speakerNamesJson, type) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }

        fun getDisplayName(e: TranscriptEntry) =
            speakerOverrides[e.speakerIndex.toString()] ?: e.speakerName

        var lastSpeaker = -1
        var speechEntryCount = 0
        entries.forEach { entry ->
            when (entry.type) {
                TranscriptEntry.TYPE_TIMESTAMP -> {
                    ssb.append("\n")
                    val s = ssb.length
                    ssb.append("  ⏱ ${entry.getFormattedTime()}  ")
                    val e = ssb.length
                    ssb.setSpan(ForegroundColorSpan(0xFF888888.toInt()), s, e, 0)
                    ssb.setSpan(RelativeSizeSpan(0.85f), s, e, 0)
                    ssb.append("\n\n")
                }
                TranscriptEntry.TYPE_SPEAKER_CHANGE -> {
                    val s = ssb.length
                    ssb.append("▶ ${getDisplayName(entry)}")
                    val e = ssb.length
                    ssb.setSpan(StyleSpan(Typeface.BOLD), s, e, 0)
                    ssb.setSpan(ForegroundColorSpan(entry.getSpeakerColor()), s, e, 0)
                    ssb.append("\n")
                }
                TranscriptEntry.TYPE_SPEECH -> {
                    if (entry.speakerIndex != lastSpeaker) {
                        lastSpeaker = entry.speakerIndex
                        ssb.append("\n")
                        val ls = ssb.length
                        ssb.append("● ${getDisplayName(entry)}")
                        val le = ssb.length
                        ssb.setSpan(StyleSpan(Typeface.BOLD), ls, le, 0)
                        ssb.setSpan(ForegroundColorSpan(entry.getSpeakerColor()), ls, le, 0)
                        ssb.setSpan(RelativeSizeSpan(0.9f), ls, le, 0)
                        ssb.append("  ")
                        val ts2 = ssb.length
                        ssb.append(entry.getFormattedTime())
                        val te2 = ssb.length
                        ssb.setSpan(ForegroundColorSpan(0xFFAAAAAA.toInt()), ts2, te2, 0)
                        ssb.setSpan(RelativeSizeSpan(0.8f), ts2, te2, 0)
                        ssb.append("\n")
                        speechEntryCount = 0
                    }
                    val ts = ssb.length
                    ssb.append(entry.text)
                    val te = ssb.length
                    if (entry == highlightEntry) {
                        ssb.setSpan(BackgroundColorSpan(0x331DB954.toInt()), ts, te, 0)
                        ssb.setSpan(StyleSpan(Typeface.BOLD), ts, te, 0)
                    }
                    ssb.append(" ")
                    speechEntryCount++
                    // Divider setiap 3 entry speech
                    if (speechEntryCount % 3 == 0) {
                        ssb.append("\n")
                        val divStart = ssb.length
                        ssb.append("─────────────────────")
                        val divEnd = ssb.length
                        ssb.setSpan(ForegroundColorSpan(0xFFDDDDDD.toInt()), divStart, divEnd, 0)
                        ssb.setSpan(RelativeSizeSpan(0.6f), divStart, divEnd, 0)
                        ssb.append("\n")
                    }
                }
            }
        }
        return ssb
    }

    // ── Segmented transcript (plainText tanpa entries) ────────────────────

    private fun buildSegmentedTranscript(text: String): SpannableStringBuilder {
        if (text == "(Belum ada transkripsi)") {
            return SpannableStringBuilder(text)
        }
        val paragraphs = TranscriptSegmenter.segment(text)
        val ssb = SpannableStringBuilder()
        val dividerColor = 0xFFDDDDDD.toInt()
        val numColor     = 0xFF1DB954.toInt()

        paragraphs.forEachIndexed { index, para ->
            val numStart = ssb.length
            ssb.append("${index + 1}")
            val numEnd = ssb.length
            ssb.setSpan(ForegroundColorSpan(numColor), numStart, numEnd, 0)
            ssb.setSpan(RelativeSizeSpan(0.72f), numStart, numEnd, 0)
            ssb.setSpan(StyleSpan(Typeface.BOLD), numStart, numEnd, 0)
            ssb.append("  ")
            ssb.append(para)
            if (index < paragraphs.lastIndex) {
                ssb.append("\n\n")
                val divStart = ssb.length
                ssb.append("─────────────────────")
                val divEnd = ssb.length
                ssb.setSpan(ForegroundColorSpan(dividerColor), divStart, divEnd, 0)
                ssb.setSpan(RelativeSizeSpan(0.6f), divStart, divEnd, 0)
                ssb.append("\n\n")
            }
        }
        return ssb
    }

    // ── Edit Mode ─────────────────────────────────────────────────────────

    private var isEditMode = false

    private fun toggleEdit(n: Note) {
        isEditMode = !isEditMode
        if (isEditMode) {
            b.tvTranscript.isVisible    = false
            b.etTranscriptEdit.isVisible = true
            b.etTranscriptEdit.setText(n.plainText)
            b.btnEdit.setImageResource(R.drawable.ic_save)
        } else {
            val newText = b.etTranscriptEdit.text?.toString() ?: n.plainText
            b.tvTranscript.isVisible    = true
            b.etTranscriptEdit.isVisible = false
            b.btnEdit.setImageResource(R.drawable.ic_edit)
            vm.updateNote(n.copy(
                plainText = newText,
                wordCount = newText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size,
                updatedAt = System.currentTimeMillis()
            ))
            Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Export ────────────────────────────────────────────────────────────

    private fun showExportDialog(n: Note) {
        val options = arrayOf("📄 PDF saja", "📝 Word (DOCX) saja", "📦 PDF + DOCX (keduanya)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Export Catatan")
            .setItems(options) { _, which -> startExport(n, which) }
            .setNegativeButton("Batal", null).show()
    }

    private fun startExport(n: Note, exportType: Int) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("Menyiapkan Export...")
            .setMessage("Membuat ringkasan AI via Groq...")
            .setCancelable(false).create()
        progress.show()

        lifecycleScope.launch {
            try {
                // Pakai summary yang sudah ada, atau generate baru
                val summary = n.summaryText.ifBlank {
                    GroqSummarizer.summarize(this@NoteDetailActivity, n.title, n.plainText, n.detectedLanguage).getOrNull()
                }
                progress.setMessage("Membuat file dokumen...")

                val files = mutableListOf<File>()
                when (exportType) {
                    0 -> ExportManager.exportPdf(this@NoteDetailActivity, n, summary).onSuccess { files.add(it) }.onFailure { throw it }
                    1 -> ExportManager.exportDocx(this@NoteDetailActivity, n, summary).onSuccess { files.add(it) }.onFailure { throw it }
                    2 -> {
                        ExportManager.exportPdf(this@NoteDetailActivity, n, summary).onSuccess { files.add(it) }
                        ExportManager.exportDocx(this@NoteDetailActivity, n, summary).onSuccess { files.add(it) }
                        if (files.isEmpty()) throw Exception("Gagal membuat file")
                    }
                }
                progress.dismiss()
                if (files.isEmpty()) { Toast.makeText(this@NoteDetailActivity, "Gagal", Toast.LENGTH_LONG).show(); return@launch }
                showExportSuccess(files, summary != null)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@NoteDetailActivity, "Export gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showExportSuccess(files: List<File>, hasSummary: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle("✅ Export Berhasil${if (hasSummary) " (dengan ringkasan AI)" else ""}")
            .setMessage("File: ${files.joinToString("\n") { "• ${it.name}" }}\n\nDi Documents/KajianNote")
            .setPositiveButton("Bagikan") { _, _ -> shareFiles(files) }
            .setNeutralButton("Buka") { _, _ -> if (files.size == 1) openFile(files.first()) else shareFiles(files) }
            .setNegativeButton("Selesai", null).show()
    }

    private fun shareFiles(files: List<File>) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Bagikan via"))
    }

    private fun openFile(file: File) {
        val uri  = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = if (file.extension == "pdf") "application/pdf"
                   else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
        catch (_: Exception) { Toast.makeText(this, "Tidak ada app untuk membuka file ini", Toast.LENGTH_SHORT).show() }
    }

    // ── Share / Copy ──────────────────────────────────────────────────────

    private fun shareNote(n: Note) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "📚 ${n.title}\n${n.getFormattedDate()}\n\n${n.plainText}\n\n— KajianNote")
        }, getString(R.string.share_via)))
    }

    private fun copyNote(n: Note) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("KajianNote", n.plainText))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_note, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
        R.id.action_delete -> { confirmDelete(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDelete() {
        val n = note ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_msg, n.title))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                AudioStorage.deleteAudio(n.audioPath)
                vm.deleteNote(n); finish()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
