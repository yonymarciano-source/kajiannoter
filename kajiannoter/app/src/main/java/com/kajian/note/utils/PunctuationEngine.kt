package com.kajian.note.utils

import java.util.Locale

/**
 * PunctuationEngine v2 — Hybrid: pause-based + text-based punctuation.
 *
 * Problem with v1: thresholds (700ms/1800ms) were too high for fast STT cycling.
 * STT restarts in 80ms, so inter-segment pauses are typically 150-400ms — below
 * every threshold, resulting in no commas or periods.
 *
 * v2 approach:
 * 1. Lower pause thresholds to match actual STT cycle timing
 * 2. Add TEXT-BASED sentence detection (word count, sentence-final words)
 * 3. Keep question/exclamation detection from content + RMS
 */
object PunctuationEngine {

    data class Result(
        val prefix: String,
        val suffix: String,
        val capitalize: Boolean
    )

    // ── Sentence-ending words (Indonesian) ────────────────────────────────
    // Words that typically end a complete thought/sentence
    private val SENTENCE_FINAL_ID = setOf(
        "itu", "ini", "tersebut", "saja", "juga", "pun",
        "dong", "lah", "deh", "nih", "gitu", "tuh", "gak",
        "begitu", "demikian", "sekali", "banget", "benar",
        "betul", "jelas", "pasti", "tentu", "memang",
        "tadi", "kemarin", "nanti", "sekarang",
        "sudah", "telah", "sudah", "belum", "tidak", "bukan",
        "oke", "baik", "baiklah", "selesai", "cukup"
    )

    // ── Question words ─────────────────────────────────────────────────────
    private val QUESTION_START_ID = setOf(
        "apakah", "apa", "siapa", "bagaimana", "mengapa", "kenapa",
        "kapan", "dimana", "di mana", "berapa", "mana", "adakah",
        "bukankah", "benarkah", "tidakkah"
    )
    private val QUESTION_TAIL_ID = setOf(
        " ya", " kan", " bukan", " tidak", " dong", "kah"
    )
    private val QUESTION_START_EN = setOf(
        "what", "who", "where", "when", "why", "how", "which",
        "is", "are", "was", "were", "do", "does", "did",
        "can", "could", "would", "should", "will"
    )

    // ── Exclamation / emphasis words ───────────────────────────────────────
    private val EXCLAMATION_WORDS = setOf(
        "subhanallah", "masya allah", "masyaallah", "alhamdulillah",
        "allahu akbar", "astaghfirullah", "luar biasa", "hebat",
        "mantap", "amazing", "incredible", "wow", "wah"
    )

    // ── Connectors (don't put punctuation BEFORE these) ───────────────────
    private val CONNECTORS_ID = setOf(
        "dan", "atau", "serta", "dengan", "karena", "sebab",
        "tetapi", "tapi", "namun", "akan tetapi", "melainkan",
        "bahwa", "jadi", "oleh karena itu", "kemudian", "lalu",
        "setelah itu", "selanjutnya", "maka", "sehingga",
        "walaupun", "meskipun", "biarpun", "agar", "supaya",
        "untuk", "demi", "guna"
    )

    // ── Main analysis ──────────────────────────────────────────────────────
    fun analyze(
        text: String,
        pauseMs: Long,
        avgRms: Float,
        maxRms: Float,
        prevText: String,
        isFirst: Boolean,
        language: String
    ): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result(" ", "", false)

        val lower     = trimmed.lowercase(Locale.getDefault())
        val firstWord = lower.split(" ").firstOrNull() ?: ""
        val lastWord  = lower.trimEnd().split(" ").lastOrNull() ?: ""
        val isArabic  = language.startsWith("ar")
        val isId      = language.startsWith("id")
        val isEn      = language.startsWith("en")

        // Already has terminal punctuation → just add space
        if (trimmed.last().let { it == '.' || it == '!' || it == '?' || it == '،' }) {
            val cap = isFirst || prevText.trimEnd().lastOrNull()
                ?.let { it == '.' || it == '!' || it == '?' } == true
            return Result(if (isFirst) "" else " ", "", cap && !isArabic)
        }

        // ── Detect question ──────────────────────────────────────────────
        val isQuestion = detectQuestion(lower, firstWord, lastWord, language)

        // ── Detect exclamation (high volume OR emphasis words) ────────────
        val isExclamation = !isQuestion && (
            maxRms > 22f ||
            EXCLAMATION_WORDS.any { lower.contains(it) }
        )

        // ── Detect sentence ending in PREVIOUS segment ────────────────────
        // → determines what PREFIX to add before this new segment
        val prevTrimmed   = prevText.trimEnd()
        val prevLastWord  = prevTrimmed.split(" ").lastOrNull()?.lowercase() ?: ""
        val prevWordCount = prevTrimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val prevLastChar  = prevTrimmed.lastOrNull()

        val prevAlreadyPunctuated = prevLastChar?.let {
            it == '.' || it == '!' || it == '?' || it == ',' || it == '\n'
        } == true

        val startsWithConnector = isId && CONNECTORS_ID.any { lower.startsWith(it) }

        // Build prefix (punctuation after previous segment, before this one)
        val prefix = when {
            isFirst -> ""
            prevAlreadyPunctuated -> " "
            startsWithConnector -> " "  // connector: just space, no punct before it

            // Very long pause → new paragraph
            pauseMs > 2000 -> "\n\n"

            // Long pause → period + new sentence
            pauseMs > 600 -> ". "

            // Medium pause → comma
            pauseMs > 180 -> ", "

            // Short pause but prev segment is a "complete sentence":
            // - ends with sentence-final word, OR
            // - has enough words (>= 8)
            prevWordCount >= 10 -> ". "
            isId && SENTENCE_FINAL_ID.contains(prevLastWord) && prevWordCount >= 5 -> ". "
            isId && SENTENCE_FINAL_ID.contains(prevLastWord) -> ", "

            // Default: just a space
            else -> " "
        }

        // ── Suffix: punctuation at end of CURRENT segment ────────────────
        val suffix = when {
            isQuestion     -> "?"
            isExclamation  -> "!"
            else           -> ""
        }

        // ── Capitalize if start of new sentence ──────────────────────────
        val capitalize = isFirst ||
            prefix == ". " ||
            prefix == "\n\n" ||
            prevLastChar?.let { it == '.' || it == '!' || it == '?' } == true

        return Result(prefix, suffix, capitalize && !isArabic)
    }

    private fun detectQuestion(
        lower: String, firstWord: String, lastWord: String, lang: String
    ): Boolean {
        if (lower.endsWith("?")) return true
        return when {
            lang.startsWith("id") ->
                QUESTION_START_ID.any { lower.startsWith(it) } ||
                QUESTION_TAIL_ID.any { lower.endsWith(it) } ||
                lower.endsWith("kah")
            lang.startsWith("en") ->
                QUESTION_START_EN.contains(firstWord)
            lang.startsWith("ar") ->
                lower.startsWith("هل") || lower.startsWith("ما") || lower.startsWith("من")
            else -> false
        }
    }

    fun capitalize(text: String, language: String): String {
        if (language.startsWith("ar")) return text
        return text.replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }
}
