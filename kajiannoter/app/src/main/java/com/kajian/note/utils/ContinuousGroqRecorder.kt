package com.kajian.note.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ContinuousGroqRecorder — Mode Langsung berbasis Groq Whisper.
 * Rekam terus → tiap CHUNK_SECONDS kirim ke Groq → append hasil.
 */
class ContinuousGroqRecorder(
    private val ctx: Context,
    private val language: String,
    private val onPartial: (String) -> Unit,
    private val onAmplitude: (Float) -> Unit
) {
    companion object {
        private const val TAG           = "ContinuousGroqRecorder"
        private const val SAMPLE_RATE   = 16000
        private const val CHANNELS      = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING      = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SECONDS = 25       // kirim tiap 25 detik
        private const val MIN_CHUNK_SEC = 2
        private const val OVERLAP_SEC   = 2        // overlap 2 detik antar chunk
    }

    var audioFile: File? = null
        private set

    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var chunkJob: Job? = null

    @Volatile private var isRecording = false
    @Volatile private var fullText = StringBuilder()

    // Raw PCM buffer untuk chunk (tanpa header)
    private val chunkPcm = ByteArrayOutputStream()
    // Simpan PCM terakhir OVERLAP_SEC untuk overlap
    private var lastOverlapPcm: ByteArray = ByteArray(0)
    private val chunkLock = Any()

    // Lang code yang valid untuk Groq
    private val langCode: String? get() = when {
        language.startsWith("id") -> "id"
        language.startsWith("en") -> "en"
        language.startsWith("ar") -> "ar"
        language.startsWith("ko") -> "ko"
        language.startsWith("ja") -> "ja"
        language.startsWith("it") -> "it"
        language.startsWith("es") -> "es"
        else -> null  // null = auto detect
    }

    @SuppressLint("MissingPermission")
    fun start(outputDir: File) {
        outputDir.mkdirs()
        val outFile = File(outputDir, "kajian_${System.currentTimeMillis()}.wav")
        audioFile = outFile
        fullText.clear()
        isRecording = true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufSize = maxOf(minBuf * 2, 8192)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNELS, ENCODING, bufSize
        ).also { it.startRecording() }

        // Job 1: rekam PCM terus, simpan ke file final DAN buffer chunk
        recordJob = scope.launch {
            val pcmStream = ByteArrayOutputStream() // akumulasi semua PCM untuk file final
            val shortBuf  = ShortArray(bufSize / 2)

            while (isRecording) {
                val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: 0
                if (read <= 0) continue

                // Amplitude untuk waveform
                var sum = 0.0
                for (i in 0 until read) sum += Math.abs(shortBuf[i].toInt())
                val amp = (sum / read / 32768.0 * 28.0).toFloat()
                withContext(Dispatchers.Main) { onAmplitude(amp) }

                // Convert short→byte (little endian)
                val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) bb.putShort(shortBuf[i])
                val rawBytes = bb.array()  // ukuran persis read*2

                // Simpan ke akumulasi file final
                pcmStream.write(rawBytes)

                // Tambah ke chunk buffer
                synchronized(chunkLock) {
                    chunkPcm.write(rawBytes)
                }
            }

            // Tulis file WAV final
            try {
                val allPcm = pcmStream.toByteArray()
                writePcmToWav(outFile, allPcm)
                Log.d(TAG, "Final WAV written: ${outFile.length() / 1024}KB")
            } catch (e: Exception) {
                Log.e(TAG, "Write final WAV error: ${e.message}")
            }
        }

        // Job 2: tiap CHUNK_SECONDS, ambil buffer dan kirim ke Groq
        chunkJob = scope.launch {
            while (isRecording) {
                delay(CHUNK_SECONDS * 1000L)
                if (!isRecording) break
                sendCurrentChunk()
            }
        }
    }

    private suspend fun sendCurrentChunk() {
        val pcmData: ByteArray
        val overlapData: ByteArray

        synchronized(chunkLock) {
            val size = chunkPcm.size()
            val minBytes = SAMPLE_RATE * MIN_CHUNK_SEC * 2
            if (size < minBytes) return

            pcmData = chunkPcm.toByteArray()

            // Simpan overlap (2 detik terakhir) untuk chunk berikutnya
            val overlapBytes = SAMPLE_RATE * OVERLAP_SEC * 2
            lastOverlapPcm = if (pcmData.size > overlapBytes)
                pcmData.copyOfRange(pcmData.size - overlapBytes, pcmData.size)
            else pcmData.copyOf()

            chunkPcm.reset()
            // Tulis overlap ke buffer baru supaya chunk berikutnya punya konteks
            if (lastOverlapPcm.isNotEmpty()) {
                chunkPcm.write(lastOverlapPcm)
            }
        }

        try {
            val chunkFile = File(ctx.cacheDir, "chunk_${System.currentTimeMillis()}.wav")
            writePcmToWav(chunkFile, pcmData)

            Log.d(TAG, "Sending chunk: ${chunkFile.length() / 1024}KB, lang=$langCode")

            val result = GroqTranscriber.transcribe(
                ctx        = ctx,
                wavFile    = chunkFile,
                lang       = langCode ?: "",
                onProgress = { _, _ -> }
            )

            chunkFile.delete()

            if (result.isNotBlank() && !result.startsWith("ERROR")) {
                val trimmed = result.trim()
                // Apply Arab post-processor
                val processed = ArabicPostProcessor.process(trimmed)
                // Hindari duplikasi dari overlap
                val deduped = deduplicateOverlap(fullText.toString(), processed)
                fullText.append(" ").append(deduped)
                withContext(Dispatchers.Main) { onPartial(deduped) }
                Log.d(TAG, "Chunk result: '$deduped'")
            } else {
                Log.w(TAG, "Chunk empty or error: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chunk send error: ${e.message}", e)
        }
    }

    /**
     * Deduplikasi teks overlap — cari suffix dari fullText yang jadi prefix di newText
     */
    private fun deduplicateOverlap(existing: String, newText: String): String {
        if (existing.isBlank() || newText.isBlank()) return newText
        val existingWords = existing.trim().split("\\s+".toRegex())
        val newWords      = newText.trim().split("\\s+".toRegex())
        if (existingWords.isEmpty() || newWords.isEmpty()) return newText

        // Cek apakah 3-5 kata terakhir existing ada di awal newText
        for (overlap in minOf(5, existingWords.size, newWords.size) downTo 2) {
            val existingSuffix = existingWords.takeLast(overlap).joinToString(" ")
            val newPrefix      = newWords.take(overlap).joinToString(" ")
            if (existingSuffix.lowercase() == newPrefix.lowercase()) {
                return newWords.drop(overlap).joinToString(" ")
            }
        }
        return newText
    }

    fun stop(onComplete: (String, File?) -> Unit) {
        isRecording = false
        chunkJob?.cancel()

        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        scope.launch {
            recordJob?.join()
            // Kirim sisa chunk terakhir
            sendCurrentChunk()

            val result = fullText.toString().trim()
            withContext(Dispatchers.Main) { onComplete(result, audioFile) }
        }
    }

    fun destroy() {
        isRecording = false
        scope.cancel()
        try { audioRecord?.release() } catch (_: Exception) {}
    }

    /**
     * Tulis raw PCM bytes ke file WAV yang valid.
     * Ini satu-satunya cara WAV ditulis — tidak ada RandomAccessFile berganda.
     */
    private fun writePcmToWav(file: File, pcm: ByteArray) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            val dataLen = pcm.size
            val totalLen = dataLen + 36

            // RIFF header
            out.writeBytes("RIFF")
            out.write(intToLe(totalLen))
            out.writeBytes("WAVE")

            // fmt chunk
            out.writeBytes("fmt ")
            out.write(intToLe(16))          // chunk size
            out.write(shortToLe(1))         // PCM format
            out.write(shortToLe(1))         // mono
            out.write(intToLe(SAMPLE_RATE)) // sample rate
            out.write(intToLe(SAMPLE_RATE * 2)) // byte rate
            out.write(shortToLe(2))         // block align
            out.write(shortToLe(16))        // bits per sample

            // data chunk
            out.writeBytes("data")
            out.write(intToLe(dataLen))
            out.write(pcm)
        }
    }

    private fun intToLe(v: Int) = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
    )
    private fun shortToLe(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
}
