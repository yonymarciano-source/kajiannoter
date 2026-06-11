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
    val audioPath: String = "",
    val summaryText: String = "",
    val bookmarksJson: String = "[]",
    val folderId: Long = 0,          // 0 = Semua Catatan (default)
    val isPremiumContent: Boolean = false  // flag konten premium (diarization etc)
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

    fun getLanguageLabel(): String = when {
        detectedLanguage.startsWith("id") -> "🇮🇩 ID"
        detectedLanguage.startsWith("en") -> "🇺🇸 EN"
        detectedLanguage.startsWith("ar") -> "🌙 AR"
        detectedLanguage.startsWith("ms") -> "🇲🇾 MS"
        detectedLanguage.startsWith("ko") -> "🇰🇷 KO"
        detectedLanguage.startsWith("ja") -> "🇯🇵 JA"
        detectedLanguage.startsWith("zh") -> "🇨🇳 ZH"
        detectedLanguage.startsWith("fr") -> "🇫🇷 FR"
        detectedLanguage.startsWith("de") -> "🇩🇪 DE"
        detectedLanguage.startsWith("it") -> "🇮🇹 IT"
        detectedLanguage.startsWith("es") -> "🇪🇸 ES"
        detectedLanguage.startsWith("tr") -> "🇹🇷 TR"
        detectedLanguage.startsWith("ru") -> "🇷🇺 RU"
        detectedLanguage.startsWith("hi") -> "🇮🇳 HI"
        detectedLanguage.startsWith("pt") -> "🇵🇹 PT"
        else -> "🌐 AUTO"
    }

    fun hasAudio(): Boolean = audioPath.isNotBlank() &&
        java.io.File(audioPath).exists()
}

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val emoji: String = "📁",
    val colorHex: String = "#00E676",
    val createdAt: Long = System.currentTimeMillis(),
    val noteCount: Int = 0
)
