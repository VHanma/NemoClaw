package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class VoiceVaultEngine(
    private val context: Context,
    private val modelManager: ModelManager,
) {
    private var tts: OfflineTts? = null
    private val stopFlag = AtomicBoolean(false)
    @Volatile private var currentTrack: AudioTrack? = null

    fun loaded(): Boolean = tts != null

    fun load() {
        if (tts != null) return
        check(modelManager.isInstalled()) { "Install the voice engine first" }
        val d = modelManager.modelDir
        val pocket = OfflineTtsPocketModelConfig(
            lmFlow = File(d, "lm_flow.int8.onnx").absolutePath,
            lmMain = File(d, "lm_main.int8.onnx").absolutePath,
            encoder = File(d, "encoder.onnx").absolutePath,
            decoder = File(d, "decoder.int8.onnx").absolutePath,
            textConditioner = File(d, "text_conditioner.onnx").absolutePath,
            vocabJson = File(d, "vocab.json").absolutePath,
            tokenScoresJson = File(d, "token_scores.json").absolutePath,
            voiceEmbeddingCacheCapacity = 64,
        )
        val model = OfflineTtsModelConfig(
            pocket = pocket,
            numThreads = max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)),
            debug = false,
            provider = "cpu",
        )
        tts = OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
    }

    suspend fun speak(
        text: String,
        voice: VoiceProfile,
        speed: Float = 1.0f,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.Default) {
        load()
        stopFlag.set(false)
        val (reference, referenceRate) = AudioUtils.readPcm16Wav(File(voice.wavPath))
        val generation = GenerationConfig(
            speed = speed.coerceIn(0.5f, 2.0f),
            referenceAudio = reference,
            referenceSampleRate = referenceRate,
            numSteps = 5,
            extra = mapOf("temperature" to "0.7", "chunk_size" to "15"),
        )
        val chunks = splitForSpeech(text)
        check(chunks.isNotEmpty()) { "There is no text to read" }
        val engine = requireNotNull(tts)

        for ((index, chunk) in chunks.withIndex()) {
            if (stopFlag.get()) break
            onProgress(index + 1, chunks.size)
            val generated = engine.generateWithConfig(chunk, generation)
            if (stopFlag.get()) break
            val track = AudioUtils.playFloatMono(generated.samples, generated.sampleRate, stopFlag)
            currentTrack = track
            val target = generated.samples.size.coerceAtMost(Int.MAX_VALUE)
            while (!stopFlag.get() && track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < target) {
                Thread.sleep(20)
            }
            runCatching { track.stop() }
            track.release()
            currentTrack = null
        }
    }

    suspend fun renderPreview(text: String, voice: VoiceProfile): File = withContext(Dispatchers.Default) {
        load()
        val (reference, referenceRate) = AudioUtils.readPcm16Wav(File(voice.wavPath))
        val generation = GenerationConfig(
            referenceAudio = reference,
            referenceSampleRate = referenceRate,
            numSteps = 5,
            extra = mapOf("temperature" to "0.7", "chunk_size" to "15"),
        )
        val generated = requireNotNull(tts).generateWithConfig(text.take(700), generation)
        val pcm = ShortArray(generated.samples.size) { i ->
            (generated.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return@withContext File(context.cacheDir, "voicevault_${System.currentTimeMillis()}.wav").also {
            AudioUtils.writePcm16Wav(it, pcm, generated.sampleRate)
        }
    }

    fun stop() {
        stopFlag.set(true)
        currentTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
        }
        currentTrack = null
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
    }

    private fun splitForSpeech(input: String): List<String> {
        val clean = input.replace("\r", "").trim()
        if (clean.isBlank()) return emptyList()
        val sentences = clean.split(Regex("(?<=[.!?。！？])\\s+|\\n+"))
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            val s = buf.toString().trim()
            if (s.isNotBlank()) out += s
            buf.clear()
        }
        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isBlank()) continue
            if (s.length > 420) {
                flush()
                s.chunked(380).forEach { out += it }
            } else if (buf.length + s.length + 1 > 420) {
                flush(); buf.append(s)
            } else {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(s)
            }
        }
        flush()
        return out
    }

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
