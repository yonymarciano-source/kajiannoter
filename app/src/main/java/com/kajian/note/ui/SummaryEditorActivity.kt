package com.kajian.note.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.style.*
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kajian.note.R
import com.kajian.note.databinding.ActivitySummaryEditorBinding

class SummaryEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID  = "note_id"
        const val EXTRA_SUMMARY  = "summary_text"
        const val RESULT_SUMMARY = "result_summary"
    }

    private lateinit var b: ActivitySummaryEditorBinding
    private var initialText = ""
    private var saved = false

    // Font sizes for "H" selector
    private val fontSizes = listOf(12, 15, 18, 22)
    private val fontLabels = listOf("S", "M", "L", "XL")
    private var fontSizeIndex = 1  // default M = 15sp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySummaryEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        initialText = intent.getStringExtra(EXTRA_SUMMARY) ?: ""
        b.etSummary.setText(initialText)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Edit Summary"

        // Set default font size
        b.etSummary.textSize = fontSizes[fontSizeIndex].toFloat()

        setupFormatButtons()
    }

    // ── Format toolbar ─────────────────────────────────────────────────────

    private fun setupFormatButtons() {
        // Undo — hapus karakter terakhir
        b.btnUndo.setOnClickListener {
            val et = b.etSummary
            val start = et.selectionStart
            if (start > 0) {
                et.text?.delete(start - 1, start)
            }
        }

        // H → Font size picker
        b.btnHeading.setOnClickListener {
            val labels = fontLabels.mapIndexed { i, l ->
                if (i == fontSizeIndex) "✓ $l (${fontSizes[i]}sp)" else "$l (${fontSizes[i]}sp)"
            }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("Ukuran Font")
                .setItems(labels) { _, which ->
                    fontSizeIndex = which
                    b.etSummary.textSize = fontSizes[which].toFloat()
                    b.btnHeading.text = fontLabels[which]
                }
                .show()
        }

        // Bold
        b.btnBold.setOnClickListener {
            val start = b.etSummary.selectionStart
            val end   = b.etSummary.selectionEnd
            if (start < end) {
                val sp = b.etSummary.text as? Spannable ?: return@setOnClickListener
                // Toggle: cek apakah sudah bold
                val existing = sp.getSpans(start, end, StyleSpan::class.java)
                    .filter { it.style == Typeface.BOLD }
                if (existing.isNotEmpty()) {
                    existing.forEach { sp.removeSpan(it) }
                } else {
                    sp.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                b.etSummary.invalidate()
            } else {
                Toast.makeText(this, "Pilih teks dulu", Toast.LENGTH_SHORT).show()
            }
        }

        // Italic
        b.btnItalic.setOnClickListener {
            val start = b.etSummary.selectionStart
            val end   = b.etSummary.selectionEnd
            if (start < end) {
                val sp = b.etSummary.text as? Spannable ?: return@setOnClickListener
                val existing = sp.getSpans(start, end, StyleSpan::class.java)
                    .filter { it.style == Typeface.ITALIC }
                if (existing.isNotEmpty()) {
                    existing.forEach { sp.removeSpan(it) }
                } else {
                    sp.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                b.etSummary.invalidate()
            } else {
                Toast.makeText(this, "Pilih teks dulu", Toast.LENGTH_SHORT).show()
            }
        }

        // Strikethrough
        b.btnStrike.setOnClickListener {
            val start = b.etSummary.selectionStart
            val end   = b.etSummary.selectionEnd
            if (start < end) {
                val sp = b.etSummary.text as? Spannable ?: return@setOnClickListener
                val existing = sp.getSpans(start, end, StrikethroughSpan::class.java)
                if (existing.isNotEmpty()) {
                    existing.forEach { sp.removeSpan(it) }
                } else {
                    sp.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                b.etSummary.invalidate()
            } else {
                Toast.makeText(this, "Pilih teks dulu", Toast.LENGTH_SHORT).show()
            }
        }

        // Bullet list
        b.btnBullet.setOnClickListener { insertPrefix("• ") }

        // Numbered list
        b.btnNumber.setOnClickListener {
            val text = b.etSummary.text.toString()
            val cur  = b.etSummary.selectionStart
            val num  = text.take(cur).count { it == '\n' } + 1
            insertPrefix("$num. ")
        }

        // Indent →
        b.btnIndent.setOnClickListener { insertPrefix("    ") }

        // Outdent ←
        b.btnOutdent.setOnClickListener {
            val cur  = b.etSummary.selectionStart
            val text = b.etSummary.text.toString()
            if (cur >= 4 && text.substring(cur - 4, cur) == "    ") {
                b.etSummary.text?.delete(cur - 4, cur)
            }
        }

        // Redo (re-generate)
        b.btnRedo.setOnClickListener {
            // Reset teks ke state awal jika user mau mulai ulang
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset ke teks awal?")
                .setMessage("Semua perubahan akan hilang.")
                .setPositiveButton("Reset") { _, _ ->
                    b.etSummary.setText(initialText)
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun insertPrefix(prefix: String) {
        val cur  = b.etSummary.selectionStart
        val text = b.etSummary.text.toString()
        val needNewline = cur > 0 && text[cur - 1] != '\n'
        val insert = if (needNewline) "\n$prefix" else prefix
        b.etSummary.text?.insert(cur, insert)
        b.etSummary.setSelection(cur + insert.length)
    }

    // ── Save / Back ────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_summary_editor, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { handleBack(); true }
        R.id.action_save_summary -> { saveAndReturn(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun saveAndReturn() {
        val text = b.etSummary.text.toString()
        saved = true
        setResult(RESULT_OK, Intent().apply { putExtra(RESULT_SUMMARY, text) })
        Toast.makeText(this, "✅ Ringkasan tersimpan", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handleBack() {
        if (!saved && b.etSummary.text.toString() != initialText) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Discard Changes?")
                .setMessage("Do you want to go back? Your changes will not be saved.")
                .setPositiveButton("Yes") { _, _ -> finish() }
                .setNegativeButton("No", null)
                .show()
        } else {
            finish()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { handleBack() }
}
