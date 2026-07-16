package com.aethena.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AethenaViewModel by viewModels()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else vm.status = "Microphone permission was not granted."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupSpeechRecognition()
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }

        setContent {
            val speech = vm.speechText
            LaunchedEffect(speech) {
                speech?.takeIf { it.isNotBlank() }?.let(::speak)
                if (speech != null) vm.consumeSpeech()
            }
            MaterialTheme(colorScheme = AethenaColors) {
                AethenaApp(vm = vm, onVoice = ::requestVoice, onSpeak = ::speak)
            }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun requestVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { vm.status = "Listening…" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { vm.status = "Understanding…" }
                override fun onError(error: Int) { vm.status = "Voice recognition error $error" }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        vm.input = text
                        vm.send()
                    } else vm.status = "I did not catch that."
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startListening() {
        val speech = recognizer ?: run {
            vm.status = "Speech recognition is unavailable on this phone."
            return
        }
        speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aethena-reply")
    }
}

private val AethenaColors = darkColorScheme(
    primary = Color(0xFFB9A4FF),
    secondary = Color(0xFF8EE8FF),
    background = Color(0xFF080B12),
    surface = Color(0xFF111624),
    surfaceVariant = Color(0xFF1A2133)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AethenaApp(
    vm: AethenaViewModel,
    onVoice: () -> Unit,
    onSpeak: (String) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Chat", "Operate", "Code", "Settings")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aethena")
                        Text(vm.status, style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(listOf("✦", "◎", "⌘", "⚙")[index]) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatTab(vm, onVoice, onSpeak)
                1 -> OperateTab(vm)
                2 -> CodeTab(vm)
                else -> SettingsTab(vm)
            }
        }
    }
}

@Composable
private fun ChatTab(vm: AethenaViewModel, onVoice: () -> Unit, onSpeak: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Freeform", "Deep Thought", "Council", "Architect").forEach { option ->
                FilterChip(
                    selected = vm.mode == option,
                    onClick = { vm.mode = option },
                    label = { Text(option) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.messages, key = { it.id }) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.role == "user") Color(0xFF292044) else Color(0xFF121A29)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (message.role == "user") "You" else "Aethena", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(message.text)
                            if (message.role == "assistant") {
                                TextButton(onClick = { onSpeak(message.text) }) { Text("Speak") }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.input,
            onValueChange = { vm.input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
            label = { Text("Ask, command, build, or explore") }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onVoice, modifier = Modifier.weight(1f)) { Text("Voice") }
            Button(onClick = vm::send, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                Text(if (vm.busy) "Working…" else "Send")
            }
        }
    }
}

@Composable
private fun OperateTab(vm: AethenaViewModel) {
    var termux by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Phone control", style = MaterialTheme.typography.headlineSmall)
        Text("Grant each Android permission once. After that, speak ordinary commands in Chat.")
        ActionButton("Enable screen reading and tapping") { vm.quickAction("open_accessibility_settings") }
        ActionButton("Enable notification memory") { vm.quickAction("open_notification_settings") }
        ActionButton("Allow floating Aethena orb") { vm.quickAction("open_overlay_settings") }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.quickAction("start_orb") }, modifier = Modifier.weight(1f)) { Text("Start orb") }
            Button(onClick = { vm.quickAction("stop_orb") }, modifier = Modifier.weight(1f)) { Text("Stop orb") }
        }
        Text("Quick controls", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.quickAction("back") }, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = { vm.quickAction("home") }, modifier = Modifier.weight(1f)) { Text("Home") }
            Button(onClick = { vm.quickAction("recents") }, modifier = Modifier.weight(1f)) { Text("Recents") }
        }
        ActionButton("Read current screen") { vm.quickAction("read_screen") }
        ActionButton("Read recent notifications") { vm.quickAction("read_notifications") }
        Text("Termux bridge", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = termux,
            onValueChange = { termux = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Shell command") },
            minLines = 2
        )
        Button(onClick = { vm.quickAction("termux", termux) }, enabled = termux.isNotBlank()) {
            Text("Run through Termux")
        }
    }
}

@Composable
private fun ActionButton(label: String, action: () -> Unit) {
    Button(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun CodeTab(vm: AethenaViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Architect workspace", style = MaterialTheme.typography.headlineSmall)
        Text("Describe the finished project. Aethena asks the connected model for every file and packages them into one ZIP.")
        OutlinedTextField(
            value = vm.projectName,
            onValueChange = { vm.projectName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Project name") }
        )
        OutlinedTextField(
            value = vm.codeRequest,
            onValueChange = { vm.codeRequest = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What should Aethena build?") },
            minLines = 8
        )
        Button(onClick = vm::buildProject, enabled = !vm.busy && vm.codeRequest.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.busy) "Building…" else "Create complete project ZIP")
        }
        Button(onClick = vm::shareLatestZip, enabled = vm.latestZip != null, modifier = Modifier.fillMaxWidth()) {
            Text("Share latest ZIP")
        }
        vm.latestZip?.let { Text("Latest: ${it.name}") }
    }
}

@Composable
private fun SettingsTab(vm: AethenaViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Model connection", style = MaterialTheme.typography.headlineSmall)
        Text("Aethena accepts local llama.cpp, Hugging Face endpoints, or any OpenAI-compatible server.")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::useLocalPreset, modifier = Modifier.weight(1f)) { Text("Local") }
            Button(onClick = vm::useOpenAiPreset, modifier = Modifier.weight(1f)) { Text("OpenAI backup") }
        }
        OutlinedTextField(
            value = vm.baseUrl,
            onValueChange = { vm.baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            singleLine = true
        )
        OutlinedTextField(
            value = vm.model,
            onValueChange = { vm.model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model name") },
            singleLine = true
        )
        OutlinedTextField(
            value = vm.apiKey,
            onValueChange = { vm.apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API token, blank for local") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        OutlinedTextField(
            value = vm.memory,
            onValueChange = { vm.memory = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Aethena memory and personality") },
            minLines = 6
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speak replies", modifier = Modifier.weight(1f))
            Switch(checked = vm.speakReplies, onCheckedChange = { vm.speakReplies = it })
        }
        Button(onClick = vm::saveSettings, modifier = Modifier.fillMaxWidth()) { Text("Save encrypted settings") }
        Spacer(Modifier.height(10.dp))
        Text("Hugging Face note", style = MaterialTheme.typography.titleMedium)
        Text("For a hosted model, paste its OpenAI-compatible endpoint URL, token, and model ID. For an offline model, run a llama.cpp server on port 8080 and use the Local preset.")
    }
}
