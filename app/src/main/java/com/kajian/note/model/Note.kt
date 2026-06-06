package com.kajian.note.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val transcriptJson: String = "[]",
    val plainText: String = "",
    val detectedLanguage: String = "auto",
    val speakerNamesJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val wordCount: Int = 0,
    val speakerCount: Int = 1,
    val audioPath: String = "",          // path ke file audio tersimpan (M4A/WAV)
    val summaryText: String = "",        // ringkasan AI yang sudah digenerate + diedit user
    val bookmarksJson: String = "[]"     // list timestamp bookmark dalam ms: [1230, 45000, ...]
) {
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return sdf.format(Date(createdAt))
    }

    fun getFormattedDuration(): String {
        val totalSeconds = durationMs / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    fun getPreview(maxLen: Int = 100): String {
        return if (plainText.length > maxLen) plainText.take(maxLen) + "…"
               else plainText
    }

    fun getLanguageLabel(): String = when (detectedLanguage) {
        "id-ID" -> "🇮🇩 ID"
        "en-US" -> "🇺🇸 EN"
        "ar-SA" -> "🌙 AR"
        "ko-KR" -> "🇰🇷 KO"
        "ja-JP" -> "🇯🇵 JA"
        "it-IT" -> "🇮🇹 IT"
        "es-ES" -> "🇪🇸 ES"
        else    -> "🌐 AUTO"
    }

    fun hasAudio(): Boolean = audioPath.isNotBlank() &&
        java.io.File(audioPath).exists()
}
