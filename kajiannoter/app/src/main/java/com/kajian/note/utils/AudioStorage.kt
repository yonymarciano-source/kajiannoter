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
     * Pindah file audio dari cache ke storage permanen.
     * Return path baru, atau string kosong jika gagal.
     */
    fun saveAudio(ctx: Context, tempFile: File, noteId: Long): String {
        return try {
            val dir  = getAudioDir(ctx)
            val dest = File(dir, "kajian_${noteId}.wav")
            tempFile.copyTo(dest, overwrite = true)
            tempFile.delete()
            dest.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("AudioStorage", "Save error: ${e.message}", e)
            ""
        }
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
