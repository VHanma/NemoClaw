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
    var status by mutableStateOf("Ready")
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

    init {
        messages += UiMessage(
            role = "assistant",
            text = "I am Aethena. Connect a local or hosted model in Settings, then speak or type what you need."
        )
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        input = ""
        messages += UiMessage(role = "user", text = text)
        busy = true
        status = "Thinking…"

        viewModelScope.launch {
            try {
                val history = messages
                    .dropLast(1)
                    .takeLast(18)
                    .map { ChatMessage(it.role, it.text) }
                val result = brain.ask(settings, history, text, mode)
                val reply = result.reply.ifBlank { "I completed the request but received an empty reply." }
                messages += UiMessage(role = "assistant", text = reply)
                if (settings.speakReplies) speechText = reply

                if (result.actions.isEmpty()) {
                    status = "Ready"
                } else {
                    val outcomes = result.actions.map { executor.execute(it) }
                    status = outcomes.joinToString("\n")
                }
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                messages += UiMessage(role = "assistant", text = "Connection or model error: $message")
                status = "Error"
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
        busy = true
        status = "Architect is creating complete files…"
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
        settings.baseUrl = baseUrl
        settings.apiKey = apiKey
        settings.model = model
        settings.memory = memory
        settings.speakReplies = speakReplies
        status = "Encrypted settings saved."
    }

    fun useLocalPreset() {
        baseUrl = "http://127.0.0.1:8080/v1"
        model = "local-model"
        apiKey = ""
    }

    fun useOpenAiPreset() {
        baseUrl = "https://api.openai.com/v1"
        model = "gpt-4.1-mini"
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
}
