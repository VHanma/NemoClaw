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
        private const val MAX_REDIRECTS = 8
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
        download(URL(MODEL_URL), archive, onProgress, onStatus)
        onStatus("Installing model…")
        extractTarBz2(archive, modelsRoot)
        archive.delete()
        check(isInstalled()) {
            val missing = required.filterNot { File(modelDir, it).isFile }
            "Model installed incompletely. Missing: ${missing.joinToString()}"
        }
        onProgress(100)
        onStatus("Voice engine ready")
    }

    private fun download(
        startUrl: URL,
        outFile: File,
        onProgress: (Int) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        val partFile = File(outFile.parentFile, outFile.name + ".part")
        partFile.delete()

        var current = startUrl
        var connection: HttpURLConnection? = null
        try {
            repeat(MAX_REDIRECTS + 1) { hop ->
                connection?.disconnect()
                val c = (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 90_000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("User-Agent", "VoiceVault-Android/0.1.1")
                    setRequestProperty("Accept", "application/octet-stream,*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                connection = c
                c.connect()
                val code = c.responseCode

                if (code in 200..299) {
                    val total = c.contentLengthLong
                    onStatus("Downloading voice model…")
                    c.inputStream.use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buf = ByteArray(128 * 1024)
                            var copied = 0L
                            var lastProgress = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val progress = ((copied * 100L) / total).toInt().coerceIn(0, 99)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                    check(partFile.length() > 1024L) { "Voice model download returned an empty file" }
                    if (outFile.exists()) outFile.delete()
                    check(partFile.renameTo(outFile)) { "Could not save downloaded voice model" }
                    return
                }

                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = c.getHeaderField("Location")
                        ?: error("Voice model redirect had no destination")
                    val next = URL(current, location)
                    check(next.protocol.equals("https", ignoreCase = true)) {
                        "Voice model redirect was not HTTPS"
                    }
                    if (hop >= MAX_REDIRECTS) error("Too many voice model download redirects")
                    onStatus("Connecting to model host…")
                    current = next
                } else {
                    val reason = c.responseMessage?.takeIf { it.isNotBlank() } ?: "HTTP error"
                    error("Voice model download failed: HTTP $code $reason (${current.host})")
                }
            }
            error("Voice model download could not follow redirects")
        } finally {
            connection?.disconnect()
            if (!outFile.isFile) partFile.delete()
        }
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
