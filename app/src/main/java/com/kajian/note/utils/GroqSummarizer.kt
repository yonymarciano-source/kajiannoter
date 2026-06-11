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

    /**
     * Catatan Rapi LENGKAP (Subscriber) — highlight ayat/hadits, struktur detail.
     * @param ctx           Context untuk ambil API key
     * @param title         Judul catatan
     * @param plainText     Teks transkripsi
     * @param detectedLang  Language code dari Note
     * @return Result<String> berisi ringkasan lengkap
     */
    suspend fun summarizeDetailed(
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
            val systemPrompt = buildSystemPromptDetailed(resolvedLang)
            val userPrompt   = buildUserPromptDetailed(title, plainText, resolvedLang)

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 2048)
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
                readTimeout    = 90_000
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

            Log.d(TAG, "Detailed summary generated (${content.length} chars)")
            Result.success(content)

        } catch (e: Exception) {
            Log.e(TAG, "summarizeDetailed error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    /**
     * Resolve bahasa yang sebenarnya dari kode language note.
     * Kalau auto/tidak dikenal, deteksi dari karakter teks transkripsi.
     */
    /**
     * Terjemahkan transkripsi ke bahasa target (Premium+).
     * @param targetLang "id" = Indonesia, "en" = English
     */
    suspend fun translate(
        ctx: Context,
        plainText: String,
        targetLang: String = "id"
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = GroqTranscriber.getApiKey(ctx)
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("NO_KEY"))

        try {
            val truncated = if (plainText.length > 6000) plainText.take(6000) + "\n...[terpotong]" else plainText
            val (systemPrompt, userPrompt) = when (targetLang) {
                "en" -> Pair(
                    "You are a professional translator. Translate the following Islamic study transcript to English. Preserve Arabic terms (Quran, hadith, Islamic terminology). Output only the translation, no commentary.",
                    "Translate to English:\n\n$truncated"
                )
                else -> Pair(
                    "Kamu adalah penerjemah profesional. Terjemahkan transkripsi kajian Islam berikut ke Bahasa Indonesia yang baik dan benar. Pertahankan istilah Arab (Al-Qur\'an, hadits, istilah Islam). Output hanya terjemahan, tanpa komentar.",
                    "Terjemahkan ke Bahasa Indonesia:\n\n$truncated"
                )
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 2048)
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
                })
            }

            val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true; connectTimeout = 30_000; readTimeout = 90_000
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }

            val code = conn.responseCode
            if (code != 200) return@withContext Result.failure(Exception("HTTP $code"))

            val content = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "translate error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Generate Poin Kunci (pengganti Mindmap) — kartu highlight + dalil (Premium+).
     * Subscriber: lebih detail dengan dalil lengkap.
     */
    suspend fun generatePoinKunci(
        ctx: Context,
        title: String,
        plainText: String,
        detectedLang: String = "id",
        detailed: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = GroqTranscriber.getApiKey(ctx)
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("NO_KEY"))

        try {
            val truncated = if (plainText.length > 6000) plainText.take(6000) + "\n...[terpotong]" else plainText
            val lang = resolveLang(detectedLang, plainText)

            val systemPrompt = if (detailed) {
                when {
                    lang.startsWith("id") -> """
                        Kamu adalah asisten kajian Islam. Buat POIN KUNCI LENGKAP dari kajian ini.
                        Format setiap poin sebagai berikut (gunakan pemisah "---" antar poin):
                        🔑 [Judul Poin]
                        [Penjelasan 2-3 kalimat]
                        📖 Dalil: [Kutipan ayat/hadits jika ada, atau "Tidak disebutkan secara eksplisit"]
                        💡 Amal: [Langkah praktis 1 kalimat]
                        ---
                        Buat 5-7 poin. Langsung mulai tanpa intro.
                    """.trimIndent()
                    else -> """
                        You are an Islamic study assistant. Create DETAILED KEY POINTS from this study session.
                        Format each point (use "---" separator):
                        🔑 [Point Title]
                        [2-3 sentence explanation]
                        📖 Dalil: [Quran/Hadith reference if mentioned, or "Not explicitly mentioned"]
                        💡 Action: [1 sentence practical step]
                        ---
                        Create 5-7 points. Start directly without intro.
                    """.trimIndent()
                }
            } else {
                when {
                    lang.startsWith("id") -> """
                        Kamu adalah asisten kajian Islam. Buat POIN KUNCI dari kajian ini.
                        Format setiap poin (gunakan pemisah "---" antar poin):
                        🔑 [Judul Poin]
                        [Penjelasan 1-2 kalimat]
                        ---
                        Buat 5-8 poin ringkas. Langsung mulai tanpa intro.
                    """.trimIndent()
                    else -> """
                        You are an Islamic study assistant. Create KEY POINTS from this study session.
                        Format each point (use "---" separator):
                        🔑 [Point Title]
                        [1-2 sentence explanation]
                        ---
                        Create 5-8 concise points. Start directly without intro.
                    """.trimIndent()
                }
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", if (detailed) 2048 else 1024)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Judul: $title\n\nTranskripsi:\n$truncated")
                    })
                })
            }

            val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true; connectTimeout = 30_000; readTimeout = 90_000
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }

            val code = conn.responseCode
            if (code != 200) return@withContext Result.failure(Exception("HTTP $code"))

            val content = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "generatePoinKunci error: ${e.message}", e)
            Result.failure(e)
        }
    }


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

    private fun buildSystemPromptDetailed(lang: String): String {
        return when {
            lang.startsWith("id") -> """
                Kamu adalah asisten ahli pencatatan kajian Islam.
                Buat Catatan Rapi LENGKAP dalam Bahasa Indonesia dengan struktur berikut:
                
                ## 📌 Tema Utama
                (1-2 kalimat tema pokok kajian)
                
                ## 📖 Poin-Poin Utama
                (5-10 poin, tiap poin diawali "• ")
                
                ## 🕌 Dalil & Referensi
                (Sebutkan ayat Al-Qur'an, hadits, atau dalil yang disebutkan, format: "→ [Sumber]: kutipan/parafrase")
                
                ## 💡 Kesimpulan & Amal
                (2-3 poin action item atau kesimpulan praktis)
                
                Pertahankan semua istilah Arab. Jika tidak ada dalil eksplisit, kosongkan bagian dalil.
                Langsung tulis struktur tanpa preamble.
            """.trimIndent()

            lang.startsWith("ar") -> """
                أنت مساعد متخصص في تدوين ملاحظات دروس الإسلام.
                اكتب ملاحظات مفصلة باللغة العربية بالهيكل التالي:
                
                ## 📌 الموضوع الرئيسي
                ## 📖 النقاط الأساسية
                ## 🕌 الأدلة والمراجع
                ## 💡 الخلاصة والعمل
                
                احرص على ذكر الآيات والأحاديث بدقة.
            """.trimIndent()

            else -> """
                You are an expert Islamic study note assistant.
                Create DETAILED study notes in the same language as the transcript:
                
                ## 📌 Main Theme
                ## 📖 Key Points (5-10 bullet points)
                ## 🕌 Dalil & References (Quran verses, hadith, scholarly references)
                ## 💡 Conclusions & Action Items
                
                Preserve all Arabic terms. Write directly without preamble.
            """.trimIndent()
        }
    }

    private fun buildUserPromptDetailed(title: String, text: String, lang: String): String {
        val truncated = if (text.length > 8000) text.take(8000) + "\n...[terpotong]" else text
        val instruction = when {
            lang.startsWith("id") -> "Buat Catatan Rapi Lengkap dari kajian berikut:"
            lang.startsWith("ar") -> "أنشئ ملاحظات مفصلة للدرس التالي:"
            else -> "Create detailed study notes from the following:"
        }
        return """
            $instruction
            
            Judul / Title: $title
            
            Transkripsi:
            $truncated
        """.trimIndent()
    }
}
