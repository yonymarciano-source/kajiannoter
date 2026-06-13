package com.kajian.note.utils

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * AudioStorage — kelola penyimpanan file audio kajian.
 * Audio disimpan di Documents/KajianNote/audio/ agar tidak terhapus cache.
 */
object AudioStorage {

    fun getAudioDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "KajianNote/audio")
        dir.mkdirs()
        return dir
    }

    /**
     * DEPRECATED (Opsi B) — audio tidak disimpan permanen.
     * Langsung hapus temp file.
     */
    fun saveAudio(ctx: Context, tempFile: File, noteId: Long): String {
        try { tempFile.delete() } catch (_: Exception) {}
        return ""
    }

    /**
     * Hapus semua audio lama yang tersimpan (migrasi dari Opsi A ke B).
     */
    fun cleanupAllAudio(ctx: Context) {
        try {
            getAudioDir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * Hapus file audio untuk note tertentu.
     */
    fun deleteAudio(audioPath: String) {
        try {
            if (audioPath.isNotBlank()) File(audioPath).delete()
        } catch (_: Exception) {}
    }

    fun exists(audioPath: String): Boolean =
        audioPath.isNotBlank() && File(audioPath).exists()
}
