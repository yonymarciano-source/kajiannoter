package com.kajian.note.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * AssemblyAI Transcriber — multi-speaker diarization untuk Subscriber tier.
 *
 * Flow:
 *   1. Upload audio file ke AssemblyAI
 *   2. Submit transcription job dengan speaker_labels=true
 *   3. Poll status sampai selesai
 *   4. Return list SpeakerSegment
 */
object AssemblyAITranscriber {

    private const val TAG = "AssemblyAITranscriber"
    private const val BASE_URL = "https://api.assemblyai.com/v2"
    private const val PREFS = "kajian_prefs_v3"
    private const val KEY_API = "assemblyai_api_key"

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
        val speaker: String,      // "A", "B", "C", dll
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
        maxSpeakers: Int = 0,         // 0 = auto detect
        onProgress: (Int, String) -> Unit
    ): DiarizationResult = withContext(Dispatchers.IO) {

        val apiKey = getApiKey(ctx)
        if (apiKey.isBlank()) {
            throw IllegalStateException("AssemblyAI API key belum diset")
        }

        // Step 1 — Upload audio
        onProgress(10, "Mengunggah audio...")
        val uploadUrl = uploadAudio(apiKey, audioFile)
        Log.d(TAG, "Audio uploaded: $uploadUrl")

        // Step 2 — Submit transcription job
        onProgress(25, "Memulai identifikasi speaker...")
        val langCode = when {
            language.startsWith("id") -> "id"
            language.startsWith("ar") -> "ar"
            language.startsWith("en") -> "en"
            else -> "id"
        }

        val transcriptId = submitJob(apiKey, uploadUrl, langCode, maxSpeakers)
        Log.d(TAG, "Job submitted: $transcriptId")

        // Step 3 — Poll status
        onProgress(40, "Memproses audio...")
        val result = pollUntilDone(apiKey, transcriptId) { progress ->
            val mapped = 40 + (progress * 0.5).toInt()
            onProgress(mapped, "Mengidentifikasi speaker... ($progress%)")
        }

        // Step 4 — Parse result
        onProgress(95, "Menyusun transkripsi...")
        parseResult(result)
    }

    // ── Upload audio ─────────────────────────────────────────────────────

    private fun uploadAudio(apiKey: String, file: File): String {
        val url = URL("$BASE_URL/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", apiKey)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Transfer-Encoding", "chunked")
            connectTimeout = 30000
            readTimeout = 60000
            doOutput = true
        }

        FileInputStream(file).use { fis ->
            conn.outputStream.use { os ->
                fis.copyTo(os, bufferSize = 8192)
            }
        }

        val responseCode = conn.responseCode
        val response = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
            conn.disconnect()
            throw IOException("Upload failed $responseCode: $errBody")
        }
        conn.disconnect()

        return JSONObject(response).getString("upload_url")
    }

    // ── Submit job ───────────────────────────────────────────────────────

    private fun submitJob(
        apiKey: String,
        audioUrl: String,
        language: String,
        maxSpeakers: Int
    ): String {
        val url = URL("$BASE_URL/transcript")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", apiKey)
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        val body = JSONObject().apply {
            put("audio_url", audioUrl)
            put("speaker_labels", true)
            put("language_code", language)
            put("punctuate", true)
            put("format_text", true)
            if (maxSpeakers > 0) put("speakers_expected", maxSpeakers)
        }

        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

        val responseCode = conn.responseCode
        val response = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
            conn.disconnect()
            throw IOException("Submit failed $responseCode: $errBody")
        }
        conn.disconnect()

        return JSONObject(response).getString("id")
    }

    // ── Poll status ──────────────────────────────────────────────────────

    private suspend fun pollUntilDone(
        apiKey: String,
        transcriptId: String,
        onProgress: (Int) -> Unit
    ): JSONObject {
        var attempts = 0
        val maxAttempts = 120  // max 10 menit (5 detik per poll)

        while (attempts < maxAttempts) {
            delay(3000)
            attempts++

            val url = URL("$BASE_URL/transcript/$transcriptId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", apiKey)
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = conn.responseCode
            val response = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
                conn.disconnect()
                throw IOException("Poll failed $responseCode: $errBody")
            }
            conn.disconnect()

            val json = JSONObject(response)
            val status = json.getString("status")

            Log.d(TAG, "Poll $attempts: status=$status")

            when (status) {
                "completed" -> return json
                "error" -> throw IOException("AssemblyAI error: ${json.optString("error")}")
                else -> {
                    // queued, processing — estimate progress
                    val progress = minOf(90, attempts * 2)
                    onProgress(progress)
                }
            }
        }

        throw IOException("Timeout: transkripsi melebihi 10 menit")
    }

    // ── Parse result ─────────────────────────────────────────────────────

    private fun parseResult(json: JSONObject): DiarizationResult {
        val fullText = json.optString("text", "")
        val utterances = json.optJSONArray("utterances")

        if (utterances == null || utterances.length() == 0) {
            // Fallback: tidak ada utterances, return sebagai satu speaker
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
            val speaker = u.getString("speaker")  // "A", "B", dll
            val text = u.getString("text")
            val start = u.getLong("start")
            val end = u.getLong("end")

            segments.add(SpeakerSegment(speaker, text, start, end))
            speakers.add(speaker)
        }

        return DiarizationResult(
            segments = segments,
            fullText = fullText,
            speakerCount = speakers.size
        )
    }
}
