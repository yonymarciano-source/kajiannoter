package com.kajian.note.utils

/**
 * TranscriptSegmenter — memecah teks transkripsi menjadi paragraf
 * berdasarkan jumlah kalimat (default 3 kalimat per paragraf).
 *
 * Mendukung kalimat bahasa Indonesia, Inggris, dan Arab.
 * Pemisah kalimat: titik (.), tanda tanya (?), tanda seru (!),
 * serta tanda Arab (؟، .)
 */
object TranscriptSegmenter {

    private const val SENTENCES_PER_PARAGRAPH = 3

    /**
     * Split teks mentah (dari plainText atau gabungan entries) menjadi
     * list paragraf, masing-masing berisi SENTENCES_PER_PARAGRAPH kalimat.
     *
     * @param text  Teks transkripsi lengkap
     * @return      List<String> — tiap item = 1 paragraf siap tampil
     */
    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val sentences = splitIntoSentences(text.trim())
        if (sentences.isEmpty()) return listOf(text.trim())

        // Group per SENTENCES_PER_PARAGRAPH kalimat
        return sentences
            .chunked(SENTENCES_PER_PARAGRAPH)
            .map { group -> group.joinToString(" ").trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Split teks menjadi kalimat-kalimat individual.
     * Regex: potong setelah . ? ! ؟ yang diikuti spasi atau akhir string,
     * tapi tidak potong singkatan umum (dr., H., dll.)
     */
    private fun splitIntoSentences(text: String): List<String> {
        // Regex: sentence boundary = titik/tanya/seru diikuti spasi + huruf kapital,
        // atau akhir teks. Juga support tanda Arab.
        val sentenceRegex = Regex(
            """(?<=[.!?؟])\s+(?=[A-ZА-ЯA-zА-яa-zء-ي\d])"""
        )

        // Split dengan lookahead agar tanda baca tetap di kalimat sebelumnya
        val parts = sentenceRegex.split(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Jika split tidak efektif (teks tanpa tanda baca — transkripsi mentah),
        // fallback: potong per ~60 kata
        if (parts.size <= 1 && text.split(" ").size > 30) {
            return splitByWordCount(text, wordsPerSentence = 20)
        }

        return parts
    }

    /**
     * Fallback: split per N kata (untuk teks tanpa tanda baca)
     */
    private fun splitByWordCount(text: String, wordsPerSentence: Int): List<String> {
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return words.chunked(wordsPerSentence).map { it.joinToString(" ") }
    }
}
