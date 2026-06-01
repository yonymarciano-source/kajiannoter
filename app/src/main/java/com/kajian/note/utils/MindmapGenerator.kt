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
 * MindmapGenerator — generate mindmap structure via Groq llama.
 *
 * Output JSON format:
 * {
 *   "title": "Judul Kajian",
 *   "nodes": [
 *     {
 *       "id": "1",
 *       "label": "Topik Utama",
 *       "children": [
 *         { "id": "1.1", "label": "Sub Topik A", "children": [] },
 *         { "id": "1.2", "label": "Sub Topik B", "children": [] }
 *       ]
 *     }
 *   ]
 * }
 */
object MindmapGenerator {

    private const val TAG      = "MindmapGenerator"
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL    = "llama-3.3-70b-versatile"

    data class MindmapNode(
        val id: String,
        val label: String,
        val children: List<MindmapNode> = emptyList()
    )

    data class Mindmap(
        val title: String,
        val nodes: List<MindmapNode>
    )

    suspend fun generate(
        ctx: Context,
        title: String,
        plainText: String,
        detectedLang: String = "id"
    ): Result<Mindmap> = withContext(Dispatchers.IO) {
        val apiKey = GroqTranscriber.getApiKey(ctx)
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("NO_KEY"))

        try {
            val systemPrompt = buildSystemPrompt(detectedLang)
            val userPrompt   = buildUserPrompt(title, plainText, detectedLang)

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 1024)
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user");   put("content", userPrompt) })
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

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }

            val code = conn.responseCode
            val resp = if (code == 200) conn.inputStream.bufferedReader().readText()
                       else { conn.errorStream?.bufferedReader()?.readText() ?: ""; return@withContext Result.failure(Exception("HTTP $code")) }

            val content = JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // Strip markdown code fences if present
            val jsonStr = content
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val mindmap = parseMindmap(jsonStr, title)
            Result.success(mindmap)

        } catch (e: Exception) {
            Log.e(TAG, "generate error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseMindmap(jsonStr: String, fallbackTitle: String): Mindmap {
        return try {
            val obj   = JSONObject(jsonStr)
            val title = obj.optString("title", fallbackTitle)
            val nodes = parseNodes(obj.optJSONArray("nodes") ?: JSONArray())
            Mindmap(title, nodes)
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message}")
            Mindmap(fallbackTitle, listOf(MindmapNode("1", "Gagal parse mindmap", emptyList())))
        }
    }

    private fun parseNodes(arr: JSONArray): List<MindmapNode> {
        val list = mutableListOf<MindmapNode>()
        for (i in 0 until arr.length()) {
            val obj  = arr.optJSONObject(i) ?: continue
            val id   = obj.optString("id", "$i")
            val lbl  = obj.optString("label", "").take(50)
            val kids = parseNodes(obj.optJSONArray("children") ?: JSONArray())
            list.add(MindmapNode(id, lbl, kids))
        }
        return list
    }

    private fun buildSystemPrompt(lang: String): String = when {
        lang.startsWith("id") -> """
            Kamu adalah asisten yang membuat mindmap dari catatan kajian Islam.
            Kembalikan HANYA JSON valid, tanpa teks lain, tanpa markdown.
            Format JSON:
            {"title":"...","nodes":[{"id":"1","label":"Topik Utama","children":[{"id":"1.1","label":"Sub-topik","children":[]}]}]}
            Maksimal 4 node utama, tiap node maksimal 4 anak. Label singkat, max 8 kata.
            Pertahankan istilah Arab penting.
        """.trimIndent()
        lang.startsWith("ar") -> """
            أنت مساعد لإنشاء خريطة ذهنية من ملاحظات الدروس الإسلامية.
            أرجع JSON فقط بدون نص آخر.
            {"title":"...","nodes":[{"id":"1","label":"موضوع رئيسي","children":[{"id":"1.1","label":"موضوع فرعي","children":[]}]}]}
        """.trimIndent()
        else -> """
            You are an assistant that creates mindmaps from Islamic study notes.
            Return ONLY valid JSON, no other text, no markdown fences.
            Format: {"title":"...","nodes":[{"id":"1","label":"Main topic","children":[{"id":"1.1","label":"Sub-topic","children":[]}]}]}
            Max 4 main nodes, max 4 children each. Labels concise, max 8 words.
        """.trimIndent()
    }

    private fun buildUserPrompt(title: String, text: String, lang: String): String {
        val truncated = if (text.length > 4000) text.take(4000) + "...[terpotong]" else text
        val instruction = when {
            lang.startsWith("id") -> "Buat mindmap dari catatan kajian ini:"
            lang.startsWith("ar") -> "أنشئ خريطة ذهنية من هذه الملاحظات:"
            else                  -> "Create a mindmap from these study notes:"
        }
        return "$instruction\n\nJudul: $title\n\n$truncated"
    }
}
