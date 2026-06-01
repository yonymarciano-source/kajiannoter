package com.kajian.note.utils

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * WhisperTranscriber — offline STT via sherpa-onnx (Whisper tiny multilingual).
 *
 * Library : com.github.k2-fsa:sherpa-onnx:1.12.40  (JitPack → GitHub releases)
 * Model   : sherpa-onnx-whisper-base (~74MB tar.bz2)
 *           https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
 *
 * Fix v3.2:
 *   1. trimSilence() — potong silence di ujung audio agar Whisper tidak loop
 *   2. deduplicateLoops() — post-process hapus hallucination loop
 *   3. tailPaddings dihapus (tidak ada di semua versi sherpa-onnx)
 */
object WhisperTranscriber {

    private const val TAG        = "WhisperTranscriber"
    private const val MODEL_NAME = "sherpa-onnx-whisper-base"
    private const val MODEL_URL  =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"

    private fun whisperDir(ctx: Context) = File(ctx.filesDir, "whisper").also { it.mkdirs() }
    private fun modelDir(ctx: Context)   = File(whisperDir(ctx), MODEL_NAME)

    fun isLibraryReady(ctx: Context) = true

    fun isModelReady(ctx: Context, lang: String): Boolean {
        val dir = modelDir(ctx)
        return dir.exists()
            && File(dir, "base-encoder.int8.onnx").exists()
            && File(dir, "base-decoder.int8.onnx").exists()
            && File(dir, "base-tokens.txt").exists()
    }

    // ── Download model dari GitHub releases ─────────────────────────────
    suspend fun downloadModel(
        ctx: Context,
        lang: String,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val tarFile = File(whisperDir(ctx), "$MODEL_NAME.tar.bz2")
        onProgress(2, "Menghubungi GitHub releases...")

        if (!downloadFile(MODEL_URL, tarFile) { p, m -> onProgress(p, m) }) {
            tarFile.delete()
            return@withContext false
        }

        onProgress(78, "Mengekstrak model Whisper...")
        val ok = extractTarBz2(tarFile, whisperDir(ctx))
        tarFile.delete()
        if (ok) onProgress(100, "✅ Model Whisper siap!")
        else onProgress(0, "Gagal ekstrak model")
        ok
    }

    // ── Transcribe WAV via sherpa-onnx OfflineRecognizer ─────────────────
    suspend fun transcribe(
        ctx: Context,
        wavFile: File,
        lang: String,
        onProgress: (Int, String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            onProgress(10, "Memuat audio...")
            val rawSamples = loadWavSamples(wavFile)
            if (rawSamples.isEmpty()) {
                Log.e(TAG, "Empty audio from ${wavFile.absolutePath}")
                return@withContext ""
            }

            // FIX 1: Trim silence di ujung audio — cegah hallucination loop
            onProgress(18, "Memproses audio...")
            val samples = trimSilence(rawSamples)
            Log.d(TAG, "Audio: ${rawSamples.size} → ${samples.size} samples after trim")

            onProgress(25, "Inisialisasi Whisper...")
            val dir = modelDir(ctx)

            val langCode = when {
                lang.startsWith("id") -> "id"
                lang.startsWith("en") -> "en"
                lang.startsWith("ar") -> "ar"
                lang.startsWith("ko") -> "ko"
                lang.startsWith("ja") -> "ja"
                lang.startsWith("it") -> "it"
                lang.startsWith("es") -> "es"
                else                  -> "id"
            }

            // FIX 2: Hapus tailPaddings (tidak kompatibel semua versi sherpa-onnx)
            val whisperConfig = OfflineWhisperModelConfig(
                encoder  = File(dir, "base-encoder.int8.onnx").absolutePath,
                decoder  = File(dir, "base-decoder.int8.onnx").absolutePath,
                language = langCode,
                task     = "transcribe",
            )

            val modelConfig = OfflineModelConfig(
                whisper    = whisperConfig,
                tokens     = File(dir, "base-tokens.txt").absolutePath,
                numThreads = 2,
                debug      = false,
                modelType  = "whisper",
            )

            val recognizerConfig = OfflineRecognizerConfig(
                modelConfig    = modelConfig,
                decodingMethod = "greedy_search",
            )

            onProgress(40, "Memulai transkripsi Whisper...")
            val recognizer = OfflineRecognizer(config = recognizerConfig)
            val stream     = recognizer.createStream()

            onProgress(50, "Memproses audio...")
            stream.acceptWaveform(samples, sampleRate = 16000)

            onProgress(70, "Decoding teks...")
            recognizer.decode(stream)

            onProgress(90, "Menyusun hasil...")
            val result = recognizer.getResult(stream)
            val rawText = result.text

            stream.release()
            recognizer.release()

            // FIX 3: Post-process hapus hallucination loop
            val text = deduplicateLoops(rawText.trim())

            Log.d(TAG, "Raw: '$rawText'")
            Log.d(TAG, "Clean: '$text'")
            onProgress(100, "✅ Selesai!")
            text

        } catch (e: Exception) {
            Log.e(TAG, "transcribe error: ${e.message}", e)
            ""
        }
    }

    // ── FIX 1: Trim silence dari ujung audio ─────────────────────────────
    // Potong trailing silence agar Whisper tidak mengalami hallucination loop.
    // Threshold 0.01f = ~-40dB, cukup untuk membedakan suara vs noise lantai.
    private fun trimSilence(
        samples: FloatArray,
        silenceThreshold: Float = 0.01f,
        minSilenceSamples: Int  = 16000  // 1 detik @ 16kHz
    ): FloatArray {
        if (samples.size < minSilenceSamples) return samples

        // Cari posisi sample terakhir yang di atas threshold
        var lastVoiceIdx = samples.size - 1
        while (lastVoiceIdx > 0 && Math.abs(samples[lastVoiceIdx]) < silenceThreshold) {
            lastVoiceIdx--
        }

        // Tambah 0.5 detik padding setelah suara terakhir
        val keepUntil = minOf(lastVoiceIdx + 8000, samples.size)
        return if (keepUntil < samples.size - minSilenceSamples)
            samples.copyOfRange(0, keepUntil)
        else
            samples  // tidak ada silence signifikan, kembalikan utuh
    }

    // ── FIX 3: Hapus hallucination loop dari output Whisper ──────────────
    // Whisper tiny kadang loop mengulang token/kata saat audio habis.
    // Contoh: "Al-Ali-Ali-Ali-Ali..." → dipotong setelah pengulangan pertama.
    private fun deduplicateLoops(text: String): String {
        if (text.length < 20) return text

        // Deteksi pola berulang: coba berbagai panjang pola (3–30 karakter)
        for (patternLen in 3..minOf(30, text.length / 3)) {
            val pattern = text.substring(text.length - patternLen)
            // Hitung berapa kali pattern ini muncul di 120 char terakhir
            val tail = text.takeLast(120)
            var count = 0
            var idx   = 0
            while (idx <= tail.length - patternLen) {
                if (tail.substring(idx, idx + patternLen) == pattern) { count++; idx += patternLen }
                else idx++
            }
            if (count >= 4) {
                // Potong sebelum loop dimulai — ambil teks sebelum pengulangan ke-2
                val firstRepeat = text.indexOf(pattern.repeat(2))
                if (firstRepeat > 10) {
                    Log.d(TAG, "Loop detected (pattern='$pattern' x$count), trimming at $firstRepeat")
                    return text.substring(0, firstRepeat).trimEnd(' ', '-', ',')
                }
            }
        }

        // Fallback: deteksi kata berulang (untuk loop kata seperti "Ali Ali Ali")
        val words = text.split(" ")
        if (words.size >= 6) {
            for (i in words.indices) {
                val word = words[i]
                if (word.length < 2) continue
                val repeatStart = words.drop(i).takeWhile { it == word }
                if (repeatStart.size >= 4) {
                    Log.d(TAG, "Word loop detected: '$word' x${repeatStart.size}")
                    return words.take(i + 1).joinToString(" ").trimEnd()
                }
            }
        }

        return text
    }

    // ── Load WAV PCM 16-bit → FloatArray ─────────────────────────────────
    private fun loadWavSamples(wavFile: File): FloatArray {
        return try {
            FileInputStream(wavFile).use { fis ->
                fis.skip(44) // skip 44-byte WAV header
                val bytes = fis.readBytes()
                FloatArray(bytes.size / 2) { i ->
                    val lo = bytes[i * 2].toInt() and 0xFF
                    val hi = bytes[i * 2 + 1].toInt()
                    ((hi shl 8) or lo).toShort() / 32768f
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadWavSamples: ${e.message}")
            FloatArray(0)
        }
    }

    // ── Download dengan HTTP Range resume ────────────────────────────────
    private fun downloadFile(
        url: String,
        dest: File,
        onProgress: (Int, String) -> Unit
    ): Boolean {
        repeat(3) { attempt ->
            val existing = if (dest.exists()) dest.length() else 0L
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout    = 180_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "KajianNote/3.0")
                if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
                conn.connect()

                val code   = conn.responseCode
                if (code != 200 && code != 206) {
                    Log.w(TAG, "HTTP $code attempt ${attempt + 1}")
                    Thread.sleep(3000); return@repeat
                }

                val contentLen = conn.contentLength.toLong()
                val total      = if (code == 206) existing + contentLen else contentLen
                val append     = code == 206 && existing > 0

                conn.inputStream.use { inp ->
                    FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(65536)
                        var down = if (append) existing else 0L
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n); down += n
                            if (total > 0) {
                                val pct = (down * 75 / total).toInt().coerceIn(0, 75)
                                onProgress(pct, "⬇ ${down/1024/1024}MB / ${total/1024/1024}MB")
                            }
                        }
                    }
                }
                if (dest.length() > 10_000) return true

            } catch (e: Exception) {
                Log.w(TAG, "Download attempt ${attempt + 1}: ${e.message}")
                Thread.sleep(3000)
            }
        }
        return false
    }

    // ── Extract .tar.bz2 via system tar ──────────────────────────────────
    private fun extractTarBz2(tarFile: File, targetDir: File): Boolean {
        return try {
            val proc = ProcessBuilder(
                "/system/bin/tar", "-xjf",
                tarFile.absolutePath, "-C", targetDir.absolutePath
            ).redirectErrorStream(true).start()
            val out      = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            Log.d(TAG, "tar exit=$exitCode: $out")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "extractTarBz2: ${e.message}")
            false
        }
    }
}
