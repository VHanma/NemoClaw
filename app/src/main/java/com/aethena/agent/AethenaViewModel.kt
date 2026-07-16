package com.aethena.agent

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String
)

class AethenaViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val brain = BrainClient()
    private val executor = ActionExecutor(application)
    private val workspace = ProjectWorkspace(application)

    val messages = mutableStateListOf<UiMessage>()

    var input by mutableStateOf("")
    var mode by mutableStateOf("Freeform")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("Strict uncensored mode")
    var speechText by mutableStateOf<String?>(null)
        private set

    var baseUrl by mutableStateOf(settings.baseUrl)
    var apiKey by mutableStateOf(settings.apiKey)
    var model by mutableStateOf(settings.model)
    var memory by mutableStateOf(settings.memory)
    var speakReplies by mutableStateOf(settings.speakReplies)

    var projectName by mutableStateOf("aethena-project")
    var codeRequest by mutableStateOf("")
    var latestZip by mutableStateOf<File?>(null)
        private set

    val activeProfileName: String
        get() = UncensoredModelCatalog.find(model)?.displayName ?: "No approved model selected"

    val activeProfileRepository: String
        get() = UncensoredModelCatalog.find(model)?.repository.orEmpty()

    init {
        if (!UncensoredModelCatalog.isAllowed(model)) useGeneralPreset()
        messages += UiMessage(
            role = "assistant",
            text = "I am Aethena in Strict Uncensored Mode. I only connect to approved local abliterated models. There is no censored fallback and no remote provider."
        )
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
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

    fun buildProject() {
        val request = codeRequest.trim()
        if (request.isBlank() || busy) return
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
        status = "Strict uncensored settings saved."
    }

    fun testConnection() {
        if (busy) return
        strictConfigurationError()?.let { error ->
            status = error
            return
        }
        persistSettings()
        busy = true
        status = "Testing approved local model…"
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

    fun useGeneralPreset() = selectProfile(UncensoredModelCatalog.general.id)

    fun useThinkerPreset() = selectProfile(UncensoredModelCatalog.thinker.id)

    fun useCoderPreset() = selectProfile(UncensoredModelCatalog.coder.id)

    fun openSelectedModelPage() {
        val repository = activeProfileRepository
        if (repository.isBlank()) {
            status = "Choose an approved model first."
            return
        }
        quickAction("open_uri", "https://huggingface.co/$repository")
    }

    fun shareLatestZip() {
        val file = latestZip ?: run {
            status = "Create a project ZIP first."
            return
        }
        val app = getApplication<Application>()
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

    private fun selectProfile(modelId: String) {
        baseUrl = UncensoredModelCatalog.LOCAL_BASE_URL
        model = modelId
        apiKey = ""
        persistSettings()
        val profile = UncensoredModelCatalog.find(modelId)
        status = "Selected ${profile?.displayName}. Start its local GGUF server, then test."
    }

    private fun strictConfigurationError(): String? {
        val normalized = baseUrl.trim().lowercase()
        val local = normalized.startsWith("http://127.0.0.1:") ||
            normalized.startsWith("http://localhost:") ||
            normalized.startsWith("https://127.0.0.1:") ||
            normalized.startsWith("https://localhost:")

        if (!local) return "Blocked: Strict Uncensored Mode accepts localhost only. Remote providers and hidden substitutions are disabled."
        if (!UncensoredModelCatalog.isAllowed(model)) return "Blocked: choose one of Aethena's approved uncensored/abliterated models."
        return null
    }

    private fun persistSettings() {
        settings.baseUrl = baseUrl
        settings.apiKey = ""
        settings.model = model
        settings.memory = memory
        settings.speakReplies = speakReplies
    }
}
