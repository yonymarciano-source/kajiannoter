package com.kajian.note.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kajian.note.KajianApp
import com.kajian.note.databinding.ActivityDictionaryBinding
import com.kajian.note.databinding.ItemDictionaryEntryBinding
import com.kajian.note.utils.DictionaryManager
import com.kajian.note.utils.PreferencesManager

class DictionaryActivity : AppCompatActivity() {

    private lateinit var b: ActivityDictionaryBinding
    private lateinit var dict: DictionaryManager
    private lateinit var adapter: DictAdapter
    private var allEntries = listOf<Pair<String, String>>()

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(KajianApp.applyLocale(base, PreferencesManager(base).getAppLanguage()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDictionaryBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "📖 Kamus Koreksi"

        dict = DictionaryManager(this)

        setupToggle()
        setupList()
        setupSearch()
        setupAddButton()
        loadEntries()
    }

    private fun setupToggle() {
        b.switchAutoCorrect.isChecked = dict.isEnabled()
        b.tvToggleLabel.text = if (dict.isEnabled()) "Auto-Correct: ON" else "Auto-Correct: OFF"

        b.switchAutoCorrect.setOnCheckedChangeListener { _, checked ->
            dict.setEnabled(checked)
            b.tvToggleLabel.text = if (checked) "Auto-Correct: ON" else "Auto-Correct: OFF"
            Toast.makeText(this,
                if (checked) "✅ Auto-correct diaktifkan" else "⏸️ Auto-correct dinonaktifkan",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupList() {
        adapter = DictAdapter { wrong ->
            AlertDialog.Builder(this)
                .setTitle("Hapus entri?")
                .setMessage("\"$wrong\" → \"${dict.getEntries()[wrong]}\" akan dihapus.")
                .setPositiveButton("Hapus") { _, _ ->
                    dict.removeEntry(wrong)
                    loadEntries()
                    Toast.makeText(this, "✅ Dihapus", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null).show()
        }
        b.rvEntries.layoutManager = LinearLayoutManager(this)
        b.rvEntries.adapter = adapter
    }

    private fun setupSearch() {
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.lowercase() ?: ""
                val filtered = if (q.isBlank()) allEntries
                else allEntries.filter { it.first.contains(q) || it.second.lowercase().contains(q) }
                adapter.submitList(filtered)
                b.tvCount.text = "${filtered.size} entri"
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
        })
    }

    private fun setupAddButton() {
        b.fabAdd.setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.activity_list_item, null)
        val wrongInput = EditText(this).apply {
            hint = "Kata yang sering salah (cth: mashallah)"
            setPadding(48, 24, 48, 16)
        }
        val correctInput = EditText(this).apply {
            hint = "Koreksi yang benar (cth: Masha Allah)"
            setPadding(48, 8, 48, 24)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(wrongInput); addView(correctInput)
        }

        AlertDialog.Builder(this)
            .setTitle("➕ Tambah Kata Baru")
            .setMessage("Masukkan kata yang sering disalah-tulis oleh speech recognition, beserta koreksinya.")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val wrong = wrongInput.text.toString().trim().lowercase()
                val correct = correctInput.text.toString().trim()
                if (wrong.isBlank() || correct.isBlank()) {
                    Toast.makeText(this, "Kedua kolom harus diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dict.addEntry(wrong, correct)
                loadEntries()
                Toast.makeText(this, "✅ \"$wrong\" → \"$correct\" ditambahkan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun loadEntries() {
        allEntries = dict.getEntries().entries
            .sortedBy { it.key }
            .map { Pair(it.key, it.value) }
        adapter.submitList(allEntries)
        b.tvCount.text = "${allEntries.size} entri"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Reset ke Default").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 2, 0, "Hapus Semua").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            1 -> {
                AlertDialog.Builder(this)
                    .setTitle("Reset ke Default?")
                    .setMessage("Semua entri kustom akan dihapus dan dikembalikan ke kamus bawaan.")
                    .setPositiveButton("Reset") { _, _ ->
                        dict.resetToDefaults()
                        loadEntries()
                        Toast.makeText(this, "✅ Direset ke default", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Batal", null).show()
                true
            }
            2 -> {
                AlertDialog.Builder(this)
                    .setTitle("Hapus Semua?")
                    .setMessage("Seluruh kamus akan dikosongkan.")
                    .setPositiveButton("Hapus") { _, _ ->
                        dict.clearAll()
                        loadEntries()
                        Toast.makeText(this, "🗑️ Kamus dikosongkan", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Batal", null).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────

class DictAdapter(private val onDelete: (String) -> Unit) :
    ListAdapter<Pair<String, String>, DictAdapter.VH>(object : DiffUtil.ItemCallback<Pair<String, String>>() {
        override fun areItemsTheSame(a: Pair<String, String>, b: Pair<String, String>) = a.first == b.first
        override fun areContentsTheSame(a: Pair<String, String>, b: Pair<String, String>) = a == b
    }) {

    inner class VH(val b: ItemDictionaryEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemDictionaryEntryBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (wrong, correct) = getItem(pos)
        h.b.tvWrong.text = wrong
        h.b.tvCorrect.text = correct
        h.b.btnDelete.setOnClickListener { onDelete(wrong) }
    }
}
