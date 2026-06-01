package com.kajian.note.utils

import android.content.Context

class PreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("kajian_prefs_v3", Context.MODE_PRIVATE)

    fun getAppLanguage(): String = prefs.getString("app_lang", "en") ?: "en"
    fun setAppLanguage(lang: String) = prefs.edit().putString("app_lang", lang).apply()

    fun getRecordingLanguage(): String = prefs.getString("rec_lang", "auto") ?: "auto"
    fun setRecordingLanguage(lang: String) = prefs.edit().putString("rec_lang", lang).apply()

    fun getRecordMode(): String = prefs.getString("record_mode", "LANGSUNG") ?: "LANGSUNG"
    fun setRecordMode(mode: String) = prefs.edit().putString("record_mode", mode).apply()

    fun getAudioMode(): String = prefs.getString("audio_mode", "NORMAL") ?: "NORMAL"
    fun setAudioMode(mode: String) = prefs.edit().putString("audio_mode", mode).apply()

    fun isFirstLaunch(): Boolean = prefs.getBoolean("first_launch", true)
    fun setFirstLaunchDone() = prefs.edit().putBoolean("first_launch", false).apply()

    fun getMicSensitivity(): Int = prefs.getInt("mic_sensitivity", 3)
    fun setMicSensitivity(level: Int) = prefs.edit().putInt("mic_sensitivity", level.coerceIn(1, 5)).apply()

    fun getSpeakerChangeSensitivity(): Long = prefs.getLong("speaker_sens", 3000L)
    fun setSpeakerChangeSensitivity(ms: Long) = prefs.edit().putLong("speaker_sens", ms).apply()

    fun getTranscribeEngine(): String = prefs.getString("transcribe_engine", "GROQ") ?: "GROQ"
    fun setTranscribeEngine(engine: String) = prefs.edit().putString("transcribe_engine", engine).apply()

    companion object {
        val APP_LANGUAGES = listOf(
            Triple("en", "English", "English"),
            Triple("id", "Indonesia", "Bahasa Indonesia"),
            Triple("ar", "العربية", "Arabic"),
            Triple("ko", "한국어", "Korean"),
            Triple("ja", "日本語", "Japanese"),
            Triple("it", "Italiano", "Italian"),
            Triple("es", "Español", "Spanish")
        )

        val RECORDING_LANGUAGES = listOf(
            Pair("auto", "🌐 Auto Detect"),
            Pair("id-ID", "🇮🇩 Indonesia"),
            Pair("en-US", "🇺🇸 English"),
            Pair("ar-SA", "🌙 العربية"),
            Pair("ko-KR", "🇰🇷 한국어"),
            Pair("ja-JP", "🇯🇵 日本語"),
            Pair("it-IT", "🇮🇹 Italiano"),
            Pair("es-ES", "🇪🇸 Español")
        )
    }
}
