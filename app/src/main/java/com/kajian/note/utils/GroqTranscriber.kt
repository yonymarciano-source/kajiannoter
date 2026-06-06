package com.kajian.note.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * GroqTranscriber — online STT via Groq Whisper large-v3-turbo.
 *
 * - Gratis (rate limited 20 req/menit)
 * - Akurasi jauh di atas Whisper tiny/base offline
 * - Support Bahasa Indonesia + kosakata Arab
 * - API Key disimpan di SharedPreferences (user input)
 */
object GroqTranscriber {

    private const val TAG       = "GroqTranscriber"
    private const val GROQ_URL  = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL     = "whisper-large-v3-turbo"
    private const val BOUNDARY  = "KajianNote_Boundary_x7z9"

    // ── API Key management ───────────────────────────────────────────────
    fun getApiKey(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("kajian_prefs_v3", Context.MODE_PRIVATE)
        return prefs.getString("groq_api_key", "") ?: ""
    }

    fun saveApiKey(ctx: Context, key: String) {
        val prefs = ctx.getSharedPreferences("kajian_prefs_v3", Context.MODE_PRIVATE)
        prefs.edit().putString("groq_api_key", key.trim()).apply()
    }

    fun hasApiKey(ctx: Context) = getApiKey(ctx).isNotBlank()

    // ── Main transcribe ──────────────────────────────────────────────────
    suspend fun transcribe(
        ctx: Context,
        wavFile: File,
        lang: String,
        onProgress: (Int, String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(ctx)
        if (apiKey.isBlank()) {
            return@withContext "ERROR_NO_KEY"
        }

        try {
            onProgress(10, "Menghubungi Groq AI...")

        val langCode = when {
                lang.startsWith("id") -> "id"
                lang.startsWith("en") -> "en"
                lang.startsWith("ar") -> "ar"
                lang.startsWith("ko") -> "ko"
                lang.startsWith("ja") -> "ja"
                lang.startsWith("it") -> "it"
                lang.startsWith("es") -> "es"
                else -> null  // null = auto detect oleh Whisper (tidak kirim parameter language)
            }

            onProgress(25, "Mengirim audio ke Groq...")

            val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 60_000   // 60 detik connect
            conn.readTimeout    = 300_000  // 5 menit read — cukup untuk file besar
            conn.doOutput       = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")

            // Build multipart body
            val body = buildMultipart(wavFile, langCode)
            conn.setFixedLengthStreamingMode(body.size)

            onProgress(40, "Mengupload audio (${wavFile.length() / 1024}KB)...")
            conn.outputStream.use { it.write(body) }

            onProgress(70, "Menunggu hasil transkripsi...")
            val responseCode = conn.responseCode

            val responseBody = if (responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "HTTP $responseCode: $err")
                return@withContext parseGroqError(responseCode, err)
            }

            onProgress(90, "Menyusun hasil...")
            val json = JSONObject(responseBody)
            val rawText = json.optString("text", "").trim()

            // Apply kamus koreksi (sama seperti Mode Langsung STT)
            val corrected = DictionaryManager(ctx).correct(rawText)
            // Apply Arab post-processor (fonetik → tulisan Arab asli)
            val text = ArabicPostProcessor.process(corrected)

            Log.d(TAG, "Groq result (raw): '$rawText'")
            Log.d(TAG, "Groq result (corrected): '$text'")
            onProgress(100, "✅ Selesai!")
            text

        } catch (e: Exception) {
            Log.e(TAG, "transcribe error: ${e.message}", e)
            "ERROR: ${e.message}"
        }
    }

    // ── Build multipart/form-data body ───────────────────────────────────
    private fun buildMultipart(wavFile: File, langCode: String?): ByteArray {
        val out = ByteArrayOutputStream()
        val nl  = "\r\n"

        fun field(name: String, value: String) {
            out.write("--$BOUNDARY$nl".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"$nl$nl".toByteArray())
            out.write("$value$nl".toByteArray())
        }

        // model
        field("model", MODEL)

        // language — null = auto detect oleh Whisper
        if (langCode != null) field("language", langCode)

        // prompt — bantu Whisper kenali istilah Arab/Islam + tulis Arab asli
        val prompt = when (langCode) {
            "id" -> "Kajian Islam. Istilah: Alhamdulillah, Subhanallah, Allahu Akbar, Bismillah, " +
                    "Insya Allah, Astaghfirullah, Assalamu'alaikum, Al-Qur'an, Hadits, Sunnah, " +
                    "sholat, zakat, puasa, haji, Rasulullah ﷺ, taqwa, ikhlas, syukur."
            "ar" -> "بسم الله الرحمن الرحيم. الحمد لله، سبحان الله، الله أكبر، أستغفر الله، " +
                    "إن شاء الله، القرآن الكريم، الحديث النبوي، السنة، الصلاة، الزكاة."
            null -> "Islamic study. Alhamdulillah, Subhanallah, Allahu Akbar, Bismillah, " +
                    "Al-Qur'an, Hadith, الحمد لله، سبحان الله، بسم الله، إن شاء الله، الله أكبر."
            else -> "Alhamdulillah, Subhanallah, Allahu Akbar, Bismillah, Al-Qur'an, Hadith."
        }
        field("prompt", prompt)

        // response_format
        field("response_format", "json")

        // file
        out.write("--$BOUNDARY$nl".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"${wavFile.name}\"$nl".toByteArray())
        out.write("Content-Type: audio/wav$nl$nl".toByteArray())
        FileInputStream(wavFile).use { it.copyTo(out) }
        out.write(nl.toByteArray())

        // closing boundary
        out.write("--$BOUNDARY--$nl".toByteArray())

        return out.toByteArray()
    }

    // ── Parse error response ─────────────────────────────────────────────
    private fun parseGroqError(code: Int, body: String): String {
        return try {
            val msg = JSONObject(body)
                .optJSONObject("error")
                ?.optString("message", "Unknown error")
                ?: "HTTP $code"
            "ERROR: $msg"
        } catch (e: Exception) {
            "ERROR: HTTP $code"
        }
    }
}

    /**
     * Transkripsi file WAV panjang dengan otomatis split per 3 menit.
     * Tiap chunk dikirim ke Groq, hasilnya digabung.
     * Cocok untuk Mode Rekam kajian panjang (>11 menit).
     */
    suspend fun transcribeChunked(
        ctx: Context,
        wavFile: File,
        lang: String,
        onProgress: (Int, String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val chunks = WavChunker.split(wavFile, ctx.cacheDir)

        if (chunks.size == 1) {
            // File pendek — transcribe langsung
            return@withContext transcribe(ctx, wavFile, lang, onProgress)
        }

        val results = StringBuilder()
        val total   = chunks.size

        chunks.forEachIndexed { i, chunk ->
            val pct = ((i.toFloat() / total) * 80).toInt() + 10
            onProgress(pct, "Transkripsi bagian ${i + 1} dari $total...")
            android.util.Log.d("GroqTranscriber", "Chunk ${i+1}/$total: ${chunk.name}")

            val result = transcribe(ctx, chunk, lang) { _, _ -> }

            if (result.isNotBlank() && !result.startsWith("ERROR")) {
                // Deduplikasi overlap antar chunk
                val prev = results.toString()
                val deduped = deduplicateChunkOverlap(prev, result.trim())
                if (results.isNotEmpty()) results.append(" ")
                results.append(deduped)
            }

            // Hapus chunk sementara
            if (chunk.absolutePath != wavFile.absolutePath) chunk.delete()
        }

        onProgress(95, "Menggabungkan hasil...")
        val final = ArabicPostProcessor.process(
            DictionaryManager(ctx).correct(results.toString().trim())
        )
        android.util.Log.d("GroqTranscriber", "Chunked result: ${final.length} chars")
        final
    }

    private fun deduplicateChunkOverlap(existing: String, newText: String): String {
        if (existing.isBlank() || newText.isBlank()) return newText
        val existWords = existing.trimEnd().split("\\s+".toRegex())
        val newWords   = newText.trimStart().split("\\s+".toRegex())
        for (overlap in minOf(8, existWords.size, newWords.size) downTo 3) {
            val suffix = existWords.takeLast(overlap).joinToString(" ")
            val prefix = newWords.take(overlap).joinToString(" ")
            if (suffix.lowercase() == prefix.lowercase()) {
                return newWords.drop(overlap).joinToString(" ")
            }
        }
        return newText
    }
