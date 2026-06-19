package com.kajian.note.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kajian.note.databinding.ActivityPaywallBinding
import com.kajian.note.utils.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PaywallActivity — Midtrans Snap payment via WebView.
 *
 * Flow:
 * 1. User tap tombol beli → hit midtrans_charge.php → dapat snap_token
 * 2. Buka WebView Snap Midtrans dengan token tersebut
 * 3. User bayar di WebView → Midtrans kirim webhook → Firestore ter-update otomatis
 * 4. User kembali ke app → tap "Sudah Bayar" → app refresh tier dari Firestore
 */
class PaywallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_NOTE_LIMIT   = "note_limit"
        const val REASON_EXPORT       = "export"
        const val REASON_DIARIZATION  = "diarization"
        const val REASON_TRANSLATION  = "translation"

        // ← URL PHP di qlachannel.com
        private const val CHARGE_URL = "https://qlachannel.com/midtrans_charge.php"

        // Midtrans Snap JS — PRODUCTION
        private const val SNAP_JS_URL = "https://app.midtrans.com/snap/snap.js"
        // private const val SNAP_JS_URL = "https://app.sandbox.midtrans.com/snap/snap.js" // SANDBOX

        private const val CLIENT_KEY = "Mid-client-VfcBYUtdW6M0WBrk"
    }

    private lateinit var binding: ActivityPaywallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_NOTE_LIMIT

        binding.tvPaywallTitle.text = when (reason) {
            REASON_NOTE_LIMIT   -> "Batas 10 Catatan Tercapai 📋"
            REASON_EXPORT       -> "Fitur Premium 🔒"
            REASON_DIARIZATION  -> "Fitur Multi Speaker 👥"
            REASON_TRANSLATION  -> "Fitur Translate 🌐"
            else                -> "Upgrade KajianNote"
        }

        binding.tvPaywallSubtitle.text = when (reason) {
            REASON_NOTE_LIMIT  -> "Akun FREE bisa menyimpan 10 catatan. Upgrade Premium untuk catatan tak terbatas + Summary, Translate, Export, dan Folder."
            REASON_DIARIZATION -> "Kenali siapa yang berbicara — identifikasi otomatis Ustadz & Jamaah dalam kajian. Fitur eksklusif Subscriber."
            REASON_TRANSLATION -> "Terjemahkan transkripsi kajian ke Bahasa Indonesia atau English otomatis via AI."
            else               -> "Buka semua fitur KajianNote untuk pengalaman menuntut ilmu terbaik."
        }

        // Tombol beli Premium (Rp 49.000 one-time)
        binding.btnBuyOnetime.setOnClickListener {
            startPayment("PREMIUM")
        }

        // Tombol Subscriber (Rp 15.000/bulan)
        binding.btnSubscribe.setOnClickListener {
            startPayment("SUBSCRIBER")
        }

        // Sudah bayar? Refresh tier dari Firestore
        binding.btnAlreadyPaid.setOnClickListener {
            checkPaymentStatus()
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    // ── STEP 1: Minta snap_token ke backend ──────────────────────────────
    private fun startPayment(packageKey: String) {
        if (!UserManager.isLoggedIn) {
            Toast.makeText(this, "Silakan login dulu sebelum upgrade.", Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val token = fetchSnapToken(packageKey)
                if (token != null) {
                    openSnapWebView(token, packageKey)
                } else {
                    Toast.makeText(
                        this@PaywallActivity,
                        "Gagal memulai pembayaran. Coba lagi.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PaywallActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    // ── STEP 2: Hit midtrans_charge.php → dapat snap_token ──────────────
    private suspend fun fetchSnapToken(packageKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("uid",     UserManager.getUserId())
                put("email",   UserManager.getUserEmail())
                put("name",    UserManager.getUserName())
                put("package", packageKey)
            }.toString()

            val url = URL(CHARGE_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
            }

            conn.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }

            val json = JSONObject(response)
            json.optString("snap_token").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // ── STEP 3: Buka Snap Midtrans via WebView ───────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun openSnapWebView(snapToken: String, packageKey: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    // Deteksi finish/close dari Snap
                    if (url.contains("finish") || url.contains("error") || url.contains("unfinish")) {
                        dialog.dismiss()
                        checkPaymentStatus()
                        return true
                    }
                    return false
                }
            }
        }

        // HTML yang load Snap.js dan langsung pay
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <script src="$SNAP_JS_URL" data-client-key="$CLIENT_KEY"></script>
            </head>
            <body>
            <script>
                window.onload = function() {
                    snap.pay('$snapToken', {
                        onSuccess: function(result) {
                            window.location.href = 'https://qlachannel.com/finish?status=success';
                        },
                        onPending: function(result) {
                            window.location.href = 'https://qlachannel.com/finish?status=pending';
                        },
                        onError: function(result) {
                            window.location.href = 'https://qlachannel.com/finish?status=error';
                        },
                        onClose: function() {
                            window.location.href = 'https://qlachannel.com/finish?status=close';
                        }
                    });
                };
            </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://qlachannel.com", html, "text/html", "UTF-8", null)

        dialog.setContentView(webView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener { webView.destroy() }
        dialog.show()
    }

    // ── STEP 4: Cek tier setelah bayar ──────────────────────────────────
    private fun checkPaymentStatus() {
        setLoading(true)
        lifecycleScope.launch {
            val tier = UserManager.getTier(forceRefresh = true)
            setLoading(false)
            if (tier != UserManager.Tier.FREE) {
                Toast.makeText(
                    this@PaywallActivity,
                    "✅ Upgrade berhasil! Selamat menggunakan fitur ${tier.name.lowercase()}.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                Toast.makeText(
                    this@PaywallActivity,
                    "Pembayaran belum terverifikasi. Jika sudah bayar, tunggu beberapa saat lalu coba lagi.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnBuyOnetime.isEnabled  = !loading
        binding.btnSubscribe.isEnabled   = !loading
        binding.btnAlreadyPaid.isEnabled = !loading
        // Jika ada progress bar di layout, tampilkan di sini
        // binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
