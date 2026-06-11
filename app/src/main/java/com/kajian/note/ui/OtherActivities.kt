package com.kajian.note.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.kajian.note.KajianApp
import com.kajian.note.R
import com.kajian.note.databinding.*
import com.kajian.note.model.Note
import com.kajian.note.utils.PreferencesManager
import com.kajian.note.utils.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        supportActionBar?.setDisplayShowTitleEnabled(false) // pakai tvToolbarTitle custom
        setupTabs()
        setupUserAvatar()
    }

    override fun onResume() {
        super.onResume()
        // Refresh tier badge setiap kali balik ke MainActivity (misal setelah bayar)
        refreshTierBadge()
    }

    // ── Avatar & tier setup ───────────────────────────────────────────────────

    private fun setupUserAvatar() {
        val user = FirebaseAuth.getInstance().currentUser

        if (user != null) {
            // Tampilkan foto Google kalau ada
            val photoUrl = user.photoUrl
            if (photoUrl != null) {
                binding.ivAvatar.visibility = View.VISIBLE
                binding.tvAvatarInitial.visibility = View.GONE
                Glide.with(this)
                    .load(photoUrl)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.bg_avatar_circle)
                    .into(binding.ivAvatar)
            } else {
                // Fallback: inisial nama
                binding.ivAvatar.visibility = View.GONE
                binding.tvAvatarInitial.visibility = View.VISIBLE
                val name = user.displayName ?: user.email ?: "?"
                binding.tvAvatarInitial.text = name.firstOrNull()?.uppercase() ?: "?"
            }

            // Load tier dari Firestore
            refreshTierBadge()

        } else {
            // Guest — tampilkan inisial "G"
            binding.ivAvatar.visibility = View.GONE
            binding.tvAvatarInitial.visibility = View.VISIBLE
            binding.tvAvatarInitial.text = "G"
            binding.tvTierBadge.visibility = View.GONE
        }

        // Klik avatar → tampilkan dialog profil
        binding.flAvatar.setOnClickListener { showProfileDialog() }
    }

    private fun refreshTierBadge() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val tier = UserManager.getTier().name
            withContext(Dispatchers.Main) {
                binding.tvTierBadge.visibility = View.VISIBLE
                binding.tvTierBadge.text = tier
                // Warna badge per tier
                val bgColor = when (tier) {
                    "PREMIUM"    -> Color.parseColor("#1565C0") // biru
                    "SUBSCRIBER" -> Color.parseColor("#6A1B9A") // ungu
                    else         -> Color.parseColor("#37474F") // abu (FREE)
                }
                binding.tvTierBadge.background.mutate().setTint(bgColor)
            }
        }
    }

    // ── Dialog profil ─────────────────────────────────────────────────────────

    private fun showProfileDialog() {
        val user = FirebaseAuth.getInstance().currentUser
        val isGuest = user == null

        val name  = user?.displayName ?: "Tamu"
        val email = user?.email ?: "Belum login"

        CoroutineScope(Dispatchers.IO).launch {
            val tier = if (isGuest) "FREE" else (UserManager.getTier().name)
            withContext(Dispatchers.Main) {
                val tierLabel = when (tier) {
                    "PREMIUM"    -> "⭐ Premium"
                    "SUBSCRIBER" -> "👑 Subscriber"
                    else         -> "🆓 Free"
                }

                val message = if (isGuest) {
                    "Kamu belum login.\nLogin untuk menyimpan data & akses fitur lengkap."
                } else {
                    "$email\n\nTier: $tierLabel"
                }

                val builder = AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (isGuest) "👤 Tamu" else "👤 $name")
                    .setMessage(message)

                if (isGuest) {
                    builder.setPositiveButton("Login") { _, _ ->
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                } else {
                    if (tier == "FREE") {
                        builder.setPositiveButton("⬆️ Upgrade") { _, _ ->
                            startActivity(Intent(this@MainActivity, PaywallActivity::class.java))
                        }
                    }
                    builder.setNeutralButton("Pengaturan") { _, _ ->
                        startActivity(Intent(this@MainActivity, AppSettingsActivity::class.java))
                    }
                    builder.setNegativeButton("Sign Out") { _, _ ->
                        confirmSignOut()
                    }
                }

                builder.show()
            }
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Yakin ingin keluar dari akun?")
            .setPositiveButton("Sign Out") { _, _ ->
                FirebaseAuth.getInstance().signOut()
                // Sign out dari Google juga agar bisa pilih akun lain
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        CoroutineScope(Dispatchers.IO).launch {
            val tier = UserManager.getTier()
            val isSubscriber = tier == UserManager.Tier.SUBSCRIBER
            withContext(Dispatchers.Main) {
                val adapter = MainPagerAdapter(this@MainActivity, isSubscriber)
                binding.viewPager.adapter = adapter
                TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
                    when (pos) {
                        0 -> { tab.text = getString(R.string.tab_record); tab.setIcon(R.drawable.ic_mic) }
                        1 -> { tab.text = getString(R.string.tab_notes); tab.setIcon(R.drawable.ic_notes) }
                        2 -> { tab.text = "Speaker" }
                    }
                }.attach()
                if (isSubscriber) {
                    binding.tabLayout.setSelectedTabIndicatorColor(
                        android.graphics.Color.parseColor("#7C3AED")
                    )
                    binding.tabLayout.setTabTextColors(
                        ContextCompat.getColor(this@MainActivity, R.color.text_secondary),
                        android.graphics.Color.parseColor("#C084FC")
                    )
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Menu settings tetap ada sebagai fallback
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
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

class MainPagerAdapter(
    a: FragmentActivity,
    private val isSubscriber: Boolean = false
) : FragmentStateAdapter(a) {
    override fun getItemCount() = if (isSubscriber) 3 else 2
    override fun createFragment(pos: Int): Fragment = when (pos) {
        0 -> RecordFragment()
        1 -> NotesListFragment()
        2 -> SpeakerFragment()
        else -> RecordFragment()
    }
}

// ── NotesListFragment ─────────────────────────────────────────────────────────

class NotesListFragment : Fragment() {
    private var _b: FragmentNotesListBinding? = null
    private val b get() = _b!!
    private val vm: RecordViewModel by activityViewModels()
    private lateinit var adapter: NotesAdapter
    private var selectedFolderId: Long = -1L  // -1 = semua, 0 = tanpa folder, >0 = folder id

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

        vm.allNotes.observe(viewLifecycleOwner) { applyFilter(it) }

        // Folder chips — hanya untuk Premium ke atas
        val tier = UserManager.getCachedTier()
        if (tier != UserManager.Tier.FREE) {
            b.scrollFolderChips.visibility = View.VISIBLE
            vm.allFolders.observe(viewLifecycleOwner) { folders -> buildFolderChips(folders) }
        }

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isBlank()) vm.allNotes.observe(viewLifecycleOwner) { applyFilter(it) }
                else vm.searchNotes(q).observe(viewLifecycleOwner) { applyFilter(it) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
        })
    }

    private fun buildFolderChips(folders: List<com.kajian.note.model.Folder>) {
        b.llFolderChips.removeAllViews()
        val allChip = makeChip("📋 Semua", -1L)
        b.llFolderChips.addView(allChip)
        folders.forEach { folder ->
            b.llFolderChips.addView(makeChip("${folder.emoji} ${folder.name}", folder.id))
        }
        updateChipSelection()
    }

    private fun makeChip(label: String, folderId: Long): android.widget.Button {
        return android.widget.Button(requireContext(), null,
            android.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 12f
            setPadding(28, 8, 28, 8)
            setTextColor(if (folderId == selectedFolderId)
                android.graphics.Color.parseColor("#00E676")
            else
                android.graphics.Color.parseColor("#99FFFFFF"))
            setOnClickListener {
                selectedFolderId = folderId
                updateChipSelection()
                vm.allNotes.value?.let { applyFilter(it) }
            }
        }
    }

    private fun updateChipSelection() {
        for (i in 0 until b.llFolderChips.childCount) {
            val chip = b.llFolderChips.getChildAt(i) as? android.widget.Button ?: continue
            val fid = when (i) {
                0 -> -1L
                else -> (vm.allFolders.value?.getOrNull(i - 1))?.id ?: continue
            }
            chip.setTextColor(if (fid == selectedFolderId)
                android.graphics.Color.parseColor("#00E676")
            else
                android.graphics.Color.parseColor("#99FFFFFF"))
        }
    }

    private fun applyFilter(list: List<Note>) {
        val filtered = when (selectedFolderId) {
            -1L -> list
            0L  -> list.filter { it.folderId == 0L }
            else -> list.filter { it.folderId == selectedFolderId }
        }
        updateList(filtered)
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
