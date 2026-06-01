package com.kajian.note.model

/**
 * A single entry in the transcript.
 * type = "speech" | "timestamp" | "speaker_change"
 */
data class TranscriptEntry(
    val type: String = TYPE_SPEECH,
    val speakerIndex: Int = 0,          // 0=A, 1=B, 2=C, etc.
    val speakerName: String = "Speaker A", // Display name
    val timeMs: Long = 0,               // Elapsed ms from recording start
    val text: String = "",              // Transcribed text
    val confidence: Float = 0f,
    val avgRms: Float = 0f,             // Average mic amplitude (for post-analysis)
    val pauseBefore: Long = 0L          // Gap (ms) before this utterance
) {
    companion object {
        const val TYPE_SPEECH = "speech"
        const val TYPE_TIMESTAMP = "timestamp"
        const val TYPE_SPEAKER_CHANGE = "speaker_change"

        val SPEAKER_LABELS = listOf("A", "B", "C", "D", "E", "F")
        val SPEAKER_COLORS = listOf(
            0xFF1E88E5.toInt(),  // Blue
            0xFF43A047.toInt(),  // Green
            0xFFE53935.toInt(),  // Red
            0xFFFB8C00.toInt(),  // Orange
            0xFF8E24AA.toInt(),  // Purple
            0xFF00ACC1.toInt()   // Cyan
        )

        fun defaultName(index: Int) = "Speaker ${SPEAKER_LABELS.getOrElse(index) { (index+1).toString() }}"
    }

    fun getFormattedTime(): String {
        val totalSeconds = timeMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
               else String.format("%02d:%02d", m, s)
    }

    fun getSpeakerColor(): Int = SPEAKER_COLORS.getOrElse(speakerIndex) { SPEAKER_COLORS[0] }
}
