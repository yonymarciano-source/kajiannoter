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

    // Tier (cached on load, refreshed on resume)
    private var userTier = UserManager.Tier.FREE

    // Bookmark state
    private var bookmarks = mutableListOf<Int>()  // list timestamp dalam ms
    private var bookmarkIndex = -1                 // index bookmark terakhir di-jump

    // Current tab
    private var currentTab = 0  // 0=transcript, 1=translate, 2=summary, 3=poin kunci
    private var translateLang = "id"
    private var translatedText = ""
    private var poinKunciText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNoteDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = PreferencesManager(this)

        // Load tier (use cache, refresh async)
        userTier = UserManager.getCachedTier()
        lifecycleScope.launch {
            userTier = UserManager.getTier()
        }

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
        b.tabLayout.addTab(b.tabLayout.newTab().setText("🌐 Translate"))
        b.tabLayout.addTab(b.tabLayout.newTab().setText("✦ Summary"))
        b.tabLayout.addTab(b.tabLayout.newTab().setText("🔑 Poin Kunci"))

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
        b.panelTranslate.isVisible  = index == 1
        b.panelSummary.isVisible    = index == 2
        b.panelMindmap.isVisible    = index == 3

        when (index) {
            1 -> loadTranslatePanel(n)
            2 -> loadSummaryPanel(n)
            3 -> loadPoinKunciPanel(n)
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
            loadBookmarks(n)
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

        b.btnShare.setOnClickListener     { shareNote(n) }
        b.btnCopy.setOnClickListener      { copyNote(n) }
        b.btnEdit.setOnClickListener      { toggleEdit(n) }
        b.btnExport.setOnClickListener    { showExportDialog(n) }
        b.btnEditTitle.setOnClickListener { showEditTitleDialog(n) }

        // Folder button — hanya visible untuk Premium ke atas
        if ((!UserManager.isLoggedIn || userTier != UserManager.Tier.FREE)) {
            b.btnMoveFolder.isVisible = true
            val folderLabel = vm.allFolders.value?.find { it.id == n.folderId }?.name
            b.btnMoveFolder.text = if (folderLabel != null) "📁 $folderLabel" else "📁 Pindah Folder"
            b.btnMoveFolder.setOnClickListener { showFolderPickerDialog(n) }
        } else {
            b.btnMoveFolder.isVisible = false
        }

        if (currentTab != 0) showTab(currentTab)
    }

    private fun showEditTitleDialog(n: Note) {
        val input = android.widget.EditText(this).apply {
            setText(n.title)
            selectAll()
            setPadding(48, 24, 48, 24)
            hint = "Judul catatan"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("✏️ Edit Judul")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    val updated = n.copy(title = newTitle, updatedAt = System.currentTimeMillis())
                    note = updated
                    vm.updateNote(updated)
                    b.tvTitle.text = newTitle
                    supportActionBar?.title = newTitle
                    Toast.makeText(this, "✅ Judul diperbarui", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Folder picker ─────────────────────────────────────────────────────────

    private fun showFolderPickerDialog(n: Note) {
        val folders = vm.allFolders.value ?: emptyList()
        val items = mutableListOf("📋 Semua Catatan (tidak ada folder)") +
                folders.map { "${it.emoji} ${it.name}" }
        val folderIds = mutableListOf(0L) + folders.map { it.id }

        MaterialAlertDialogBuilder(this)
            .setTitle("📁 Pilih Folder")
            .setItems(items.toTypedArray()) { _, which ->
                val selectedFolderId = folderIds[which]
                val updated = n.copy(folderId = selectedFolderId, updatedAt = System.currentTimeMillis())
                vm.updateNote(updated)
                val label = if (which == 0) "Semua Catatan" else folders[which - 1].name
                b.btnMoveFolder.text = if (which == 0) "📁 Pindah Folder" else "📁 $label"
                Toast.makeText(this, "✅ Dipindah ke $label", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Summary Panel (#8 editor + #9 save + #10 discard) ────────────────

    private fun loadSummaryPanel(n: Note) {
        val existingSummary = n.summaryText
        summaryDraft = existingSummary
        summaryEdited = false

        // Free user: tampilkan pesan locked
        if ((UserManager.isLoggedIn && userTier == UserManager.Tier.FREE)) {
            b.tvSummaryEmpty.isVisible     = true
            b.cardSummaryContent.isVisible = false
            b.btnGenerateSummary.isVisible = false
            b.tvSummaryEmpty.text = "🔒 Fitur Catatan Rapi tersedia untuk Premium & Subscriber.\n\nUpgrade untuk ringkasan otomatis via AI."
            b.btnGenerateSummary.setOnClickListener {
                startActivity(Intent(this, PaywallActivity::class.java).apply {
                    putExtra(PaywallActivity.EXTRA_REASON, PaywallActivity.REASON_EXPORT)
                })
            }
            b.btnGenerateSummary.isVisible = true
            b.btnGenerateSummary.text = "⭐ Upgrade ke Premium"
            return
        }

        // Label mode: Standar (Premium) atau Lengkap (Subscriber)
        val modeLabel = if (userTier == UserManager.Tier.SUBSCRIBER)
            "✦ Catatan Rapi Lengkap (Subscriber)" else "✦ Catatan Rapi Standar (Premium)"

        if (existingSummary.isNotBlank()) {
            showSummaryContent(existingSummary, n)
        } else {
            b.tvSummaryEmpty.isVisible   = true
            b.cardSummaryContent.isVisible = false
            b.tvSummaryEmpty.text = "Belum ada ringkasan.\n\n$modeLabel\nTap tombol di bawah untuk generate otomatis via Groq AI."
            b.btnGenerateSummary.isVisible = true
            b.btnGenerateSummary.text = "✦ Generate Sekarang"
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
        // ── Tier gate ─────────────────────────────────────────────────────────
        if ((UserManager.isLoggedIn && userTier == UserManager.Tier.FREE)) {
            startActivity(Intent(this, PaywallActivity::class.java).apply {
                putExtra(PaywallActivity.EXTRA_REASON, PaywallActivity.REASON_EXPORT)
            })
            return
        }

        b.btnGenerateSummary.isEnabled = false
        b.btnRedoSummary.isEnabled = false

        val isDetailed = userTier == UserManager.Tier.SUBSCRIBER
        b.tvSummaryEmpty.text = if (isDetailed)
            "⏳ Membuat Catatan Rapi Lengkap (Subscriber)..."
        else
            "⏳ Membuat ringkasan via Groq AI..."
        b.tvSummaryEmpty.isVisible = true
        b.cardSummaryContent.isVisible = false

        lifecycleScope.launch {
            val result = if (isDetailed) {
                GroqSummarizer.summarizeDetailed(
                    ctx          = this@NoteDetailActivity,
                    title        = n.title,
                    plainText    = n.plainText,
                    detectedLang = n.detectedLanguage
                )
            } else {
                GroqSummarizer.summarize(
                    ctx          = this@NoteDetailActivity,
                    title        = n.title,
                    plainText    = n.plainText,
                    detectedLang = n.detectedLanguage
                )
            }
            b.btnGenerateSummary.isEnabled = true
            b.btnRedoSummary.isEnabled = true

            result.onSuccess { summary ->
                summaryDraft = summary
                summaryEdited = true
                showSummaryContent(summary, n)
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

    // ── Translate Panel ───────────────────────────────────────────────────────

    private fun loadTranslatePanel(n: Note) {
        // Tier gate — Premium+
        if (UserManager.isLoggedIn && userTier == UserManager.Tier.FREE) {
            b.tvTranslateEmpty.isVisible = true
            b.cardTranslateContent.isVisible = false
            b.btnTranslate.isVisible = false
            b.tvTranslateEmpty.text = "🔒 Fitur Translate tersedia untuk Premium & Subscriber.\n\nUpgrade untuk terjemahan otomatis via AI."
            b.btnTranslate.setOnClickListener {
                startActivity(Intent(this, PaywallActivity::class.java).apply {
                    putExtra(PaywallActivity.EXTRA_REASON, PaywallActivity.REASON_EXPORT)
                })
            }
            b.btnTranslate.isVisible = true
            b.btnTranslate.text = "⭐ Upgrade ke Premium"
            return
        }
        if (!UserManager.isLoggedIn) {
            b.tvTranslateEmpty.isVisible = true
            b.tvTranslateEmpty.text = "🔒 Login untuk menggunakan fitur Translate."
            b.btnTranslate.isVisible = true
            b.btnTranslate.text = "🔑 Login"
            b.btnTranslate.setOnClickListener {
                startActivity(Intent(this, LoginActivity::class.java))
            }
            b.cardTranslateContent.isVisible = false
            return
        }

        // Setup chips
        setupTranslateChips(n)

        // Show cached translation if available
        if (translatedText.isNotBlank()) {
            showTranslateContent(translatedText)
        } else {
            b.tvTranslateEmpty.isVisible = true
            b.tvTranslateEmpty.text = "Pilih bahasa tujuan, lalu tap Terjemahkan."
            b.cardTranslateContent.isVisible = false
            b.btnTranslate.isVisible = true
            b.btnTranslate.text = "🌐 Terjemahkan"
        }

        b.btnTranslate.setOnClickListener { doTranslate(n) }
    }

    private fun setupTranslateChips(n: Note) {
        fun select(lang: String) {
            translateLang = lang
            b.chipTranslateId.setBackgroundResource(
                if (lang == "id") R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
            b.chipTranslateEn.setBackgroundResource(
                if (lang == "en") R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
            // Clear cache when language changes
            translatedText = ""
            b.cardTranslateContent.isVisible = false
            b.tvTranslateEmpty.isVisible = true
            b.tvTranslateEmpty.text = "Pilih bahasa tujuan, lalu tap Terjemahkan."
            b.btnTranslate.isVisible = true
            b.btnTranslate.text = "🌐 Terjemahkan"
        }
        b.chipTranslateId.setOnClickListener { select("id") }
        b.chipTranslateEn.setOnClickListener { select("en") }
        // Set initial state
        b.chipTranslateId.setBackgroundResource(
            if (translateLang == "id") R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
        b.chipTranslateEn.setBackgroundResource(
            if (translateLang == "en") R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
    }

    private fun doTranslate(n: Note) {
        b.btnTranslate.isEnabled = false
        b.tvTranslateEmpty.isVisible = true
        b.tvTranslateEmpty.text = "⏳ Menerjemahkan via Groq AI..."
        b.cardTranslateContent.isVisible = false

        lifecycleScope.launch {
            val result = GroqSummarizer.translate(
                ctx = this@NoteDetailActivity,
                plainText = n.plainText,
                targetLang = translateLang
            )
            b.btnTranslate.isEnabled = true
            result.onSuccess { text ->
                translatedText = text
                showTranslateContent(text)
            }.onFailure { e ->
                b.tvTranslateEmpty.text = "❌ Gagal: ${e.message}\n\nPastikan Groq API Key sudah diset."
                b.btnTranslate.isVisible = true
            }
        }
    }

    private fun showTranslateContent(text: String) {
        b.tvTranslateEmpty.isVisible = false
        b.cardTranslateContent.isVisible = true
        b.tvTranslateContent.text = text
        b.btnTranslate.isVisible = true
        b.btnTranslate.text = "↻ Terjemahkan Ulang"
    }

    // ── Poin Kunci Panel (replaces Mindmap) ───────────────────────────────────

    private fun loadPoinKunciPanel(n: Note) {
        // Tier gate — Premium+
        if (UserManager.isLoggedIn && userTier == UserManager.Tier.FREE) {
            b.tvPoinKunciLoading.isVisible = true
            b.tvPoinKunciLoading.text = "🔒 Fitur Poin Kunci tersedia untuk Premium & Subscriber.\n\nUpgrade untuk highlight poin penting kajian."
            b.llPoinKunciCards.isVisible = false
            b.btnGeneratePoinKunci.isVisible = true
            b.btnGeneratePoinKunci.text = "⭐ Upgrade ke Premium"
            b.btnGeneratePoinKunci.setOnClickListener {
                startActivity(Intent(this, PaywallActivity::class.java).apply {
                    putExtra(PaywallActivity.EXTRA_REASON, PaywallActivity.REASON_EXPORT)
                })
            }
            return
        }
        if (!UserManager.isLoggedIn) {
            b.tvPoinKunciLoading.isVisible = true
            b.tvPoinKunciLoading.text = "🔒 Login untuk menggunakan fitur Poin Kunci."
            b.btnGeneratePoinKunci.isVisible = true
            b.btnGeneratePoinKunci.text = "🔑 Login"
            b.btnGeneratePoinKunci.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
            return
        }

        // Show cached result
        if (poinKunciText.isNotBlank()) {
            renderPoinKunciCards(poinKunciText)
            return
        }

        // Auto-generate on first open
        b.tvPoinKunciLoading.isVisible = true
        b.tvPoinKunciLoading.text = "⏳ Membuat Poin Kunci via Groq AI..."
        b.llPoinKunciCards.isVisible = false
        b.btnGeneratePoinKunci.isVisible = false

        lifecycleScope.launch {
            val isDetailed = userTier == UserManager.Tier.SUBSCRIBER
            val result = GroqSummarizer.generatePoinKunci(
                ctx = this@NoteDetailActivity,
                title = n.title,
                plainText = n.plainText,
                detectedLang = n.detectedLanguage,
                detailed = isDetailed
            )
            b.tvPoinKunciLoading.isVisible = false
            result.onSuccess { text ->
                poinKunciText = text
                renderPoinKunciCards(text)
            }.onFailure { e ->
                b.tvPoinKunciLoading.isVisible = true
                b.tvPoinKunciLoading.text = "❌ Gagal: ${e.message}\n\nPastikan Groq API Key sudah diset."
                b.btnGeneratePoinKunci.isVisible = true
                b.btnGeneratePoinKunci.text = "↻ Coba Lagi"
                b.btnGeneratePoinKunci.setOnClickListener {
                    poinKunciText = ""
                    loadPoinKunciPanel(n)
                }
            }
        }
    }

    private fun renderPoinKunciCards(text: String) {
        b.llPoinKunciCards.removeAllViews()
        b.llPoinKunciCards.isVisible = true

        val points = text.split("---").map { it.trim() }.filter { it.isNotBlank() }
        points.forEach { point ->
            val card = android.view.LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_1, b.llPoinKunciCards, false)
            val tv = card.findViewById<android.widget.TextView>(android.R.id.text1)
            tv.text = point
            tv.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
            tv.textSize = 14f
            tv.setPadding(48, 32, 48, 32)
            tv.setLineSpacing(8f, 1f)
            tv.setBackgroundColor(android.graphics.Color.parseColor("#1E2A3A"))

            // Wrap in card
            val cardView = androidx.cardview.widget.CardView(this).apply {
                radius = 16f
                cardElevation = 0f
                setCardBackgroundColor(android.graphics.Color.parseColor("#1A2332"))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 16)
                layoutParams = lp
            }
            cardView.addView(tv)
            b.llPoinKunciCards.addView(cardView)
        }

        b.btnGeneratePoinKunci.isVisible = true
        b.btnGeneratePoinKunci.text = "↻ Generate Ulang"
        b.btnGeneratePoinKunci.setOnClickListener {
            poinKunciText = ""
            note?.let { loadPoinKunciPanel(it) }
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
                        highlightTranscriptAt(cur, n)
                        updateBookmarkButtonState(cur)
                        b.bookmarkOverlay.update(bookmarks, total, cur)
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

        // Bookmark — tap: toggle tambah/hapus, long press: list semua
        b.btnBookmark.setOnClickListener {
            val posMs = player.currentPosition
            val nearby = bookmarks.firstOrNull { Math.abs(it - posMs) <= 3000 }
            if (nearby != null) {
                bookmarks.remove(nearby)
                saveBookmarks()
                refreshTranscriptBookmarks()
                updateBookmarkOverlay()
                Toast.makeText(this, "Bookmark dihapus: ${formatMs(nearby)}", Toast.LENGTH_SHORT).show()
            } else {
                if (posMs > 0) {
                    bookmarks.add(posMs)
                    bookmarks.sort()
                    saveBookmarks()
                    refreshTranscriptBookmarks()
                    updateBookmarkOverlay()
                    Toast.makeText(this, "⭐ Ditandai: ${formatMs(posMs)}", Toast.LENGTH_SHORT).show()
                }
            }
            updateBookmarkButtonState(posMs)
        }

        b.btnBookmark.setOnLongClickListener {
            showBookmarkListDialog()
            true
        }

        // Bookmark — jump ke berikutnya (loop)
        b.btnBookmarkJump.setOnClickListener {
            if (bookmarks.isEmpty()) return@setOnClickListener
            val cur = player.currentPosition
            val next = bookmarks.firstOrNull { it > cur + 500 } ?: bookmarks.first()
            player.seekTo(next)
            bookmarkIndex = bookmarks.indexOf(next)
            Toast.makeText(this, "⭐ ${bookmarkIndex + 1}/${bookmarks.size}: ${formatMs(next)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatMs(ms: Int): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        return String.format("%02d:%02d", m, s)
    }

    // Update tampilan tombol ⭐ sesuai posisi audio (dekat bookmark atau tidak)
    private fun updateBookmarkButtonState(posMs: Int) {
        val nearby = bookmarks.firstOrNull { Math.abs(it - posMs) <= 3000 }
        if (nearby != null) {
            b.btnBookmark.text = "⭐ Hapus"
            b.btnBookmark.setTextColor(0xFFB8920A.toInt())
            b.btnBookmark.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0x26F5C400.toInt())
        } else {
            b.btnBookmark.text = "☆ Tandai"
            b.btnBookmark.setTextColor(resources.getColor(R.color.text_primary, null))
            b.btnBookmark.backgroundTintList = null
        }
        val count = bookmarks.size
        b.btnBookmarkJump.isVisible = count > 0
        b.btnBookmarkJump.text = "⭐ $count"
    }

    private fun saveBookmarks() {
        val n = note ?: return
        val json = gson.toJson(bookmarks)
        val updated = n.copy(bookmarksJson = json, updatedAt = System.currentTimeMillis())
        note = updated
        vm.updateNote(updated)
    }

    private fun loadBookmarks(n: Note) {
        try {
            val type = object : TypeToken<List<Int>>() {}.type
            bookmarks = gson.fromJson<List<Int>>(n.bookmarksJson, type)?.toMutableList()
                ?: mutableListOf()
        } catch (_: Exception) { bookmarks = mutableListOf() }
        updateBookmarkButtonState(player.currentPosition)
        b.bookmarkOverlay.post {
            b.bookmarkOverlay.update(bookmarks, player.duration, player.currentPosition)
        }
    }

    // Dialog list bookmark — custom layout per item dengan Jump + Hapus
    private fun showBookmarkListDialog() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "Belum ada bookmark", Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = this
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }

        // Header
        val header = android.widget.TextView(ctx).apply {
            text = "⭐ Daftar Bookmark"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(48, 32, 48, 16)
            setTextColor(resources.getColor(R.color.text_primary, null))
        }
        container.addView(header)

        fun rebuildItems() {
            container.removeViews(1, container.childCount - 1)
            bookmarks.forEachIndexed { i, ms ->
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(48, 12, 24, 12)
                    val isNear = Math.abs(ms - player.currentPosition) <= 3000
                    if (isNear) setBackgroundColor(0x18F5C400.toInt())
                }
                val label = android.widget.TextView(ctx).apply {
                    text = "⭐  ${formatMs(ms)}" +
                        if (Math.abs(ms - player.currentPosition) <= 3000) "  ● sekarang" else ""
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnJump = android.widget.Button(ctx).apply {
                    text = "Jump"
                    textSize = 12f
                    setTextColor(0xFF1DB954.toInt())
                    background = null
                    setPadding(16, 8, 8, 8)
                    setOnClickListener {
                        player.seekTo(ms)
                        Toast.makeText(ctx, "Jump ke ${formatMs(ms)}", Toast.LENGTH_SHORT).show()
                    }
                }
                val btnDel = android.widget.Button(ctx).apply {
                    text = "Hapus"
                    textSize = 12f
                    setTextColor(0xFFE57373.toInt())
                    background = null
                    setPadding(8, 8, 16, 8)
                    setOnClickListener {
                        bookmarks.removeAt(i)
                        saveBookmarks()
                        refreshTranscriptBookmarks()
                        updateBookmarkOverlay()
                        updateBookmarkButtonState(player.currentPosition)
                        if (bookmarks.isEmpty()) {
                            Toast.makeText(ctx, "Semua bookmark dihapus", Toast.LENGTH_SHORT).show()
                        } else {
                            rebuildItems()
                        }
                    }
                }
                row.addView(label)
                row.addView(btnJump)
                row.addView(btnDel)
                container.addView(row)

                // Divider
                if (i < bookmarks.lastIndex) {
                    container.addView(android.view.View(ctx).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            setMargins(48, 0, 48, 0)
                        }
                        setBackgroundColor(0x22FFFFFF)
                    })
                }
            }

            // Hapus semua
            val btnClearAll = android.widget.Button(ctx).apply {
                text = "Hapus Semua"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                background = null
                setPadding(48, 24, 48, 16)
            }
            container.addView(btnClearAll)
            btnClearAll.setOnClickListener {
                bookmarks.clear()
                saveBookmarks()
                refreshTranscriptBookmarks()
                updateBookmarkOverlay()
                updateBookmarkButtonState(player.currentPosition)
                Toast.makeText(ctx, "Semua bookmark dihapus", Toast.LENGTH_SHORT).show()
            }
        }

        rebuildItems()

        val scroll = android.widget.ScrollView(ctx).apply { addView(container) }
        AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun updateBookmarkOverlay() {
        b.bookmarkOverlay.update(bookmarks, player.duration, player.currentPosition)
    }

    // Refresh transcript setelah bookmark berubah
    private fun refreshTranscriptBookmarks() {
        val n = note ?: return
        try {
            val type = object : TypeToken<List<TranscriptEntry>>() {}.type
            val entries: List<TranscriptEntry> = gson.fromJson(n.transcriptJson, type)
            if (entries.isNotEmpty()) {
                b.tvTranscript.text = buildRichTranscript(entries, n)
            } else {
                b.tvTranscript.text = buildSegmentedTranscript(n.plainText.ifBlank { "" })
            }
        } catch (_: Exception) {}
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
                    // Cek apakah ada bookmark di dekat timeMs entry ini
                    if (entry.timeMs > 0) {
                        val bm = bookmarks.firstOrNull { Math.abs(it - entry.timeMs) <= 3000 }
                        if (bm != null) {
                            ssb.append("\n")
                            val bmStart = ssb.length
                            ssb.append("⭐  ${formatMs(bm)} — Momen Penting")
                            val bmEnd = ssb.length
                            ssb.setSpan(ForegroundColorSpan(0xFFB8920A.toInt()), bmStart, bmEnd, 0)
                            ssb.setSpan(StyleSpan(Typeface.BOLD), bmStart, bmEnd, 0)
                            ssb.setSpan(RelativeSizeSpan(0.82f), bmStart, bmEnd, 0)
                            ssb.append("\n")
                        }
                    }
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
        if (text.isBlank() || text == "(Belum ada transkripsi)") {
            return SpannableStringBuilder("(Belum ada transkripsi)")
        }
        val paragraphs = TranscriptSegmenter.segment(text)
        val ssb = SpannableStringBuilder()
        val dividerColor = 0xFFDDDDDD.toInt()
        val numColor     = 0xFF1DB954.toInt()

        // Tampilkan daftar bookmark di atas transcript kalau ada
        if (bookmarks.isNotEmpty()) {
            val bmHeaderStart = ssb.length
            ssb.append("⭐ Bookmark: ")
            ssb.setSpan(StyleSpan(Typeface.BOLD), bmHeaderStart, ssb.length, 0)
            ssb.setSpan(ForegroundColorSpan(0xFFB8920A.toInt()), bmHeaderStart, ssb.length, 0)
            ssb.setSpan(RelativeSizeSpan(0.85f), bmHeaderStart, ssb.length, 0)
            bookmarks.forEachIndexed { i, ms ->
                val bmStart = ssb.length
                ssb.append(formatMs(ms))
                ssb.setSpan(ForegroundColorSpan(0xFFB8920A.toInt()), bmStart, ssb.length, 0)
                ssb.setSpan(RelativeSizeSpan(0.82f), bmStart, ssb.length, 0)
                if (i < bookmarks.lastIndex) ssb.append("  ·  ")
            }
            ssb.append("\n")
            val divStart = ssb.length
            ssb.append("─────────────────────")
            ssb.setSpan(ForegroundColorSpan(dividerColor), divStart, ssb.length, 0)
            ssb.setSpan(RelativeSizeSpan(0.6f), divStart, ssb.length, 0)
            ssb.append("\n\n")
        }

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
        // ── Tier gate ─────────────────────────────────────────────────────────
        if ((UserManager.isLoggedIn && userTier == UserManager.Tier.FREE)) {
            startActivity(Intent(this, PaywallActivity::class.java).apply {
                putExtra(PaywallActivity.EXTRA_REASON, PaywallActivity.REASON_EXPORT)
            })
            return
        }
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
