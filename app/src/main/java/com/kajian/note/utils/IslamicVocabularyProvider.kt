package com.kajian.note.utils

/**
 * IslamicVocabularyProvider — Single source of truth untuk word boosting.
 *
 * Dipakai oleh:
 * - AssemblyAITranscriber → wordBoostList (word_boost parameter)
 * - GroqTranscriber        → groqPromptHint() (prompt parameter)
 *
 * Kategori:
 * 1. Zikir & doa harian
 * 2. Istilah fiqih & aqidah
 * 3. Nama surah & kitab terkenal
 * 4. Nama ustadz populer Indonesia
 * 5. Istilah kajian & pesantren
 */
object IslamicVocabularyProvider {

    // ─────────────────────────────────────────────
    // KATEGORI 1 — Zikir & doa harian (fonetik)
    // ─────────────────────────────────────────────
    private val zikirDoa = listOf(
        "Alhamdulillah", "Subhanallah", "Allahu Akbar", "Bismillah",
        "Insya Allah", "Astaghfirullah", "Assalamu alaikum", "Wa alaikumsalam",
        "Barakallahu fiik", "Jazakallahu khairan", "Laa ilaaha illallah",
        "Muhammadur rasulullah", "Inna lillahi wa inna ilaihi raajiun",
        "La hawla wala quwwata illa billah", "Subhanallahi wabihamdihi",
        "Subhanallahi al-adzim", "Alhamdulillahi rabbil alamin",
        "Ar-rahmanir rahim", "Maalik yaumiddin", "Iyyaka nabudu wa iyyaka nastain",
        "Ihdinas shiratal mustaqim", "Amiin ya rabbal alamin",
        "Ya Allah", "Ya Rabb", "Rabbighfirli",
        "Rabbana atina fid dunya hasanah", "Allahumma sholli ala Muhammad",
        "Sholawat nabi", "Allahumma baarik"
    )

    // ─────────────────────────────────────────────
    // KATEGORI 2 — Istilah fiqih & aqidah
    // ─────────────────────────────────────────────
    private val fiqihAqidah = listOf(
        // Aqidah / manhaj
        "tauhid", "aqidah", "manhaj", "ahlus sunnah", "ahlussunnah wal jamaah",
        "salafi", "salafiyah", "bid'ah", "syirik", "kufur", "nifaq", "munafik",
        "tawakkul", "tawakkal", "qanaah", "zuhud", "wara", "taqwa", "ikhlas",
        "muhasabah", "istiqomah", "istiqamah", "tazkiyatun nafs", "tawbah",
        "taubat nasuha", "husnudzan", "husnu zhan",
        // Fiqih ibadah
        "sholat", "shalat", "wudhu", "tayamum", "thaharah",
        "zakat", "zakat fitrah", "zakat maal", "infaq", "sedekah", "shadaqah",
        "puasa", "saum", "shiyam", "ramadhan", "lailatul qadr",
        "haji", "umroh", "ihram", "thawaf", "sai", "wukuf",
        "qurban", "udhiyah", "aqiqah",
        // Ibadah sunnah
        "tahajud", "qiyamul lail", "dhuha", "rawatib", "sunnah muakkad",
        "witir", "tarawih", "tilawah", "tadarus", "hafal Quran", "tahfidz",
        "talaqqi", "murajaah",
        // Muamalah
        "halal", "haram", "makruh", "mubah", "wajib", "fardhu",
        "fardhu ain", "fardhu kifayah", "riba", "gharar",
        "akad", "nikah", "mahar", "walimah", "mahram",
        // Ilmu hadits
        "hadits", "hadis", "shahih", "hasan", "dhaif", "maudu",
        "sanad", "matan", "rawi", "perawi", "mutawatir", "ahad",
        "marfu", "mauquf", "ijma", "qiyas", "ijtihad", "fatwa",
        "tafsir", "asbabun nuzul", "asbabul wurud",
        "ulumul quran", "ulumul hadits", "ushul fiqih"
    )

    // ─────────────────────────────────────────────
    // KATEGORI 3 — Nama surah & kitab
    // ─────────────────────────────────────────────
    private val surahKitab = listOf(
        "Al-Fatihah", "Al-Baqarah", "Ali Imran", "An-Nisa", "Al-Maidah",
        "Al-Anam", "Al-Anfal", "At-Taubah", "Yunus", "Yusuf",
        "Al-Kahfi", "Maryam", "Thaha", "Al-Anbiya", "Yasin",
        "Az-Zumar", "Al-Hujurat", "Ar-Rahman", "Al-Waqiah", "Al-Mulk",
        "Al-Qalam", "An-Naba", "An-Naziat", "Al-Fajr", "At-Tin",
        "Al-Alaq", "Al-Qadr", "Al-Ashr", "Al-Kautsar", "Al-Ikhlas",
        "Al-Falaq", "An-Nas",
        // Kitab
        "Shahih Bukhari", "Shahih Muslim", "Sunan Abu Dawud",
        "Sunan Tirmidzi", "Sunan Nasai", "Sunan Ibnu Majah",
        "Musnad Ahmad", "Riyadhus Shalihin", "Bulughul Maram",
        "Umdatul Ahkam", "Arbaun Nawawi", "Al-Arbain An-Nawawiyyah",
        "Fathul Baari", "Zaadul Maad", "Al-Aqidah Al-Wasithiyyah",
        "Lum'atul I'tiqad", "Kitabut Tauhid", "Al-Usul As-Tsalatsah",
        "Tsalatsatul Ushul", "Al-Mabadi Al-Mufidah"
    )

    // ─────────────────────────────────────────────
    // KATEGORI 4 — Nama ustadz populer Indonesia
    // ─────────────────────────────────────────────
    private val namaUstadz = listOf(
        // Salafi/ahlussunnah
        "Khalid Basalamah", "Firanda Andirja", "Yazid Jawas",
        "Zainal Abidin", "Muhammad Nuzul Dzikri", "Ammi Nur Baits",
        "Erwandi Tarmizi", "Abdul Hakim Abdat", "Kholid Syamhudi",
        "Luqman Baabduh", "Aris Munandar", "Abdullah Taslim",
        "Abu Yahya Badrusalam", "Muslih Abdul Karim",
        "Ahmad Zainuddin", "Abdullah Roy", "Subhan Bawazier",
        // Populer umum
        "Hanan Attaki", "Felix Siauw",
        "Adi Hidayat", "Buya Yahya", "Syafiq Riza Basalamah",
        "Abdul Somad", "Ustadz Abdul Somad", "Dasad Latif",
        "Oemar Mita", "Hamzah Izzulhaq",
        // Ulama klasik yang sering disebut
        "Ibnu Taimiyah", "Ibnul Qayyim", "Ibnu Katsir", "An-Nawawi",
        "Al-Bukhari", "Imam Muslim", "Imam Syafii", "Imam Ahmad",
        "Imam Malik", "Imam Abu Hanifah", "Al-Albani", "Ibnu Baz",
        "Ibnu Utsaimin", "Shalih Al-Fauzan"
    )

    // ─────────────────────────────────────────────
    // KATEGORI 5 — Istilah kajian & pesantren
    // ─────────────────────────────────────────────
    private val istilahKajian = listOf(
        "kajian", "kajian Islam", "kajian ilmiah", "majelis ilmu",
        "majelis taklim", "pengajian", "tabligh akbar", "daurah",
        "halaqah", "usrah", "tarbiyah", "liqo",
        "ustadz", "ustadzah", "kiai", "kyai", "ulama", "syaikh", "imam",
        "pesantren", "ponpes", "santri", "ma'had",
        "mualaf", "muallaf", "hijrah",
        "dakwah", "tabligh", "amar makruf nahi mungkar",
        "masjid", "mushola", "mihrab", "mimbar",
        "khutbah", "khutbah Jumat", "ceramah", "tausiyah",
        "Al-Quran", "Quran", "mushaf", "tajwid", "makhraj",
        "idgham", "ikhfa", "izhar", "iqlab", "mad", "qalqalah",
        "sunnah nabi", "sirah nabawiyah", "sahabat", "tabi'in",
        "salafus shalih", "khulafaur rasyidin",
        "Abu Bakar", "Umar bin Khattab", "Utsman bin Affan", "Ali bin Abi Thalib",
        "Siti Aisyah", "Khadijah", "Fatimah",
        "Nabi Muhammad", "Rasulullah",
        "shallallahu alaihi wasallam", "shalallahu alaihi wasallam",
        "rahimahullah", "hafidzahullah",
        "rhadiyallahu anhu", "rhadiyallahu anha",
        "alaihissalam", "alaihis salam"
    )

    // ─────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────

    /**
     * Gabungan semua kategori — dipakai untuk AssemblyAI word_boost.
     * Max 1000 kata (saat ini ~300, aman).
     */
    val wordBoostList: List<String> by lazy {
        (zikirDoa + fiqihAqidah + surahKitab + namaUstadz + istilahKajian)
            .distinct()
            .take(1000)
    }

    /**
     * Prompt hint untuk Groq Whisper — per bahasa.
     */
    fun groqPromptHint(langCode: String?): String {
        val ustadzSample = namaUstadz.take(8).joinToString(", ")
        val surahSample  = surahKitab.take(8).joinToString(", ")
        val fiqihSample  = fiqihAqidah.take(18).joinToString(", ")
        val zikirSample  = zikirDoa.take(10).joinToString(", ")

        return when (langCode) {
            "id" -> """Rekaman kajian Islam berbahasa Indonesia. Istilah yang sering muncul:
Zikir & doa: $zikirSample.
Fiqih & aqidah: $fiqihSample.
Nama ustadz: $ustadzSample.
Surah & kitab: $surahSample.
Kata Arab ditulis dalam transliterasi Indonesia."""

            "ar" -> """بسم الله الرحمن الرحيم. تسجيل درس إسلامي. المصطلحات الشائعة:
الحمد لله، سبحان الله، الله أكبر، أستغفر الله، إن شاء الله، بسم الله.
القرآن الكريم، الحديث النبوي، السنة، الصلاة، الزكاة، الحج، العقيدة."""

            null -> """Islamic study recording — Indonesian with Arabic terms.
Phrases: $zikirSample. Terms: tauhid, aqidah, manhaj, sholat, zakat.
Scholars: $ustadzSample."""

            else -> "Islamic lecture. Terms: $zikirSample. tauhid, aqidah, sholat, zakat, puasa."
        }
    }
}
