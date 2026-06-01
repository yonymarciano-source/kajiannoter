package com.kajian.note.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.*
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kajian.note.databinding.ActivitySummaryEditorBinding

/**
 * SummaryEditorActivity — editor ringkasan dengan toolbar formatting.
 *
 * Features:
 * - Bold, Italic, Strikethrough, Heading
 * - Bullet list, Numbered list
 * - Indent / Outdent
 * - Quote
 * - Save via ✓ button (top right)
 * - Discard confirm dialog saat back tanpa save
 */
class SummaryEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_SUMMARY = "summary_text"
        const val RESULT_SUMMARY = "result_summary"
    }

    private lateinit var b: ActivitySummaryEditorBinding
    private var initialText = ""
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySummaryEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        initialText = intent.getStringExtra(EXTRA_SUMMARY) ?: ""
        b.etSummary.setText(initialText)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Edit Summary"

        setupFormatButtons()
    }

    // ── Format toolbar ─────────────────────────────────────────────────────

    private fun setupFormatButtons() {
        b.btnUndo.setOnClickListener {
            b.etSummary.text?.let { t ->
                if (t.isNotEmpty()) t.delete(t.length - 1, t.length)
            }
        }
        b.btnHeading.setOnClickListener  { insertHeading() }
        b.btnBold.setOnClickListener     { applySpan(StyleSpan(Typeface.BOLD)) }
        b.btnItalic.setOnClickListener   { applySpan(StyleSpan(Typeface.ITALIC)) }
        b.btnStrike.setOnClickListener   { applySpan(StrikethroughSpan()) }
        b.btnBullet.setOnClickListener   { insertBullet() }
        b.btnNumber.setOnClickListener   { insertNumber() }
        b.btnIndent.setOnClickListener   { insertIndent() }
        b.btnOutdent.setOnClickListener  { removeIndent() }
        b.btnQuote.setOnClickListener    { insertQuote() }
    }

    private fun applySpan(span: Any) {
        val start = b.etSummary.selectionStart
        val end   = b.etSummary.selectionEnd
        if (start >= end) return
        val ssb = SpannableStringBuilder(b.etSummary.text)
        ssb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.etSummary.text = ssb
        b.etSummary.setSelection(end)
    }

    private fun insertHeading() {
        val start = b.etSummary.selectionStart
        val end   = b.etSummary.selectionEnd
        if (start < end) {
            val ssb = SpannableStringBuilder(b.etSummary.text)
            ssb.setSpan(RelativeSizeSpan(1.3f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.etSummary.text = ssb
            b.etSummary.setSelection(end)
        }
    }

    private fun insertBullet() {
        val cur = b.etSummary.selectionStart
        val ssb = SpannableStringBuilder(b.etSummary.text)
        val prefix = if (cur == 0 || ssb.getOrNull(cur - 1) == '\n') "" else "\n"
        ssb.insert(cur, "${prefix}• ")
        b.etSummary.text = ssb
        b.etSummary.setSelection(cur + prefix.length + 2)
    }

    private fun insertNumber() {
        val cur = b.etSummary.selectionStart
        val text = b.etSummary.text.toString()
        // Count existing numbered items
        val num = text.take(cur).count { it == '\n' } + 1
        val ssb = SpannableStringBuilder(b.etSummary.text)
        val prefix = if (cur == 0 || ssb.getOrNull(cur - 1) == '\n') "" else "\n"
        ssb.insert(cur, "${prefix}${num}. ")
        b.etSummary.text = ssb
        b.etSummary.setSelection(cur + prefix.length + "$num. ".length)
    }

    private fun insertIndent() {
        val cur = b.etSummary.selectionStart
        val ssb = SpannableStringBuilder(b.etSummary.text)
        ssb.insert(cur, "    ")
        b.etSummary.text = ssb
        b.etSummary.setSelection(cur + 4)
    }

    private fun removeIndent() {
        val cur = b.etSummary.selectionStart
        val text = b.etSummary.text.toString()
        if (cur >= 4 && text.substring(cur - 4, cur) == "    ") {
            val ssb = SpannableStringBuilder(b.etSummary.text)
            ssb.delete(cur - 4, cur)
            b.etSummary.text = ssb
            b.etSummary.setSelection(cur - 4)
        }
    }

    private fun insertQuote() {
        val cur = b.etSummary.selectionStart
        val end = b.etSummary.selectionEnd
        val ssb = SpannableStringBuilder(b.etSummary.text)
        if (cur < end) {
            ssb.setSpan(QuoteSpan(0xFF1DB954.toInt()), cur, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.etSummary.text = ssb
        } else {
            val prefix = if (cur == 0 || ssb.getOrNull(cur - 1) == '\n') "" else "\n"
            ssb.insert(cur, "${prefix}❝ ")
            b.etSummary.text = ssb
            b.etSummary.setSelection(cur + prefix.length + 2)
        }
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
        val result = Intent().apply { putExtra(RESULT_SUMMARY, text) }
        setResult(RESULT_OK, result)
        Toast.makeText(this, "✅ Ringkasan tersimpan", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handleBack() {
        val currentText = b.etSummary.text.toString()
        if (!saved && currentText != initialText) {
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

    override fun onBackPressed() {
        handleBack()
    }
}
