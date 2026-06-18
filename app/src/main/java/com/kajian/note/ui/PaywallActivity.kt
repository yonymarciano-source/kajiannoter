package com.kajian.note.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kajian.note.databinding.ActivityPaywallBinding
import com.kajian.note.utils.UserManager
import kotlinx.coroutines.launch

/**
 * PaywallActivity — tampil saat user mencoba fitur premium.
 * Menampilkan 2 pilihan:
 *   1. One-time Purchase Rp 49.000 (unlimited notes + export)
 *   2. Langganan Rp 15.000/bulan (+ diarization + terjemah Arab)
 *
 * Payment flow (manual, sementara sebelum Xendit aktif):
 *   → User transfer ke salah satu rekening yang ditampilkan
 *   → Tap tombol → buka WhatsApp dengan template otomatis berisi
 *     NAMA & EMAIL LOGIN user (wajib sama, untuk verifikasi manual)
 *   → Admin cocokkan bukti transfer dengan nama/email → update Firestore tier
 *   → User tap "Sudah Bayar? Verifikasi" → app fetch ulang tier
 */
class PaywallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_NOTE_LIMIT = "note_limit"
        const val REASON_EXPORT = "export"
        const val REASON_DIARIZATION = "diarization"
        const val REASON_TRANSLATION = "translation"

        private const val WA_NUMBER = "6287872390906"
        private const val BANK_INFO = "Mandiri: 1570005373924 a.n Yony Marciano\n" +
                "BCA: 8010198454 a.n Yony Marciano\n" +
                "Gopay/OVO: 087872390906 a.n Yony Marciano"
    }

    private lateinit var binding: ActivityPaywallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_NOTE_LIMIT

        binding.tvPaywallTitle.text = when (reason) {
            REASON_NOTE_LIMIT -> "Batas 10 Catatan Tercapai 📋"
            REASON_EXPORT -> "Fitur Premium 🔒"
            REASON_DIARIZATION -> "Fitur Multi Speaker 👥"
            REASON_TRANSLATION -> "Fitur Translate 🌐"
            else -> "Upgrade KajianNote"
        }

        binding.tvPaywallSubtitle.text = when (reason) {
            REASON_NOTE_LIMIT -> "Akun FREE bisa menyimpan 10 catatan. Upgrade Premium untuk catatan tak terbatas + Summary, Translate, Export, dan Folder."
            REASON_DIARIZATION -> "Kenali siapa yang berbicara — identifikasi otomatis Ustadz & Jamaah dalam kajian. Fitur eksklusif Subscriber."
            REASON_TRANSLATION -> "Terjemahkan transkripsi kajian ke Bahasa Indonesia atau English otomatis via AI."
            else -> "Buka semua fitur KajianNote untuk pengalaman menuntut ilmu terbaik."
        }

        // One-time purchase
        binding.btnBuyOnetime.setOnClickListener {
            confirmAndOpenWa(packageName = "Premium", price = "Rp 49.000 (sekali bayar)")
        }

        // Subscription
        binding.btnSubscribe.setOnClickListener {
            confirmAndOpenWa(packageName = "Subscriber", price = "Rp 15.000/bulan")
        }

        // Salin info rekening
        binding.btnCopyBankInfo.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Info Rekening", BANK_INFO))
            Toast.makeText(this, "✅ Info rekening disalin", Toast.LENGTH_SHORT).show()
        }

        // Sudah bayar? Refresh tier
        binding.btnAlreadyPaid.setOnClickListener {
            lifecycleScope.launch {
                val tier = UserManager.getTier(forceRefresh = true)
                if (tier != UserManager.Tier.FREE) {
                    Toast.makeText(this@PaywallActivity,
                        "✅ Upgrade berhasil! Selamat menggunakan fitur premium.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@PaywallActivity,
                        "Belum terverifikasi. Pastikan sudah konfirmasi via WhatsApp dan tunggu admin proses (maks 1 hari kerja).",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    /**
     * Tampilkan konfirmasi singkat bahwa data harus sesuai email login,
     * lalu buka WhatsApp dengan template otomatis berisi nama & email user.
     */
    private fun confirmAndOpenWa(packageName: String, price: String) {
        if (!UserManager.isLoggedIn) {
            Toast.makeText(this, "Silakan login dulu sebelum upgrade, agar verifikasi bisa dilakukan.", Toast.LENGTH_LONG).show()
            return
        }

        val name  = UserManager.getUserName()
        val email = UserManager.getUserEmail()

        val message = "Halo Admin KajianNote,\n\n" +
                "Saya ingin upgrade ke *$packageName* ($price).\n\n" +
                "Nama: $name\n" +
                "Email akun KajianNote: $email\n\n" +
                "Bukti transfer akan saya lampirkan setelah ini. Mohon diverifikasi. Terima kasih."

        val url = "https://wa.me/$WA_NUMBER?text=${Uri.encode(message)}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka WhatsApp. Hubungi kami di +62 878-7239-0906.", Toast.LENGTH_LONG).show()
        }
    }
}
