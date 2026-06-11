package com.kajian.note.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

/**
 * DictionaryManager — Local vocabulary auto-correction.
 *
 * How it works:
 * 1. STT returns raw text (e.g. "mashallah quran hadis")
 * 2. DictionaryManager.correct() scans and replaces
 * 3. Output: "Masha Allah Al-Qur'an Hadits"
 *
 * Dictionary is stored as Map<String, String> in SharedPreferences.
 * Pre-seeded with common Islamic/Indonesian terms that STT gets wrong.
 */
class DictionaryManager(context: Context) {

    private val prefs = context.getSharedPreferences("kajian_dictionary", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_ENTRIES   = "dict_entries"
        private const val KEY_ENABLED   = "auto_correct_enabled"
        private const val KEY_SEEDED    = "is_seeded"

        // ── Pre-seeded entries ─────────────────────────────────────────────
        // Format: "wrong pattern (lowercase)" to "correct replacement"
        // Patterns are matched case-insensitively, whole words preferred
        val DEFAULT_ENTRIES = linkedMapOf(
            // ── Dzikir & Tasbih ──────────────────────────────────────────
            "mashallah"           to "Masha Allah",
            "masya allah"         to "Masha Allah",
            "masyaallah"          to "Masha Allah",
            "subhanallah"         to "Subhanallah",
            "subhanlah"           to "Subhanallah",
            "subhanalla"          to "Subhanallah",
            "alhamdulillah"       to "Alhamdulillah",
            "alhamdulila"         to "Alhamdulillah",
            "alhamdulilah"        to "Alhamdulillah",
            "allahu akbar"        to "Allahu Akbar",
            "allahuakbar"         to "Allahu Akbar",
            "astaghfirullah"      to "Astaghfirullah",
            "astagfirullah"       to "Astaghfirullah",
            "laa ilaha illallah"  to "Laa ilaaha illallah",
            "bismillah"           to "Bismillah",
            "bismila"             to "Bismillah",
            "inshallah"           to "Insya Allah",
            "insyaallah"          to "Insya Allah",
            "insya allah"         to "Insya Allah",
            "jazakallah"          to "Jazakallahu khairan",
            "jazakallahu khair"   to "Jazakallahu khairan",
            "barakallah"          to "Barakallahu fiik",
            "la hawla"            to "La hawla wala quwwata illa billah",

            // ── Salam ────────────────────────────────────────────────────
            "assalamualaikum"     to "Assalamu'alaikum",
            "assalamu alaikum"    to "Assalamu'alaikum",
            "waalaikumsalam"      to "Wa'alaikumsalam",
            "wa alaikumsalam"     to "Wa'alaikumsalam",
            "waalaikussalam"      to "Wa'alaikumsalam",

            // ── Nabi & Rasul ─────────────────────────────────────────────
            "rasulullah"          to "Rasulullah ﷺ",
            "nabi muhammad"       to "Nabi Muhammad ﷺ",
            "nabi saw"            to "Nabi ﷺ",
            "nabi shalallahu"     to "Nabi ﷺ",

            // ── Kitab & Ilmu ─────────────────────────────────────────────
            "quran"               to "Al-Qur'an",
            "al quran"            to "Al-Qur'an",
            "alquran"             to "Al-Qur'an",
            "hadis"               to "Hadits",
            "hadist"              to "Hadits",
            "sunnah"              to "Sunnah",
            "tafsir"              to "Tafsir",
            "fiqih"               to "Fiqih",
            "fiqh"                to "Fiqih",
            "aqidah"              to "Aqidah",
            "aqeedah"             to "Aqidah",
            "tauhid"              to "Tauhid",

            // ── Ibadah ────────────────────────────────────────────────────
            "salat"               to "sholat",
            "shalat"              to "sholat",
            "zakat"               to "zakat",
            "puasa ramadan"       to "puasa Ramadhan",
            "ramadan"             to "Ramadhan",
            "haji"                to "haji",
            "umroh"               to "umroh",
            "umrah"               to "umroh",
            "wudhu"               to "wudhu",
            "wudu"                to "wudhu",
            "tayamum"             to "tayamum",
            "qiblat"              to "kiblat",
            "kiblat"              to "kiblat",

            // ── Istilah Umum ──────────────────────────────────────────────
            "ustadz"              to "Ustadz",
            "ustad"               to "Ustadz",
            "ustadzah"            to "Ustadzah",
            "ulama"               to "ulama",
            "masjid"              to "masjid",
            "mushola"             to "mushola",
            "halal"               to "halal",
            "haram"               to "haram",
            "makruh"              to "makruh",
            "mubah"               to "mubah",
            "sunnah"              to "sunnah",
            "wajib"               to "wajib",
            "syariah"             to "syariah",
            "shariah"             to "syariah",
            "jihad"               to "jihad",
            "dakwah"              to "dakwah",
            "dawah"               to "dakwah",
            "taqwa"               to "taqwa",
            "ikhlas"              to "ikhlas",
            "sabar"               to "sabar",
            "syukur"              to "syukur",
            "tawakkal"            to "tawakkal",
            "doa"                 to "doa",
            "dzikir"              to "dzikir",
            "zikir"               to "dzikir",
            "istighfar"           to "istighfar",
            "takbir"              to "takbir",
            "tahmid"              to "tahmid",
            "tasbih"              to "tasbih",
            "sedekah"             to "sedekah",
            "shadaqah"            to "sedekah",
            "infak"               to "infak",
            "wakaf"               to "wakaf",
            "ridho"               to "ridha",
            "rahmat"              to "rahmat",
            "maghfirah"           to "maghfirah",
            "taubat"              to "taubat",
            "tobat"               to "taubat",
            "surga"               to "surga",
            "neraka"              to "neraka",
            "akhirat"             to "akhirat",
            "dunia"               to "dunia",
            "barzakh"             to "barzakh",
            "malaikat"            to "malaikat",
            "syaitan"             to "syaitan",
            "setan"               to "syaitan",
            "jin"                 to "jin",
            "mukmin"              to "mukmin",
            "muslim"              to "Muslim",
            "muslimah"            to "Muslimah",
            "kafir"               to "kafir",
            "munafik"             to "munafik",
        )
    }

    // ── Storage ────────────────────────────────────────────────────────────

    fun getEntries(): MutableMap<String, String> {
        if (!prefs.getBoolean(KEY_SEEDED, false)) seedDefaults()
        val json = prefs.getString(KEY_ENTRIES, "{}") ?: "{}"
        return try {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }
    }

    private fun saveEntries(entries: Map<String, String>) {
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
    }

    fun addEntry(wrong: String, correct: String) {
        val entries = getEntries()
        entries[wrong.lowercase(Locale.getDefault()).trim()] = correct.trim()
        saveEntries(entries)
    }

    fun removeEntry(wrong: String) {
        val entries = getEntries()
        entries.remove(wrong.lowercase(Locale.getDefault()).trim())
        saveEntries(entries)
    }

    fun clearAll() {
        saveEntries(emptyMap())
        prefs.edit().putBoolean(KEY_SEEDED, false).apply()
    }

    fun resetToDefaults() {
        saveEntries(DEFAULT_ENTRIES)
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun seedDefaults() {
        val existing = try {
            val json = prefs.getString(KEY_ENTRIES, "{}") ?: "{}"
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            gson.fromJson<MutableMap<String, String>>(json, type) ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }

        // Merge defaults (don't overwrite user's custom entries)
        val merged = DEFAULT_ENTRIES.toMutableMap()
        merged.putAll(existing)
        saveEntries(merged)
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    // ── Auto-correct toggle ────────────────────────────────────────────────

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun setEnabled(on: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, on).apply()

    // ── Core correction function ───────────────────────────────────────────

    /**
     * Apply dictionary corrections to raw STT text.
     *
     * Strategy: scan for each dictionary pattern (longest first to avoid
     * partial matches), replace with correct form.
     */
    fun correct(rawText: String): String {
        if (!isEnabled() || rawText.isBlank()) return rawText

        val entries = getEntries()
        if (entries.isEmpty()) return rawText

        var result = rawText

        // Sort by pattern length descending — match longer phrases first
        // e.g. "nabi muhammad" before "nabi"
        val sorted = entries.entries.sortedByDescending { it.key.length }

        for ((pattern, replacement) in sorted) {
            if (pattern.isBlank()) continue
            // Case-insensitive replacement, preserve surrounding chars
            val regex = Regex("(?i)\\b${Regex.escape(pattern)}\\b")
            result = regex.replace(result, replacement)
        }

        return result
    }

    fun getEntryCount() = getEntries().size
}
