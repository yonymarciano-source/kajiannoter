package com.kajian.note.utils

import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WavChunker — potong file WAV besar menjadi chunk kecil.
 *
 * Strategi: baca header WAV → hitung bytes per detik →
 * potong di batas sample yang bersih → tulis setiap chunk
 * sebagai WAV valid lengkap dengan header baru.
 *
 * Max chunk = MAX_CHUNK_SEC detik.
 * Overlap = OVERLAP_SEC detik antar chunk supaya kalimat tidak terpotong.
 */
object WavChunker {

    private const val TAG           = "WavChunker"
    const val MAX_CHUNK_SEC         = 180   // 3 menit per chunk — jauh di bawah batas Groq 25MB
    private const val OVERLAP_SEC   = 2     // 2 detik overlap antar chunk
    private const val WAV_HEADER    = 44    // standard WAV header size

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int
    ) {
        val bytesPerSecond get() = sampleRate * channels * (bitsPerSample / 8)
        val durationSec get() = dataSize.toDouble() / bytesPerSecond
    }

    /**
     * Parse WAV header.
     * Return null jika bukan WAV valid.
     */
    fun parseHeader(file: File): WavInfo? {
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { din ->
                val riff = ByteArray(4).also { din.readFully(it) }
                if (String(riff) != "RIFF") return null
                din.readInt() // file size (little endian — ignored)
                val wave = ByteArray(4).also { din.readFully(it) }
                if (String(wave) != "WAVE") return null

                // Scan chunks untuk fmt dan data
                var sampleRate = 0; var channels = 0; var bits = 0
                var dataOffset = 0; var dataSize = 0
                var pos = 12

                while (pos < file.length() - 8) {
                    val id = ByteArray(4).also { din.readFully(it) }
                    val size = readIntLE(din)
                    pos += 8
                    when (String(id)) {
                        "fmt " -> {
                            din.readShort() // audio format
                            channels   = readShortLE(din).toInt()
                            sampleRate = readIntLE(din)
                            din.readInt()  // byte rate
                            din.readShort() // block align
                            bits       = readShortLE(din).toInt()
                            if (size > 16) din.skipBytes(size - 16)
                            pos += size
                        }
                        "data" -> {
                            dataOffset = pos
                            dataSize   = size
                            break
                        }
                        else -> { din.skipBytes(size); pos += size }
                    }
                }
                if (sampleRate == 0 || dataOffset == 0) null
                else WavInfo(sampleRate, channels, bits, dataOffset, dataSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseHeader error: ${e.message}")
            null
        }
    }

    /**
     * Pecah file WAV menjadi chunk-chunk.
     * Return list file chunk (disimpan di outputDir).
     * Kalau file pendek (< MAX_CHUNK_SEC), return listOf(file) langsung.
     */
    fun split(file: File, outputDir: File): List<File> {
        val info = parseHeader(file) ?: run {
            Log.w(TAG, "Cannot parse WAV header, returning original file")
            return listOf(file)
        }

        if (info.durationSec <= MAX_CHUNK_SEC) {
            Log.d(TAG, "File ${info.durationSec.toInt()}s — no split needed")
            return listOf(file)
        }

        Log.d(TAG, "Splitting ${info.durationSec.toInt()}s WAV into ${MAX_CHUNK_SEC}s chunks")

        val chunks = mutableListOf<File>()
        val chunkBytes  = info.bytesPerSecond * MAX_CHUNK_SEC
        val overlapBytes = info.bytesPerSecond * OVERLAP_SEC

        // Align to sample boundary
        val blockAlign = info.channels * (info.bitsPerSample / 8)
        val alignedChunk   = (chunkBytes   / blockAlign) * blockAlign
        val alignedOverlap = (overlapBytes / blockAlign) * blockAlign

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(info.dataOffset.toLong())
                var remaining = info.dataSize
                var chunkIdx  = 0

                while (remaining > 0) {
                    val toRead = minOf(alignedChunk, remaining)
                    val buf    = ByteArray(toRead)
                    val read   = raf.read(buf, 0, toRead)
                    if (read <= 0) break

                    val chunkFile = File(outputDir, "chunk_${chunkIdx}_${System.currentTimeMillis()}.wav")
                    writePcmToWav(chunkFile, buf.copyOf(read), info)
                    chunks.add(chunkFile)
                    Log.d(TAG, "Chunk $chunkIdx: ${read / info.bytesPerSecond}s → ${chunkFile.name}")

                    remaining -= read
                    chunkIdx++

                    // Rewind overlap untuk chunk berikutnya
                    if (remaining > 0 && alignedOverlap > 0) {
                        val rewind = minOf(alignedOverlap.toLong(), (raf.filePointer - info.dataOffset))
                        raf.seek(raf.filePointer - rewind)
                        remaining = (info.dataSize - (raf.filePointer - info.dataOffset)).toInt()
                            .coerceAtLeast(0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "split error: ${e.message}", e)
            // Gagal split — kembalikan file asli
            chunks.forEach { it.delete() }
            return listOf(file)
        }

        return chunks
    }

    private fun writePcmToWav(file: File, pcm: ByteArray, info: WavInfo) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            val dataLen = pcm.size
            out.writeBytes("RIFF")
            out.write(intToLE(dataLen + 36))
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.write(intToLE(16))
            out.write(shortToLE(1))                            // PCM
            out.write(shortToLE(info.channels))
            out.write(intToLE(info.sampleRate))
            out.write(intToLE(info.bytesPerSecond))
            out.write(shortToLE(info.channels * info.bitsPerSample / 8))
            out.write(shortToLE(info.bitsPerSample))
            out.writeBytes("data")
            out.write(intToLE(dataLen))
            out.write(pcm)
        }
    }

    private fun readIntLE(din: DataInputStream): Int {
        val b = ByteArray(4).also { din.readFully(it) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }
    private fun readShortLE(din: DataInputStream): Short {
        val b = ByteArray(2).also { din.readFully(it) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short
    }
    private fun intToLE(v: Int) = byteArrayOf(v.toByte(),(v shr 8).toByte(),(v shr 16).toByte(),(v shr 24).toByte())
    private fun shortToLE(v: Int) = byteArrayOf(v.toByte(),(v shr 8).toByte())
}
