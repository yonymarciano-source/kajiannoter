package com.kajian.note.utils

import android.content.Context
import android.speech.SpeechRecognizer
import java.util.Locale

object LanguageDetector {

    // Map device locale → best STT language code
    private val LOCALE_MAP = mapOf(
        "id" to "id-ID",
        "in" to "id-ID",   // older Android uses "in" for Indonesian
        "en" to "en-US",
        "ar" to "ar-SA",
        "ko" to "ko-KR",
        "ja" to "ja-JP",
        "it" to "it-IT",
        "es" to "es-ES",
        "fr" to "fr-FR",
        "de" to "de-DE",
        "pt" to "pt-BR",
        "zh" to "zh-CN",
        "ms" to "ms-MY",   // Malay → close to Indonesian
        "jv" to "id-ID",   // Javanese → fallback to Indonesian
        "su" to "id-ID",   // Sundanese → fallback
    )

    /**
     * Detect the best STT language based on device locale.
     * Returns a BCP-47 tag like "id-ID", "en-US", "ar-SA"
     */
    fun detectDeviceLanguage(context: Context): String {
        val locale = Locale.getDefault()
        val lang = locale.language.lowercase()
        val country = locale.country.uppercase()

        // Check system language list (includes secondary languages)
        val systemLocales = context.resources.configuration.locales
        for (i in 0 until systemLocales.size()) {
            val l = systemLocales.get(i).language.lowercase()
            if (LOCALE_MAP.containsKey(l)) return LOCALE_MAP[l]!!
        }

        // Try exact match (e.g., "en_ID" → "id-ID" for Indonesia-region English users)
        if (country == "ID") return "id-ID"

        // Fallback to primary locale map
        return LOCALE_MAP[lang] ?: "id-ID"
    }

    /**
     * Check if a given language is supported by the device's SpeechRecognizer
     */
    private fun isSupportedBySpeechRecognizer(context: Context, langCode: String): Boolean {
        return try {
            // We can't directly query supported languages in all API levels,
            // so we just check our known list
            LOCALE_MAP.values.contains(langCode) ||
            langCode.startsWith("id") || langCode.startsWith("en") ||
            langCode.startsWith("ar") || langCode.startsWith("ko") ||
            langCode.startsWith("ja") || langCode.startsWith("it") ||
            langCode.startsWith("es")
        } catch (e: Exception) { false }
    }

    fun getDisplayName(code: String): String = when (code) {
        "id-ID" -> "Bahasa Indonesia 🇮🇩"
        "en-US", "en-GB" -> "English 🇺🇸"
        "ar-SA" -> "العربية 🌙"
        "ko-KR" -> "한국어 🇰🇷"
        "ja-JP" -> "日本語 🇯🇵"
        "it-IT" -> "Italiano 🇮🇹"
        "es-ES" -> "Español 🇪🇸"
        else    -> "Auto Detect 🌐"
    }
}
