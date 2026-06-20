package com.kajian.note.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.util.concurrent.TimeUnit

/**
 * AssemblyAI Transcriber — multi-speaker diarization untuk Subscriber tier.
 * Menggunakan OkHttp untuk upload yang lebih reliable di Android.
 */
object AssemblyAITranscriber {

    private const val TAG = "AssemblyAITranscriber"
    private const val BASE_URL = "https://api.assemblyai.com/v2"
    private const val PREFS = "kajian_prefs_v3"
    private const val KEY_API = "assemblyai_api_key"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── API Key management ───────────────────────────────────────────────

    fun getApiKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "") ?: ""

    fun saveApiKey(ctx: Context, key: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, key.trim()).apply()

    fun hasApiKey(ctx: Context) = getApiKey(ctx).isNotBlank()

    // ── Data model ───────────────────────────────────────────────────────

    data class SpeakerSegment(
        val speaker: String,
        val text: String,
        val startMs: Long,
        val endMs: Long
    )

    data class DiarizationResult(
        val segments: List<SpeakerSegment>,
        val fullText: String,
        val speakerCount: Int
    )

    // ── Main transcribe ──────────────────────────────────────────────────

    suspend fun transcribe(
        ctx: Context,
        audioFile: File,
        language: String = "id",
        maxSpeakers: Int = 0,
        onProgress: (Int, String) -> Unit
    ): DiarizationResult = withContext(Dispatchers.IO) {

        val apiKey = getApiKey(ctx).trim()
        if (apiKey.isBlank()) throw IllegalStateException("AssemblyAI API key belum diset")

        Log.d(TAG, "Starting transcription, file size: ${audioFile.length()} bytes")

        // Step 1 — Upload audio
        onProgress(10, "Mengunggah audio...")
        val uploadUrl = uploadAudio(apiKey, audioFile)
        Log.d(TAG, "Audio uploaded: $uploadUrl")

        // Step 2 — Submit job
        onProgress(25, "Memulai identifikasi speaker...")
        val langCode = when {
            language.startsWith("ar") -> "ar"
            language.startsWith("en") -> "en"
            language == "auto"        -> null  // AssemblyAI auto-detect 99 bahasa
            else -> "id"
        }
        val transcriptId = submitJob(apiKey, uploadUrl, langCode, maxSpeakers)
        Log.d(TAG, "Job submitted: $transcriptId")

        // Step 3 — Poll
        onProgress(40, "Mengidentifikasi speaker...")
        val result = pollUntilDone(apiKey, transcriptId) { progress ->
            val mapped = 40 + (progress * 0.5).toInt()
            onProgress(mapped, "Memproses audio... ($progress%)")
        }

        // Step 4 — Parse
        onProgress(95, "Menyusun transkripsi...")
        parseResult(result)
    }

    // ── Upload ───────────────────────────────────────────────────────────

    private fun uploadAudio(apiKey: String, file: File): String {
        val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/upload")
            .addHeader("Authorization", apiKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Upload failed ${response.code}: $body")
            }
            return JSONObject(body).getString("upload_url")
        }
    }

    // ── Submit job ───────────────────────────────────────────────────────

    private fun submitJob(
        apiKey: String,
        audioUrl: String,
        language: String?,
        maxSpeakers: Int
    ): String {
        val bodyJson = JSONObject().apply {
            put("audio_url", audioUrl)
            put("speaker_labels", true)
            put("speech_models", org.json.JSONArray().put("universal-3-pro"))  // ✅ Model terbaik AssemblyAI untuk diarization
            put("disfluencies", false)            // ✅ Filter "eh", "um", "hmm" supaya tidak trigger speaker baru
            put("punctuate", true)
            put("format_text", true)
            if (language != null) {
                put("language_code", language)
            } else {
                put("language_detection", true)   // AssemblyAI auto-detect 99 bahasa
            }
            if (maxSpeakers > 0) {
                // User pilih 2/3/4 — hint ke AssemblyAI
                put("speakers_expected", maxSpeakers)
            }
            // Auto (maxSpeakers == 0) → tidak set speakers_expected sama sekali
            // Biarkan AssemblyAI deteksi jumlah speaker sendiri tanpa constraint

            // ✅ Keyterms prompt — universal-3-pro, harus JSON array of strings
            put("keyterms_prompt", org.json.JSONArray(IslamicVocabularyProvider.wordBoostList.take(100)))
        }

        val requestBody = bodyJson.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/transcript")
            .addHeader("Authorization", apiKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Submit failed ${response.code}: $body")
            }
            return JSONObject(body).getString("id")
        }
    }

    // ── Poll status ──────────────────────────────────────────────────────

    private suspend fun pollUntilDone(
        apiKey: String,
        transcriptId: String,
        onProgress: (Int) -> Unit
    ): JSONObject {
        var attempts = 0
        val maxAttempts = 120 // max 6 menit (3 detik per poll)

        while (attempts < maxAttempts) {
            delay(3000)
            attempts++

            val request = Request.Builder()
                .url("$BASE_URL/transcript/$transcriptId")
                .addHeader("Authorization", apiKey)
                .get()
                .build()

            val json = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw IOException("Poll failed ${response.code}: $body")
                }
                JSONObject(body)
            }

            val status = json.getString("status")
            Log.d(TAG, "Poll $attempts: status=$status")

            when (status) {
                "completed" -> return json
                "error" -> throw IOException("AssemblyAI error: ${json.optString("error")}")
                else -> onProgress(minOf(90, attempts * 2))
            }
        }

        throw IOException("Timeout: transkripsi melebihi 6 menit")
    }

    // ── Parse result ─────────────────────────────────────────────────────

    private fun parseResult(json: JSONObject): DiarizationResult {
        val fullText = json.optString("text", "")
        val utterances = json.optJSONArray("utterances")

        if (utterances == null || utterances.length() == 0) {
            return DiarizationResult(
                segments = listOf(SpeakerSegment("A", fullText, 0L, 0L)),
                fullText = fullText,
                speakerCount = 1
            )
        }

        val segments = mutableListOf<SpeakerSegment>()
        val speakers = mutableSetOf<String>()

        for (i in 0 until utterances.length()) {
            val u = utterances.getJSONObject(i)
            segments.add(SpeakerSegment(
                speaker = u.getString("speaker"),
                text = u.getString("text"),
                startMs = u.getLong("start"),
                endMs = u.getLong("end")
            ))
            speakers.add(u.getString("speaker"))
        }

        return DiarizationResult(
            segments = segments,
            fullText = fullText,
            speakerCount = speakers.size
        )
    }
}
