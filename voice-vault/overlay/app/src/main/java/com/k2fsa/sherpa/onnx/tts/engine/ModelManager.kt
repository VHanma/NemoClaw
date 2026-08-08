package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelManager(private val context: Context) {
    companion object {
        const val MODEL_NAME = "sherpa-onnx-pocket-tts-int8-2026-01-26"
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2"
    }

    val modelsRoot: File = File(context.filesDir, "models").apply { mkdirs() }
    val modelDir: File = File(modelsRoot, MODEL_NAME)

    private val required = listOf(
        "lm_flow.int8.onnx",
        "lm_main.int8.onnx",
        "encoder.onnx",
        "decoder.int8.onnx",
        "text_conditioner.onnx",
        "vocab.json",
        "token_scores.json",
    )

    fun isInstalled(): Boolean = required.all { File(modelDir, it).isFile }

    suspend fun install(onStatus: (String) -> Unit, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            onProgress(100)
            onStatus("Voice engine ready")
            return@withContext
        }

        modelsRoot.mkdirs()
        val archive = File(context.cacheDir, "$MODEL_NAME.tar.bz2")
        onStatus("Downloading PocketTTS model…")
        download(URL(MODEL_URL), archive, onProgress)
        onStatus("Installing model…")
        extractTarBz2(archive, modelsRoot)
        archive.delete()
        check(isInstalled()) { "Model download completed but required model files are missing" }
        onProgress(100)
        onStatus("Voice engine ready")
    }

    private fun download(url: URL, outFile: File, onProgress: (Int) -> Unit) {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VoiceVault/0.1 Android")
        }
        connection.connect()
        check(connection.responseCode in 200..299) { "Model download failed: HTTP ${connection.responseCode}" }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(128 * 1024)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    copied += n
                    if (total > 0) onProgress(((copied * 100L) / total).toInt().coerceIn(0, 99))
                }
            }
        }
        connection.disconnect()
    }

    private fun extractTarBz2(archive: File, destination: File) {
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val target = File(destination, entry.name).canonicalFile
                            val root = destination.canonicalFile
                            check(target.path.startsWith(root.path + File.separator)) { "Unsafe model archive path" }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { tar.copyTo(it) }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
        }
    }
}
