package com.kajian.note.utils

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecordManager(private val onAmplitude: (Float) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS    = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioRecordManager"
    }

    data class SpeakerEvent(val timeMs: Long, val speakerIndex: Int, val speakerName: String)

    @Volatile var isRecording = false
    val speakerEvents = mutableListOf<SpeakerEvent>()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var startTimeMs = 0L
    private var outputFile: File? = null

    @SuppressLint("MissingPermission")
    fun start(cacheDir: File): File {
        val file = File(cacheDir, "kajian_${System.currentTimeMillis()}.wav")
        outputFile = file
        startTimeMs = System.currentTimeMillis()
        speakerEvents.clear()

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING) * 2, 8192
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNELS, ENCODING, bufferSize
        )
        isRecording = true
        audioRecord?.startRecording()

        recordingJob = scope.launch {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.write(ByteArray(44)) // WAV header placeholder
                    val shortBuf = ShortArray(bufferSize / 2)
                    val byteBuf  = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
                    var totalBytes = 0L
                    while (isRecording) {
                        val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: 0
                        if (read <= 0) continue
                        val amp = shortBuf.take(read).map { Math.abs(it.toInt()) }.average().toFloat()
                        withContext(Dispatchers.Main) { onAmplitude(amp / 32768f * 28f) }
                        byteBuf.clear()
                        for (i in 0 until read) byteBuf.putShort(shortBuf[i])
                        raf.write(byteBuf.array(), 0, read * 2)
                        totalBytes += read * 2
                    }
                    raf.seek(0)
                    writeWavHeader(raf, totalBytes.toInt(), SAMPLE_RATE)
                }
            } catch (e: Exception) { Log.e(TAG, "Recording error: ${e.message}") }
        }
        return file
    }

    /**
     * Stop recording asynchronously — NEVER blocks UI thread.
     * onComplete called on Main thread with WAV file (or null if failed).
     */
    fun stop(onComplete: (File?) -> Unit) {
        isRecording = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        // Wait for file write in background, then callback on Main
        scope.launch {
            recordingJob?.join()
            val result = if (outputFile?.exists() == true && (outputFile?.length() ?: 0) > 44)
                outputFile else null
            withContext(Dispatchers.Main) { onComplete(result) }
        }
    }

    fun recordSpeakerChange(speakerIndex: Int, speakerName: String) {
        val ms = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        speakerEvents.add(SpeakerEvent(ms, speakerIndex, speakerName))
    }

    fun elapsedMs() = if (startTimeMs > 0 && isRecording)
        System.currentTimeMillis() - startTimeMs else 0L

    private fun writeWavHeader(raf: RandomAccessFile, dataBytes: Int, sr: Int) {
        fun i(v: Int) = raf.write(byteArrayOf((v).toByte(),(v shr 8).toByte(),(v shr 16).toByte(),(v shr 24).toByte()))
        fun s(v: Int) = raf.write(byteArrayOf((v).toByte(),(v shr 8).toByte()))
        raf.write("RIFF".toByteArray()); i(36 + dataBytes); raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray()); i(16); s(1); s(1); i(sr); i(sr * 2); s(2); s(16)
        raf.write("data".toByteArray()); i(dataBytes)
    }

    fun destroy() { scope.cancel(); try { audioRecord?.release() } catch (_: Exception) {} }
}
