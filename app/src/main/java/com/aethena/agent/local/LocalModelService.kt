package com.aethena.agent.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.aethena.agent.MainActivity
import com.aethena.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class LocalModelService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Aethena local brain", "Ready to install or start"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INSTALL_AND_START -> installAndStart()
            ACTION_START -> startExistingModel()
            ACTION_STOP -> stopBrain()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeJob?.cancel()
        stopProcess()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun installAndStart() {
        if (activeJob?.isActive == true) return
        activeJob = serviceScope.launch {
            try {
                val model = modelFile()
                if (!model.exists()) downloadModel(model)
                verifyModel(model)
                startServer(model)
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                LocalBrainRuntime.update("Setup failed", message, online = false)
                updateNotification("Setup failed", message)
            }
        }
    }

    private fun startExistingModel() {
        if (activeJob?.isActive == true) return
        activeJob = serviceScope.launch {
            try {
                val model = modelFile()
                require(model.exists()) { "The verified model is not installed yet. Press Install and Start Brain." }
                verifyModel(model)
                startServer(model)
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                LocalBrainRuntime.update("Start failed", message, online = false)
                updateNotification("Start failed", message)
            }
        }
    }

    private suspend fun downloadModel(destination: File) = withContext(Dispatchers.IO) {
        val available = StatFs(filesDir.absolutePath).availableBytes
        require(available >= MIN_FREE_BYTES) {
            "Aethena needs about 450 MB free for the verified local model and download workspace."
        }

        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()

        LocalBrainRuntime.update("Downloading uncensored brain", "0% of about 338 MB", 0, online = false)
        updateNotification("Downloading uncensored brain", "0% of about 338 MB")

        val request = Request.Builder()
            .url(MODEL_URL)
            .header("User-Agent", "Aethena-Android/0.4")
            .build()

        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Model download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Model download returned no file data.")
            val total = body.contentLength().takeIf { it > 0 } ?: EXPECTED_APPROX_BYTES
            var copied = 0L
            var lastPercent = -1

            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = ((copied.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            val detail = "$percent% · ${copied / 1_048_576} MB downloaded"
                            LocalBrainRuntime.update("Downloading uncensored brain", detail, percent, online = false)
                            updateNotification("Downloading uncensored brain", detail)
                        }
                    }
                    output.fd.sync()
                }
            }
        }

        require(partial.length() > 300L * 1024L * 1024L) { "Downloaded model file is unexpectedly small." }
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
    }

    private suspend fun verifyModel(model: File) = withContext(Dispatchers.IO) {
        LocalBrainRuntime.update("Verifying exact model", "Checking SHA-256…", 100, online = false)
        updateNotification("Verifying exact model", "Checking SHA-256")

        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(model).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
            model.delete()
            error("Model verification failed. The file was deleted instead of being trusted.")
        }
        LocalBrainRuntime.update(
            phase = "Model verified",
            detail = "Exact approved uncensored GGUF confirmed",
            progressPercent = 100,
            online = false,
            modelVerified = true
        )
    }

    private suspend fun startServer(model: File) = withContext(Dispatchers.IO) {
        stopProcess()
        val nativeDir = applicationInfo.nativeLibraryDir
        val executable = File(nativeDir, "libllama_server_exec.so")
        require(executable.exists()) { "The bundled llama.cpp engine is missing from this APK." }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val command = listOf(
            executable.absolutePath,
            "-m", model.absolutePath,
            "--alias", MODEL_ALIAS,
            "-c", "2048",
            "--host", "127.0.0.1",
            "--port", "8080",
            "--parallel", "1",
            "--threads", threads.toString(),
            "--no-ui"
        )

        LocalBrainRuntime.update(
            phase = "Starting local brain",
            detail = "Loading the verified model into memory…",
            progressPercent = 100,
            online = false,
            modelVerified = true
        )
        updateNotification("Starting local brain", "Loading verified model")

        val process = ProcessBuilder(command)
            .directory(filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir
                environment()["HOME"] = filesDir.absolutePath
            }
            .start()
        LocalBrainRuntime.serverProcess = process

        serviceScope.launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("error", ignoreCase = true)) {
                        LocalBrainRuntime.update(
                            phase = "Local engine log",
                            detail = line.take(220),
                            progressPercent = 100,
                            online = false,
                            modelVerified = true
                        )
                    }
                }
            }
        }

        repeat(120) {
            if (!process.isAlive) error("The bundled local engine stopped while loading the model.")
            if (healthCheck()) {
                LocalBrainRuntime.update(
                    phase = "Local brain online",
                    detail = "Verified uncensored model is ready. No remote fallback.",
                    progressPercent = 100,
                    online = true,
                    modelVerified = true
                )
                updateNotification("Aethena brain online", "Verified model running locally")
                return@withContext
            }
            delay(1_000)
        }
        stopProcess()
        error("The local model took too long to start.")
    }

    private fun healthCheck(): Boolean {
        val request = Request.Builder().url("http://127.0.0.1:8080/health").build()
        return runCatching {
            http.newCall(request).execute().use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    private fun stopBrain() {
        activeJob?.cancel()
        activeJob = null
        stopProcess()
        LocalBrainRuntime.update(
            phase = "Local brain stopped",
            detail = "The model remains installed and verified.",
            progressPercent = 100,
            online = false,
            modelVerified = modelFile().exists()
        )
        updateNotification("Aethena brain stopped", "Model remains installed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProcess() {
        LocalBrainRuntime.serverProcess?.let { process ->
            runCatching { process.destroy() }
            runCatching {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        LocalBrainRuntime.serverProcess = null
    }

    private fun modelFile(): File = File(File(filesDir, "models"), MODEL_FILENAME)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aethena Local Brain", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(title: String, text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aethena)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(title, text))
    }

    companion object {
        const val ACTION_INSTALL_AND_START = "com.aethena.agent.INSTALL_AND_START_LOCAL_BRAIN"
        const val ACTION_START = "com.aethena.agent.START_LOCAL_BRAIN"
        const val ACTION_STOP = "com.aethena.agent.STOP_LOCAL_BRAIN"

        const val MODEL_ALIAS = "qwen2.5-0.5b-abliterated-sft"
        const val MODEL_FILENAME = "Aethena-Qwen2.5-0.5B-Abliterated-SFT-Q3_K_S.gguf"
        const val MODEL_SHA256 = "65175e70ac1054990fc3a63bd31533f7864e8fecdee2b3b7f5c529949c49d6d8"
        const val MODEL_URL = "https://huggingface.co/mradermacher/Qwen2.5-0.5B-Instruct-abliterated-SFT-i1-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-abliterated-SFT.i1-Q3_K_S.gguf?download=true"

        private const val CHANNEL_ID = "aethena_local_brain"
        private const val NOTIFICATION_ID = 7402
        private const val EXPECTED_APPROX_BYTES = 338L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 450L * 1024L * 1024L
    }
}
