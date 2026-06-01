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
 *
 * Strategi: rekam terus → tiap CHUNK_SECONDS kirim ke Groq → append hasil.
 * Jauh lebih akurat dari Android SpeechRecognizer untuk:
 * - Bicara cepat
 * - Istilah Arab/Islam
 * - Kalimat panjang tanpa jeda
 *
 * Setelah selesai, file audio lengkap tersimpan di audioFile.
 */
class ContinuousGroqRecorder(
    private val ctx: Context,
    private val language: String,
    private val onPartial: (String) -> Unit,    // dipanggil saat chunk selesai
    private val onAmplitude: (Float) -> Unit    // untuk waveform
) {
    companion object {
        private const val TAG           = "ContinuousGroqRecorder"
        private const val SAMPLE_RATE   = 16000
        private const val CHANNELS      = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING      = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SECONDS = 30       // kirim ke Groq tiap 30 detik
        private const val MIN_CHUNK_SEC = 2        // minimal 2 detik sebelum kirim
    }

    // File audio lengkap yang tersimpan
    var audioFile: File? = null
        private set

    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var chunkJob: Job? = null

    @Volatile private var isRecording = false
    @Volatile private var fullText = StringBuilder()

    // Buffer untuk chunk saat ini
    private val chunkBuffer = ByteArrayOutputStream()
    private val chunkLock = Object()

    @SuppressLint("MissingPermission")
    fun start(outputDir: File) {
        outputDir.mkdirs()
        val outFile = File(outputDir, "kajian_${System.currentTimeMillis()}.wav")
        audioFile = outFile
        fullText.clear()
        isRecording = true

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING) * 2, 8192
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNELS, ENCODING, bufSize
        ).also { it.startRecording() }

        // Job 1: rekam audio terus-menerus, simpan ke file dan buffer chunk
        recordJob = scope.launch {
            try {
                RandomAccessFile(outFile, "rw").use { raf ->
                    raf.write(ByteArray(44)) // WAV header placeholder
                    val shortBuf = ShortArray(bufSize / 2)
                    val byteBuf  = ByteBuffer.allocate(bufSize).order(ByteOrder.LITTLE_ENDIAN)
                    var totalBytes = 0L

                    while (isRecording) {
                        val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: 0
                        if (read <= 0) continue

                        // Update amplitude untuk waveform
                        val amp = shortBuf.take(read)
                            .map { Math.abs(it.toInt()) }.average().toFloat()
                        withContext(Dispatchers.Main) { onAmplitude(amp / 32768f * 28f) }

                        // Tulis ke file output (WAV lengkap)
                        byteBuf.clear()
                        for (i in 0 until read) byteBuf.putShort(shortBuf[i])
                        val rawBytes = byteBuf.array().copyOf(read * 2)
                        raf.write(rawBytes)
                        totalBytes += read * 2

                        // Tambah ke chunk buffer
                        synchronized(chunkLock) {
                            chunkBuffer.write(rawBytes)
                        }
                    }

                    // Tulis WAV header yang benar
                    raf.seek(0)
                    writeWavHeader(raf, totalBytes.toInt(), SAMPLE_RATE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Record error: ${e.message}", e)
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
        val chunkData: ByteArray
        synchronized(chunkLock) {
            if (chunkBuffer.size() < SAMPLE_RATE * MIN_CHUNK_SEC * 2) return
            chunkData = chunkBuffer.toByteArray()
            chunkBuffer.reset()
        }

        try {
            // Buat file WAV temporary untuk chunk ini
            val chunkFile = File(ctx.cacheDir, "chunk_${System.currentTimeMillis()}.wav")
            FileOutputStream(chunkFile).use { fos ->
                RandomAccessFile(chunkFile, "rw").use { raf ->
                    raf.write(ByteArray(44))
                    raf.write(chunkData)
                    raf.seek(0)
                    writeWavHeader(raf, chunkData.size, SAMPLE_RATE)
                }
            }

            Log.d(TAG, "Sending chunk: ${chunkData.size / 1024}KB")

            val result = GroqTranscriber.transcribe(
                ctx     = ctx,
                wavFile = chunkFile,
                lang    = language.take(2),
                onProgress = { _, _ -> }
            )

            chunkFile.delete()

            if (result.isNotBlank() && !result.startsWith("ERROR")) {
                val trimmed = result.trim()
                fullText.append(" ").append(trimmed)
                withContext(Dispatchers.Main) { onPartial(trimmed) }
                Log.d(TAG, "Chunk result: '$trimmed'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chunk send error: ${e.message}", e)
        }
    }

    /**
     * Stop recording. Mengirim sisa chunk terakhir.
     * onComplete dipanggil di Main thread dengan (fullText, audioFile).
     */
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
            val file   = audioFile

            withContext(Dispatchers.Main) {
                onComplete(result, file)
            }
        }
    }

    fun destroy() {
        isRecording = false
        scope.cancel()
        try { audioRecord?.release() } catch (_: Exception) {}
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataBytes: Int, sr: Int) {
        fun i(v: Int) = raf.write(byteArrayOf(v.toByte(),(v shr 8).toByte(),(v shr 16).toByte(),(v shr 24).toByte()))
        fun s(v: Int) = raf.write(byteArrayOf(v.toByte(),(v shr 8).toByte()))
        raf.write("RIFF".toByteArray()); i(36 + dataBytes); raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray()); i(16); s(1); s(1); i(sr); i(sr * 2); s(2); s(16)
        raf.write("data".toByteArray()); i(dataBytes)
    }
}
