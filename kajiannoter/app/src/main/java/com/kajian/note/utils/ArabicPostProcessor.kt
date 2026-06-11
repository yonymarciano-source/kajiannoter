package com.kajian.note.utils

/**
 * ArabicPostProcessor — post-processing setelah transkripsi selesai.
 *
 * Strategi AMAN:
 * - Tidak ubah engine rekam/transkripsi sama sekali
 * - Hanya scan teks hasil, replace kata Arab fonetik ke tulisan Arab asli
 * - Case-insensitive matching
 * - Hanya replace kata utuh (word boundary) — tidak partial match
 * - RTL marker ditambah otomatis di sekitar kata Arab
 */
object ArabicPostProcessor {

    // Kamus fonetik → Arab asli
    // Urutan penting: kata lebih panjang dulu supaya tidak di-match sebagian
    private val dictionary: List<Pair<Regex, String>> by lazy {
        buildDictionary()
    }

    fun process(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for ((regex, arabic) in dictionary) {
            result = regex.replace(result) { match ->
                // Tambah RTL marker agar teks Arab tampil benar di LTR context
                "\u200F$arabic\u200F"
            }
        }
        return result
    }

    private fun buildDictionary(): List<Pair<Regex, String>> {
        val entries = listOf(
            // Tahmid & Takbir
            "alhamdulillahi rabbil alamin" to "الحمد لله رب العالمين",
            "alhamdulillahi rabbil 'alamin" to "الحمد لله رب العالمين",
            "alhamdulillah rabbil alamin" to "الحمد لله رب العالمين",
            "alhamdulillahi" to "الحمد لله",
            "alhamdulillah" to "الحمد لله",
            "alhamdu lillah" to "الحمد لله",
            "al hamdulillah" to "الحمد لله",
            "subhanallahi wabihamdihi subhanallahil azhim" to "سبحان الله وبحمده سبحان الله العظيم",
            "subhanallahi wabihamdihi" to "سبحان الله وبحمده",
            "subhanallah" to "سبحان الله",
            "subhanallahi" to "سبحان الله",
            "allahu akbar" to "الله أكبر",
            "allahuakbar" to "الله أكبر",
            "allahu akhbar" to "الله أكبر",

            // Basmalah & Taawwudz
            "bismillahirrahmanirrahim" to "بسم الله الرحمن الرحيم",
            "bismillahir rahmanir rahim" to "بسم الله الرحمن الرحيم",
            "bismillah" to "بسم الله",
            "a'udzubillahi minasy syaithanirrajim" to "أعوذ بالله من الشيطان الرجيم",
            "audzubillahi minasy syaithanirrajim" to "أعوذ بالله من الشيطان الرجيم",

            // Salam
            "assalamu'alaikum warahmatullahi wabarakatuh" to "السلام عليكم ورحمة الله وبركاته",
            "assalamu alaikum warahmatullahi wabarakatuh" to "السلام عليكم ورحمة الله وبركاته",
            "assalamualaikum warahmatullahi wabarakatuh" to "السلام عليكم ورحمة الله وبركاته",
            "assalamu'alaikum warahmatullahi" to "السلام عليكم ورحمة الله",
            "assalamu alaikum warahmatullahi" to "السلام عليكم ورحمة الله",
            "assalamualaikum warahmatullahi" to "السلام عليكم ورحمة الله",
            "wa'alaikumussalam warahmatullahi wabarakatuh" to "وعليكم السلام ورحمة الله وبركاته",
            "wa alaikumussalam warahmatullahi wabarakatuh" to "وعليكم السلام ورحمة الله وبركاته",
            "assalamu'alaikum" to "السلام عليكم",
            "assalamualaikum" to "السلام عليكم",
            "assalamu alaikum" to "السلام عليكم",
            "wa'alaikumussalam" to "وعليكم السلام",
            "waalaikumussalam" to "وعليكم السلام",

            // Istighfar & Doa
            "astaghfirullahal azhim" to "أستغفر الله العظيم",
            "astaghfirullah" to "أستغفر الله",
            "astghfirullah" to "أستغفر الله",
            "la ilaha illallah muhammadur rasulullah" to "لا إله إلا الله محمد رسول الله",
            "la ilaha illallah" to "لا إله إلا الله",
            "laa ilaha illallah" to "لا إله إلا الله",
            "la ilaaha illallah" to "لا إله إلا الله",
            "muhammadar rasulullah" to "محمد رسول الله",
            "muhammadur rasulullah" to "محمد رسول الله",

            // Insya Allah & sejenisnya
            "insya allah" to "إن شاء الله",
            "insyaallah" to "إن شاء الله",
            "in syaa allah" to "إن شاء الله",
            "insya'allah" to "إن شاء الله",
            "masyaallah" to "ما شاء الله",
            "masya allah" to "ما شاء الله",
            "ma syaa allah" to "ما شاء الله",
            "barakallah" to "بارك الله",
            "barakallahu fiik" to "بارك الله فيك",
            "barakallahu fiikum" to "بارك الله فيكم",
            "jazakallah khairan" to "جزاك الله خيرًا",
            "jazakallahu khairan" to "جزاك الله خيرًا",
            "jazaakallahu khairan" to "جزاك الله خيرًا",

            // Shalawat
            "shallallahu alaihi wasallam" to "صلى الله عليه وسلم",
            "shallallahu 'alaihi wasallam" to "صلى الله عليه وسلم",
            "shalallahu alaihi wasallam" to "صلى الله عليه وسلم",
            "saw" to "ﷺ",

            // Nama Allah & Istilah
            "subhanahu wata'ala" to "سبحانه وتعالى",
            "subhanahu wa ta'ala" to "سبحانه وتعالى",
            "subhanahu wataala" to "سبحانه وتعالى",
            "rahimahullah" to "رحمه الله",
            "rahimahullahu ta'ala" to "رحمه الله تعالى",
            "hafizhahullah" to "حفظه الله",
            "radiyallahu anhu" to "رضي الله عنه",
            "radiyallahu anha" to "رضي الله عنها",
            "radiyallahu anhum" to "رضي الله عنهم",

            // Nama Surah umum
            "al fatihah" to "الفاتحة",
            "al baqarah" to "البقرة",
            "ali imran" to "آل عمران",
            "an nisa" to "النساء",
            "al ikhlas" to "الإخلاص",
            "al falaq" to "الفلق",
            "an nas" to "الناس",
            "al kahfi" to "الكهف",
            "yasin" to "يس",
            "yaasin" to "يس",

            // Istilah Islam umum
            "inna lillahi wa inna ilaihi raji'un" to "إنا لله وإنا إليه راجعون",
            "inna lillahi wa inna ilaihi rajiun" to "إنا لله وإنا إليه راجعون",
            "innalillahi wainnailaihi rajiun" to "إنا لله وإنا إليه راجعون",
            "allahumma shalli ala muhammad" to "اللهم صل على محمد",
            "allahumma shalli 'ala muhammad" to "اللهم صل على محمد",
            "rabbana atina fiddunya hasanah" to "ربنا آتنا في الدنيا حسنة",
        )

        return entries.map { (phonetic, arabic) ->
            // Case-insensitive, word boundary matching
            val escaped = Regex.escape(phonetic)
            val regex = Regex("(?i)\\b$escaped\\b")
            regex to arabic
        }
    }
}
