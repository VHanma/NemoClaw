package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object AudioUtils {
    const val REFERENCE_SAMPLE_RATE = 24_000

    fun voiceFile(context: Context, prefix: String = "voice"): File {
        val dir = File(context.filesDir, "voices").apply { mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.wav")
    }

    fun importPcm16Wav(context: Context, uri: Uri): File {
        val out = voiceFile(context, "import")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open audio file" }
            FileOutputStream(out).use { input.copyTo(it) }
        }
        // Validate before adding the profile.
        readPcm16Wav(out)
        return out
    }

    fun readPcm16Wav(file: File): Pair<FloatArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            fun fourcc(): String {
                val b = ByteArray(4)
                raf.readFully(b)
                return String(b, Charsets.US_ASCII)
            }
            fun u16le(): Int {
                val b = ByteArray(2); raf.readFully(b)
                return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
            }
            fun i32le(): Int {
                val b = ByteArray(4); raf.readFully(b)
                return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
            }

            require(fourcc() == "RIFF") { "Reference audio must be a WAV file" }
            i32le()
            require(fourcc() == "WAVE") { "Invalid WAV file" }

            var format = 0
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var dataOffset = -1L
            var dataSize = 0

            while (raf.filePointer + 8 <= raf.length()) {
                val id = fourcc()
                val size = i32le()
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        format = u16le()
                        channels = u16le()
                        sampleRate = i32le()
                        i32le() // byte rate
                        u16le() // block align
                        bits = u16le()
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        break
                    }
                }
                raf.seek(start + size + (size and 1))
            }

            require(format == 1) { "Only uncompressed PCM WAV references are supported in this build" }
            require(bits == 16) { "Reference WAV must be 16-bit PCM" }
            require(channels in 1..2) { "Reference WAV must be mono or stereo" }
            require(sampleRate > 0 && dataOffset >= 0) { "WAV audio data was not found" }

            raf.seek(dataOffset)
            val frameCount = dataSize / (2 * channels)
            val samples = FloatArray(frameCount)
            val two = ByteArray(2)
            for (i in 0 until frameCount) {
                var sum = 0f
                for (ch in 0 until channels) {
                    raf.readFully(two)
                    val s = ByteBuffer.wrap(two).order(ByteOrder.LITTLE_ENDIAN).short
                    sum += s / 32768f
                }
                samples[i] = sum / channels
            }
            return samples to sampleRate
        }
    }

    fun writePcm16Wav(file: File, pcm: ShortArray, sampleRate: Int = REFERENCE_SAMPLE_RATE) {
        FileOutputStream(file).use { fos ->
            BufferedOutputStream(fos).use { out ->
                val dataBytes = pcm.size * 2
                fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
                fun le16(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte()))
                fun le32(v: Int) = out.write(byteArrayOf(
                    (v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(),
                    ((v ushr 16) and 0xff).toByte(), ((v ushr 24) and 0xff).toByte()
                ))
                ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
                ascii("fmt "); le32(16); le16(1); le16(1); le32(sampleRate)
                le32(sampleRate * 2); le16(2); le16(16)
                ascii("data"); le32(dataBytes)
                val bb = ByteBuffer.allocate(max(2, pcm.size * 2)).order(ByteOrder.LITTLE_ENDIAN)
                pcm.forEach { bb.putShort(it) }
                out.write(bb.array(), 0, pcm.size * 2)
            }
        }
    }

    fun playFloatMono(samples: FloatArray, sampleRate: Int, stop: AtomicBoolean): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(min, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        val chunk = ShortArray(4096)
        var pos = 0
        while (pos < samples.size && !stop.get()) {
            val n = minOf(chunk.size, samples.size - pos)
            for (i in 0 until n) {
                chunk[i] = (samples[pos + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            track.write(chunk, 0, n, AudioTrack.WRITE_BLOCKING)
            pos += n
        }
        return track
    }
}

class VoiceRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val recording = AtomicBoolean(false)
    private var rawFile: File? = null

    fun isRecording(): Boolean = recording.get()

    fun start() {
        check(!recording.get()) { "Already recording" }
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioUtils.REFERENCE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioUtils.REFERENCE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer * 2, 8192),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not start at 24 kHz" }
        val raw = File(context.cacheDir, "voice_${System.currentTimeMillis()}.pcm")
        rawFile = raw
        audioRecord = record
        recording.set(true)
        record.startRecording()
        thread = Thread {
            FileOutputStream(raw).use { out ->
                val buffer = ShortArray(4096)
                val bytes = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (recording.get()) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        bytes.clear()
                        for (i in 0 until n) bytes.putShort(buffer[i])
                        out.write(bytes.array(), 0, n * 2)
                    }
                }
            }
        }.also { it.start() }
    }

    fun stop(): File {
        check(recording.get()) { "Not recording" }
        recording.set(false)
        runCatching { audioRecord?.stop() }
        thread?.join(1500)
        audioRecord?.release()
        audioRecord = null
        thread = null

        val raw = requireNotNull(rawFile)
        val bytes = raw.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(bytes.size / 2)
        for (i in pcm.indices) pcm[i] = bb.short
        val wav = AudioUtils.voiceFile(context, "recorded")
        AudioUtils.writePcm16Wav(wav, pcm)
        raw.delete()
        rawFile = null
        return wav
    }

    fun cancel() {
        if (recording.get()) {
            recording.set(false)
            runCatching { audioRecord?.stop() }
            thread?.join(500)
        }
        audioRecord?.release()
        audioRecord = null
        rawFile?.delete()
        rawFile = null
    }
}
