package com.kajian.note.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kajian.note.databinding.ActivityFolderManagerBinding
import com.kajian.note.databinding.ItemFolderBinding
import com.kajian.note.db.NoteRepository
import com.kajian.note.model.Folder
import com.kajian.note.utils.UserManager
import kotlinx.coroutines.launch

class FolderManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderManagerBinding
    private lateinit var repo: NoteRepository
    private lateinit var adapter: FolderAdapter

    private val EMOJIS = listOf("📚","🕌","🌙","📖","✏️","🎯","💡","🌿","⭐","🔖","📝","🎓")
    private val COLORS = listOf("#00E676","#448AFF","#FF6E40","#EA80FC","#FFD740","#00BCD4","#FF5252","#69F0AE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Kelola Folder"

        // ── Tier gate ─────────────────────────────────────────────────────────
        if (UserManager.isLoggedIn && UserManager.getCachedTier() == UserManager.Tier.FREE) {
            startActivity(Intent(this, PaywallActivity::class.java).apply {
                putExtra(PaywallActivity.EXTRA_REASON, PaywallActivity.REASON_EXPORT)
            })
            finish()
            return
        }

        repo = NoteRepository(this)

        adapter = FolderAdapter(
            onEdit = { folder -> showEditDialog(folder) },
            onDelete = { folder -> confirmDelete(folder) }
        )
        binding.rvFolders.layoutManager = LinearLayoutManager(this)
        binding.rvFolders.adapter = adapter

        repo.allFolders.observe(this) { folders ->
            adapter.submitList(folders)
            binding.tvEmpty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.fabAddFolder.setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        var selectedEmoji = EMOJIS[0]
        var selectedColor = COLORS[0]

        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val input = EditText(this).apply {
            hint = "Nama folder (cth: Kajian Fiqih)"
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("📁 Folder Baru")
            .setView(input)
            .setPositiveButton("Buat") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Nama folder tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    repo.insertFolder(Folder(
                        name = name,
                        emoji = selectedEmoji,
                        colorHex = selectedColor
                    ))
                    Toast.makeText(this@FolderManagerActivity, "✅ Folder '$name' dibuat", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showEditDialog(folder: Folder) {
        val input = EditText(this).apply {
            hint = "Nama folder"
            setText(folder.name)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Edit Folder")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    repo.updateFolder(folder.copy(name = name))
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmDelete(folder: Folder) {
        AlertDialog.Builder(this)
            .setTitle("Hapus Folder?")
            .setMessage("Folder '${folder.name}' akan dihapus. Catatan di dalamnya akan dipindah ke Semua Catatan.")
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    repo.deleteFolder(folder)
                    Toast.makeText(this@FolderManagerActivity, "Folder dihapus", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }
}

class FolderAdapter(
    private val onEdit: (Folder) -> Unit,
    private val onDelete: (Folder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    private var folders = listOf<Folder>()

    fun submitList(list: List<Folder>) {
        folders = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = folders.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val folder = folders[position]
        holder.binding.apply {
            tvFolderEmoji.text = folder.emoji
            tvFolderName.text = folder.name
            tvFolderCount.text = "${folder.noteCount} catatan"
            btnEdit.setOnClickListener { onEdit(folder) }
            btnDelete.setOnClickListener { onDelete(folder) }
        }
    }
}
