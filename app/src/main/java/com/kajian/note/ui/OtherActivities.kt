package com.kajian.note.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.kajian.note.KajianApp
import com.kajian.note.R
import com.kajian.note.databinding.*
import com.kajian.note.model.Note
import com.kajian.note.utils.PreferencesManager

// ── MainActivity ─────────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    val vm: RecordViewModel by viewModels()
    private lateinit var prefs: PreferencesManager

    override fun attachBaseContext(base: android.content.Context) {
        prefs = PreferencesManager(base)
        super.attachBaseContext(KajianApp.applyLocale(base, prefs.getAppLanguage()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupTabs()
    }

    private fun setupTabs() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = if (pos == 0) getString(R.string.tab_record) else getString(R.string.tab_notes)
            tab.setIcon(if (pos == 0) R.drawable.ic_mic else R.drawable.ic_notes)
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, AppSettingsActivity::class.java))
                true
            }
else -> super.onOptionsItemSelected(item)
        }
    }
}

class MainPagerAdapter(a: FragmentActivity) : FragmentStateAdapter(a) {
    override fun getItemCount() = 2
    override fun createFragment(pos: Int): Fragment =
        if (pos == 0) RecordFragment() else NotesListFragment()
}

// ── NotesListFragment ─────────────────────────────────────────────────────────

class NotesListFragment : Fragment() {
    private var _b: FragmentNotesListBinding? = null
    private val b get() = _b!!
    private val vm: RecordViewModel by activityViewModels()
    private lateinit var adapter: NotesAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentNotesListBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        adapter = NotesAdapter(
            onClick = { note ->
                startActivity(Intent(requireContext(), NoteDetailActivity::class.java)
                    .apply { putExtra(NoteDetailActivity.EXTRA_ID, note.id) })
            },
            onDelete = { note -> confirmDelete(note) }
        )
        b.rvNotes.layoutManager = LinearLayoutManager(requireContext())
        b.rvNotes.adapter = adapter

        vm.allNotes.observe(viewLifecycleOwner) { updateList(it) }

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isBlank()) vm.allNotes.observe(viewLifecycleOwner) { updateList(it) }
                else vm.searchNotes(q).observe(viewLifecycleOwner) { updateList(it) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
        })
    }

    private fun updateList(list: List<Note>) {
        adapter.submitList(list)
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.rvNotes.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        b.tvCount.text = "${list.size} ${getString(R.string.notes_saved)}"
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_msg, note.title))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> vm.deleteNote(note) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

// ── NotesAdapter ──────────────────────────────────────────────────────────────

class NotesAdapter(
    private val onClick: (Note) -> Unit,
    private val onDelete: (Note) -> Unit
) : ListAdapter<Note, NotesAdapter.VH>(object : DiffUtil.ItemCallback<Note>() {
    override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
    override fun areContentsTheSame(a: Note, b: Note) = a == b
}) {
    inner class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemNoteBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val note = getItem(pos)
        h.b.apply {
            tvTitle.text = note.title.ifBlank { "Note ${note.id}" }
            tvPreview.text = note.getPreview()
            tvDate.text = note.getFormattedDate()
            tvLanguage.text = note.getLanguageLabel()
            tvWordCount.text = "${note.wordCount} words"
            tvDuration.text = note.getFormattedDuration()
            tvSpeakers.text = if (note.speakerCount > 1) "👥 ${note.speakerCount}" else ""
            root.setOnClickListener { onClick(note) }
            btnDelete.setOnClickListener { onDelete(note) }
        }
    }
}

// ── AppSettingsActivity ───────────────────────────────────────────────────────

class AppSettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: PreferencesManager

    override fun attachBaseContext(base: android.content.Context) {
        prefs = PreferencesManager(base)
        super.attachBaseContext(KajianApp.applyLocale(base, prefs.getAppLanguage()))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        updateDisplay()

        // App language
        b.rowAppLang.setOnClickListener {
            val langs = PreferencesManager.APP_LANGUAGES
            val names = langs.map { "${it.first.uppercase()} — ${it.second}" }.toTypedArray()
            val current = langs.indexOfFirst { it.first == prefs.getAppLanguage() }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_language))
                .setSingleChoiceItems(names, current) { dlg, which ->
                    val lang = langs[which].first
                    prefs.setAppLanguage(lang)
                    dlg.dismiss()
                    Toast.makeText(this, "Restart app to apply language", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Recording language — sinkron dengan chip di layar depan
        b.rowRecLang.setOnClickListener {
            val langs = PreferencesManager.RECORDING_LANGUAGES
            val names = langs.map { it.second }.toTypedArray()
            val current = langs.indexOfFirst { it.first == prefs.getRecordingLanguage() }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("🌐 Bahasa Audio")
                .setSingleChoiceItems(names, current) { dlg, which ->
                    prefs.setRecordingLanguage(langs[which].first)
                    dlg.dismiss()
                    updateDisplay()
                    Toast.makeText(this, "✅ ${langs[which].second} dipilih", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Speaker sensitivity
        b.rowDictionary.setOnClickListener {
            startActivity(android.content.Intent(this, DictionaryActivity::class.java))
        }

        // Mic sensitivity slider
        val currentLevel = prefs.getMicSensitivity()
        b.seekMicSensitivity.progress = currentLevel - 1
        updateSensitivityUI(currentLevel)

        b.seekMicSensitivity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val level = progress + 1
                if (fromUser) {
                    prefs.setMicSensitivity(level)
                    updateSensitivityUI(level)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val level = (sb?.progress ?: 2) + 1
                android.widget.Toast.makeText(this@AppSettingsActivity,
                    "✅ Sensitivitas $level/5 disimpan", android.widget.Toast.LENGTH_SHORT).show()
            }
        })

        b.rowSpeakerSens.setOnClickListener {
            val options = arrayOf("High (2s)", "Medium (3.5s)", "Low (6s)")
            val values = longArrayOf(2000L, 3500L, 6000L)
            val current = values.indexOfFirst { it == prefs.getSpeakerChangeSensitivity() }.coerceAtLeast(1)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.speaker_sensitivity))
                .setSingleChoiceItems(options, current) { dlg, which ->
                    prefs.setSpeakerChangeSensitivity(values[which])
                    dlg.dismiss()
                    updateDisplay()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Transcribe engine selector
        b.rowTranscribeEngine.setOnClickListener {
            val options = arrayOf("🌐 Groq (Online, akurasi tinggi)", "🧠 Offline (Whisper, tanpa internet)")
            val keys    = arrayOf("GROQ", "OFFLINE")
            val current = keys.indexOfFirst { it == prefs.getTranscribeEngine() }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Engine Transkripsi")
                .setSingleChoiceItems(options, current) { dlg, which ->
                    prefs.setTranscribeEngine(keys[which])
                    dlg.dismiss()
                    updateDisplay()
                    Toast.makeText(this, "Engine: ${options[which]}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Groq API Key input
        b.rowGroqApiKey.setOnClickListener {
            val currentKey = com.kajian.note.utils.GroqTranscriber.getApiKey(this)
            val input = android.widget.EditText(this).apply {
                hint = "gsk_xxxxxxxxxxxxxxxxxxxx"
                setText(currentKey)
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle("🔑 Groq API Key")
                .setMessage("Dapatkan gratis di console.groq.com\n→ API Keys → Create Key")
                .setView(input)
                .setPositiveButton("Simpan") { _, _ ->
                    val key = input.text.toString().trim()
                    com.kajian.note.utils.GroqTranscriber.saveApiKey(this, key)
                    updateDisplay()
                    val msg = if (key.isNotBlank()) "✅ API Key tersimpan" else "API Key dihapus"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Claude key listener removed — ringkasan AI sekarang pakai Groq key yang sama
    }

    private fun updateSensitivityUI(level: Int) {
        b.tvSensitivityLevel.text = when(level) {
            1 -> "Rendah (1/5)"
            2 -> "Bawah Normal (2/5)"
            3 -> "Normal (3/5)"
            4 -> "Tinggi (4/5)"
            5 -> "Maksimal (5/5)"
            else -> "Normal (3/5)"
        }
        b.tvSensitivityDesc.text = when(level) {
            1 -> "Hanya menangkap suara jelas dari jarak dekat. Minim false positive."
            2 -> "Konservatif — cocok untuk ruangan tenang, bicara ke HP."
            3 -> "Seimbang — cocok untuk bicara langsung ke HP atau kajian dekat."
            4 -> "Tinggi — untuk speaker PA/masjid, mic jauh, atau audio streaming."
            5 -> "Maksimal — untuk ruangan besar, suara jauh, atau kondisi bising."
            else -> "Seimbang."
        }
        b.tvSensitivityLevel.setTextColor(
            when(level) {
                1, 2 -> android.graphics.Color.parseColor("#8892B0")
                3    -> android.graphics.Color.parseColor("#00E676")
                4    -> android.graphics.Color.parseColor("#FB8C00")
                else -> android.graphics.Color.parseColor("#FF5252")
            }
        )
    }

    private fun updateDisplay() {
        val dictCount = com.kajian.note.utils.DictionaryManager(this).getEntryCount()
        val dictEnabled = com.kajian.note.utils.DictionaryManager(this).isEnabled()
        b.tvDictStatus.text = "${if (dictEnabled) "ON" else "OFF"} · $dictCount entri"

        val appLang = prefs.getAppLanguage()
        b.tvAppLang.text = PreferencesManager.APP_LANGUAGES.find { it.first == appLang }?.second ?: "English"

        val recLang = prefs.getRecordingLanguage()
        b.tvRecLang.text = PreferencesManager.getLanguageLabel(recLang)

        val sens = prefs.getSpeakerChangeSensitivity()
        b.tvSpeakerSens.text = when (sens) {
            2000L -> "High (2s)"
            6000L -> "Low (6s)"
            else  -> "Medium (3.5s)"
        }

        // Groq / engine status
        val engine = prefs.getTranscribeEngine()
        b.tvTranscribeEngine.text = if (engine == "GROQ") "Groq 🌐" else "Offline 🧠"

        val hasKey = com.kajian.note.utils.GroqTranscriber.hasApiKey(this)
        b.tvGroqKeyStatus.text = if (hasKey) "✅ Tersimpan" else "⚠️ Belum diset"
        b.tvGroqKeyStatus.setTextColor(
            if (hasKey) android.graphics.Color.parseColor("#00E676")
            else android.graphics.Color.parseColor("#FF8A65")
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
