package com.aethena.agent

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aethena.agent.automation.ActionExecutor
import com.aethena.agent.brain.AgentAction
import com.aethena.agent.brain.BrainClient
import com.aethena.agent.brain.ChatMessage
import com.aethena.agent.brain.UncensoredModelCatalog
import com.aethena.agent.coding.ProjectWorkspace
import com.aethena.agent.data.SettingsStore
import com.aethena.agent.local.LocalBrainRuntime
import com.aethena.agent.local.LocalModelService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String
)

class AethenaViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val settings = SettingsStore(application)
    private val brain = BrainClient()
    private val executor = ActionExecutor(application)
    private val workspace = ProjectWorkspace(application)

    val messages = mutableStateListOf<UiMessage>()

    var input by mutableStateOf("")
    var mode by mutableStateOf("Freeform")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("Install local brain in Settings")
    var speechText by mutableStateOf<String?>(null)
        private set

    var baseUrl by mutableStateOf(UncensoredModelCatalog.LOCAL_BASE_URL)
    var apiKey by mutableStateOf("")
    var model by mutableStateOf(UncensoredModelCatalog.verified.id)
    var memory by mutableStateOf(settings.memory)
    var speakReplies by mutableStateOf(settings.speakReplies)

    var localBrainPhase by mutableStateOf("Not installed")
        private set
    var localBrainDetail by mutableStateOf("Install the verified local uncensored brain.")
        private set
    var localBrainProgress by mutableStateOf(0)
        private set
    var localBrainOnline by mutableStateOf(false)
        private set
    var localModelVerified by mutableStateOf(false)
        private set

    var projectName by mutableStateOf("aethena-project")
    var codeRequest by mutableStateOf("")
    var latestZip by mutableStateOf<File?>(null)
        private set

    val activeProfileName: String
        get() = UncensoredModelCatalog.verified.displayName

    val activeProfileRepository: String
        get() = UncensoredModelCatalog.verified.repository

    init {
        persistSettings()
        messages += UiMessage(
            role = "assistant",
            text = "I am Aethena in Strict Uncensored Mode. Open Settings and press Install and Start Brain once. I will download and verify the exact local model automatically."
        )
        viewModelScope.launch {
            LocalBrainRuntime.state.collectLatest { state ->
                localBrainPhase = state.phase
                localBrainDetail = state.detail
                localBrainProgress = state.progressPercent
                localBrainOnline = state.online
                localModelVerified = state.modelVerified
                if (!busy) status = state.phase
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        if (!localBrainOnline) {
            val message = "Aethena's local brain is not running yet. Open Settings and press Install and Start Brain. The app handles the download, verification, and startup."
            status = "Start local brain"
            messages += UiMessage(role = "assistant", text = message)
            return
        }
        strictConfigurationError()?.let { error ->
            status = error
            messages += UiMessage(role = "assistant", text = error)
            return
        }

        input = ""
        messages += UiMessage(role = "user", text = text)
        busy = true
        status = "Thinking locally…"

        viewModelScope.launch {
            try {
                val history = messages
                    .dropLast(1)
                    .takeLast(18)
                    .map { ChatMessage(it.role, it.text) }
                val result = brain.ask(settings, history, text, mode)
                val reply = result.reply.ifBlank { "The local model returned an empty reply." }
                messages += UiMessage(role = "assistant", text = reply)
                if (settings.speakReplies) speechText = reply

                if (result.actions.isEmpty()) {
                    status = "Local uncensored brain online"
                } else {
                    val outcomes = result.actions.map { executor.execute(it) }
                    status = outcomes.joinToString("\n")
                }
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                messages += UiMessage(role = "assistant", text = "Local brain connection: $message")
                status = "Local model offline"
            } finally {
                busy = false
            }
        }
    }

    fun quickAction(type: String, arg: String = "") {
        viewModelScope.launch {
            status = executor.execute(AgentAction(type, arg))
        }
    }

    fun installAndStartBrain() {
        persistSettings()
        val intent = Intent(app, LocalModelService::class.java).setAction(LocalModelService.ACTION_INSTALL_AND_START)
        ContextCompat.startForegroundService(app, intent)
        status = "Preparing verified local brain…"
    }

    fun startInstalledBrain() {
        persistSettings()
        val intent = Intent(app, LocalModelService::class.java).setAction(LocalModelService.ACTION_START)
        ContextCompat.startForegroundService(app, intent)
        status = "Starting verified local brain…"
    }

    fun stopLocalBrain() {
        val intent = Intent(app, LocalModelService::class.java).setAction(LocalModelService.ACTION_STOP)
        ContextCompat.startForegroundService(app, intent)
    }

    fun buildProject() {
        val request = codeRequest.trim()
        if (request.isBlank() || busy) return
        if (!localBrainOnline) {
            status = "Install and start the local brain first."
            return
        }
        strictConfigurationError()?.let { error ->
            status = error
            return
        }

        busy = true
        status = "Aethena Architect is creating complete files locally…"
        viewModelScope.launch {
            try {
                val output = brain.buildProject(settings, request)
                latestZip = workspace.saveProject(projectName, output)
                status = "Project ZIP created: ${latestZip?.name}"
            } catch (error: Throwable) {
                status = "Project build failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun saveSettings() {
        strictConfigurationError()?.let { error ->
            status = error
            return
        }
        persistSettings()
        status = "Strict local settings saved."
    }

    fun testConnection() {
        if (busy) return
        if (!localBrainOnline) {
            status = "Start the installed local brain first."
            return
        }
        persistSettings()
        busy = true
        status = "Testing verified local model…"
        viewModelScope.launch {
            try {
                val result = brain.test(settings)
                status = "Verified local connection"
                messages += UiMessage(role = "assistant", text = result)
                if (settings.speakReplies) speechText = result
            } catch (error: Throwable) {
                status = error.message ?: error.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun openSelectedModelPage() {
        quickAction("open_uri", "https://huggingface.co/$activeProfileRepository")
    }

    fun shareLatestZip() {
        val file = latestZip ?: run {
            status = "Create a project ZIP first."
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(Intent.createChooser(share, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun consumeSpeech() {
        speechText = null
    }

    private fun strictConfigurationError(): String? {
        val normalized = baseUrl.trim().lowercase()
        val local = normalized.startsWith("http://127.0.0.1:") ||
            normalized.startsWith("http://localhost:")

        if (!local) return "Blocked: Strict Uncensored Mode accepts localhost only. Remote providers and hidden substitutions are disabled."
        if (!UncensoredModelCatalog.isAllowed(model)) return "Blocked: Aethena accepts only the bundled verified uncensored model alias."
        return null
    }

    private fun persistSettings() {
        baseUrl = UncensoredModelCatalog.LOCAL_BASE_URL
        apiKey = ""
        model = UncensoredModelCatalog.verified.id
        settings.baseUrl = baseUrl
        settings.apiKey = ""
        settings.model = model
        settings.memory = memory
        settings.speakReplies = speakReplies
    }
}
