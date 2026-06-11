package com.kajian.note.ui

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
 * Payment flow sementara:
 *   → Redirect ke WhatsApp/Midtrans link
 *   → Setelah konfirmasi manual, admin update Firestore
 *   (Midtrans webhook akan otomatis nanti)
 */
class PaywallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_NOTE_LIMIT = "note_limit"
        const val REASON_EXPORT = "export"
        const val REASON_DIARIZATION = "diarization"
        const val REASON_TRANSLATION = "translation"

        // Ganti dengan link Midtrans atau WhatsApp kamu
        const val LINK_ONE_TIME = "https://wa.me/628XXXXXXXXXX?text=Halo,%20saya%20mau%20beli%20KajianNote%20Premium%20Rp49.000"
        const val LINK_SUBSCRIBE = "https://wa.me/628XXXXXXXXXX?text=Halo,%20saya%20mau%20langganan%20KajianNote%20Rp15.000/bulan"
    }

    private lateinit var binding: ActivityPaywallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_NOTE_LIMIT

        // Sesuaikan headline berdasarkan reason
        binding.tvPaywallTitle.text = when (reason) {
            REASON_NOTE_LIMIT -> "Batas 10 Catatan Tercapai"
            REASON_EXPORT -> "Fitur Export Premium"
            REASON_DIARIZATION -> "Fitur Speaker Diarization"
            REASON_TRANSLATION -> "Fitur Terjemah Arab"
            else -> "Upgrade KajianNote"
        }

        binding.tvPaywallSubtitle.text = when (reason) {
            REASON_NOTE_LIMIT -> "Versi gratis hanya bisa menyimpan 10 catatan. Upgrade untuk catatan tak terbatas."
            REASON_DIARIZATION -> "Kenali siapa yang berbicara — label Ustadz & Jamaah otomatis."
            REASON_TRANSLATION -> "Terjemahan otomatis teks Arab dalam transkripsi."
            else -> "Buka semua fitur KajianNote untuk pengalaman menuntut ilmu terbaik."
        }

        // One-time purchase
        binding.btnBuyOnetime.setOnClickListener {
            openLink(LINK_ONE_TIME)
        }

        // Subscription
        binding.btnSubscribe.setOnClickListener {
            openLink(LINK_SUBSCRIBE)
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
                        "Belum terverifikasi. Hubungi kami jika sudah bayar.", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka link. Hubungi kami di WhatsApp.", Toast.LENGTH_LONG).show()
        }
    }
}
