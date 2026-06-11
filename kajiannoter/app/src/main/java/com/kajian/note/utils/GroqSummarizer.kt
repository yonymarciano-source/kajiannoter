package com.kajian.note.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * GroqSummarizer — generate ringkasan catatan kajian via Groq Chat API.
 *
 * - Pakai key yang sama dengan GroqTranscriber (zero setup tambahan)
 * - Model: llama-3.3-70b-versatile (gratis, multilingual)
 * - Auto-detect bahasa dari detectedLanguage note
 */
object GroqSummarizer {

    private const val TAG      = "GroqSummarizer"
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL    = "llama-3.3-70b-versatile"

    /**
     * Generate judul otomatis dari teks transkripsi via Groq llama.
     * Dipanggil setelah note disimpan, judul diganti jika berhasil.
     * @return Result<String> judul pendek (max 8 kata)
     */
    suspend fun autoTitle(
        ctx: Context,
        plainText: String,
        detectedLang: String = "id"
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = GroqTranscriber.getApiKey(ctx)
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("NO_KEY"))

        try {
            val sample = plainText.take(800)
            val lang = resolveLang(detectedLang, sample)
            val instruction = when {
                lang.startsWith("id") -> "Buat judul singkat (maksimal 7 kata) dalam Bahasa Indonesia untuk catatan kajian berikut. Hanya tulis judulnya saja, tanpa tanda kutip, tanpa penjelasan."
                lang.startsWith("ar") -> "اكتب عنواناً موجزاً (7 كلمات كحد أقصى) لهذه الملاحظة. فقط العنوان بدون شرح."
                else -> "Write a short title (max 7 words) for this study note. Only write the title, no quotes, no explanation."
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 30)
                put("temperature", 0.4)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "$instruction\n\nTeks:\n$sample")
                    })
                })
            }

            val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout    = 30_000
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }

            val code = conn.responseCode
            if (code != 200) return@withContext Result.failure(Exception("HTTP $code"))

            val raw = conn.inputStream.bufferedReader().readText()
            val title = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .trimStart('"', '\'').trimEnd('"', '\'')  // hapus tanda kutip kalau ada

            Result.success(title)
        } catch (e: Exception) {
            Log.e(TAG, "autoTitle error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * @param ctx           Context untuk ambil API key
     * @param title         Judul catatan
     * @param plainText     Teks transkripsi
     * @param detectedLang  Language code (id-ID, en-US, ar-SA, dll) dari Note
     * @return Result<String> berisi ringkasan, atau failure jika error
     */
    suspend fun summarize(
        ctx: Context,
        title: String,
        plainText: String,
        detectedLang: String = "auto"
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = GroqTranscriber.getApiKey(ctx)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("NO_KEY"))
        }

        try {
            val resolvedLang = resolveLang(detectedLang, plainText)
            val systemPrompt = buildSystemPrompt(resolvedLang)
            val userPrompt   = buildUserPrompt(title, plainText, resolvedLang)

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 1024)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
            }

            val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 30_000
                readTimeout    = 60_000
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(requestBody.toString())
            }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "HTTP $responseCode: $err")
                return@withContext Result.failure(Exception("HTTP $responseCode"))
            }

            val content = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            Log.d(TAG, "Summary generated (${content.length} chars)")
            Result.success(content)

        } catch (e: Exception) {
            Log.e(TAG, "summarize error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    /**
     * Resolve bahasa yang sebenarnya dari kode language note.
     * Kalau auto/tidak dikenal, deteksi dari karakter teks transkripsi.
     */
    private fun resolveLang(detectedLang: String, text: String): String {
        return when {
            detectedLang.startsWith("id") -> "id"
            detectedLang.startsWith("ar") -> "ar"
            detectedLang.startsWith("en") -> "en"
            detectedLang.startsWith("ko") -> "ko"
            detectedLang.startsWith("ja") -> "ja"
            detectedLang.startsWith("it") -> "it"
            detectedLang.startsWith("es") -> "es"
            else -> {
                // Auto detect dari karakter teks
                val sample = text.take(500)
                val arabCount = sample.count { it in '\u0600'..'\u06FF' }
                val latinCount = sample.count { it in 'a'..'z' || it in 'A'..'Z' }
                val koCount = sample.count { it in '\uAC00'..'\uD7A3' }
                val jaCount = sample.count { it in '\u3040'..'\u30FF' }
                when {
                    arabCount > latinCount * 0.3 -> "ar"  // banyak Arab
                    koCount > 20  -> "ko"
                    jaCount > 20  -> "ja"
                    // Deteksi Indonesia dari kata-kata umum
                    Regex("\\b(dan|yang|dengan|untuk|dari|ini|itu|adalah|juga|sudah|akan|saya|kita|Allah|bahwa|karena)\\b")
                        .containsMatchIn(sample.lowercase()) -> "id"
                    else -> "id"  // default Indonesia karena konteks kajian Islam
                }
            }
        }
    }

    private fun buildSystemPrompt(lang: String): String {
        return when {
            lang.startsWith("id") -> """
                Kamu adalah asisten yang membantu meringkas catatan kajian Islam.
                Buat ringkasan dalam Bahasa Indonesia yang padat dan terstruktur.
                Pertahankan istilah Arab penting (nama surah, hadits, doa, dll).
                Format: 5-8 poin ringkasan, tiap poin diawali "• ".
                Jangan tambahkan intro/outro, langsung ke poin-poin.
            """.trimIndent()

            lang.startsWith("ar") -> """
                أنت مساعد لتلخيص ملاحظات دروس الإسلام.
                اكتب ملخصاً واضحاً ومنظماً باللغة العربية.
                الصيغة: 5-8 نقاط رئيسية، كل نقطة تبدأ بـ "• ".
                لا تضف مقدمة أو خاتمة، اذهب مباشرة إلى النقاط.
            """.trimIndent()

            lang.startsWith("en") -> """
                You are an assistant that summarizes Islamic study notes.
                Write a concise, structured summary in English.
                Preserve important Arabic terms (surah names, hadith, duas, etc).
                Format: 5-8 bullet points, each starting with "• ".
                No intro or outro, go directly to the points.
            """.trimIndent()

            lang.startsWith("ko") -> """
                당신은 이슬람 학습 노트를 요약하는 도우미입니다.
                한국어로 간결하고 구조적인 요약을 작성하세요.
                중요한 아랍어 용어(수라 이름, 하디스 등)는 유지하세요.
                형식: 5-8개의 핵심 포인트, 각 포인트는 "• "로 시작.
            """.trimIndent()

            lang.startsWith("ja") -> """
                あなたはイスラム学習ノートを要約するアシスタントです。
                日本語で簡潔で構造的な要約を書いてください。
                重要なアラビア語の用語（スーラ名、ハディースなど）は保持してください。
                形式：5〜8つの箇条書き、各ポイントは「• 」で始まる。
            """.trimIndent()

            lang.startsWith("it") -> """
                Sei un assistente che riassume note di lezioni islamiche.
                Scrivi un riassunto conciso e strutturato in italiano.
                Mantieni i termini arabi importanti (nomi delle sure, hadith, ecc).
                Formato: 5-8 punti principali, ognuno inizia con "• ".
            """.trimIndent()

            lang.startsWith("es") -> """
                Eres un asistente que resume notas de clases islámicas.
                Escribe un resumen conciso y estructurado en español.
                Mantén los términos árabes importantes (nombres de suras, hadices, etc).
                Formato: 5-8 puntos principales, cada uno comienza con "• ".
            """.trimIndent()

            else -> """
                You are an assistant that summarizes Islamic study notes.
                Detect the language of the transcript and respond in the same language.
                Write a concise, structured summary preserving important Arabic terms.
                Format: 5-8 bullet points, each starting with "• ".
                No intro or outro, go directly to the points.
            """.trimIndent()
        }
    }

    private fun buildUserPrompt(title: String, text: String, lang: String): String {
        // Potong kalau terlalu panjang (Groq context 8k token, aman sampai ~6000 char)
        val truncated = if (text.length > 6000) text.take(6000) + "\n...[terpotong]" else text

        val instruction = when {
            lang.startsWith("id") -> "Buatkan ringkasan dari catatan kajian berikut:"
            lang.startsWith("ar") -> "لخص الملاحظات التالية:"
            lang.startsWith("en") -> "Summarize the following study notes:"
            lang.startsWith("ko") -> "다음 학습 노트를 요약하세요:"
            lang.startsWith("ja") -> "次のノートを要約してください:"
            lang.startsWith("it") -> "Riassumi le seguenti note:"
            lang.startsWith("es") -> "Resume las siguientes notas:"
            else                  -> "Summarize the following study notes:"
        }

        return """
            $instruction
            
            Judul / Title: $title
            
            Transkripsi:
            $truncated
        """.trimIndent()
    }
}
