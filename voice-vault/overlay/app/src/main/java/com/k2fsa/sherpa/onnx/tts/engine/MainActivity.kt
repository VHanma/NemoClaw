package com.k2fsa.sherpa.onnx.tts.engine

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var modelManager: ModelManager
    private lateinit var profileStore: VoiceProfileStore
    private lateinit var engine: VoiceVaultEngine
    private lateinit var recorder: VoiceRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelManager = ModelManager(this)
        profileStore = VoiceProfileStore(this)
        engine = VoiceVaultEngine(this, modelManager)
        recorder = VoiceRecorder(this)

        val incoming = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else ""

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var status by remember { mutableStateOf(if (modelManager.isInstalled()) "Voice engine ready" else "Install the offline voice engine once") }
                    var modelProgress by remember { mutableIntStateOf(if (modelManager.isInstalled()) 100 else 0) }
                    var installing by remember { mutableStateOf(false) }
                    var profiles by remember { mutableStateOf(profileStore.all()) }
                    var selectedId by remember { mutableStateOf(profileStore.selected()?.id) }
                    var voiceName by remember { mutableStateOf("") }
                    var text by remember { mutableStateOf(incoming) }
                    var speed by remember { mutableFloatStateOf(1.0f) }
                    var speaking by remember { mutableStateOf(false) }
                    var recording by remember { mutableStateOf(false) }
                    var speechPart by remember { mutableIntStateOf(0) }
                    var speechTotal by remember { mutableIntStateOf(0) }

                    fun refreshProfiles() {
                        profiles = profileStore.all()
                        selectedId = profileStore.selected()?.id
                    }

                    fun beginRecording() {
                        try {
                            recorder.start()
                            recording = true
                            status = "Recording reference voice… speak clearly for about 5–15 seconds"
                        } catch (e: Exception) {
                            status = e.message ?: "Recording failed"
                        }
                    }

                    val microphonePermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) beginRecording() else status = "Microphone permission is needed to record a voice sample"
                    }

                    val importVoice = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            lifecycleScope.launch {
                                try {
                                    status = "Importing voice reference…"
                                    val file = withContext(Dispatchers.IO) { AudioUtils.importPcm16Wav(this@MainActivity, uri) }
                                    profileStore.add(voiceName, file)
                                    voiceName = ""
                                    refreshProfiles()
                                    status = "Voice clone profile saved"
                                } catch (e: Exception) {
                                    status = e.message ?: "Voice import failed"
                                }
                            }
                        }
                    }

                    val importDocument = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            lifecycleScope.launch {
                                try {
                                    status = "Reading document…"
                                    text = DocumentReader.read(this@MainActivity, uri)
                                    status = "Document loaded: ${text.length} characters"
                                } catch (e: Exception) {
                                    status = e.message ?: "Document could not be read"
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("VOICE VAULT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        Text("Offline multi-voice cloning + document reader", style = MaterialTheme.typography.bodyMedium)

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("1. Voice engine", fontWeight = FontWeight.Bold)
                                Text(status)
                                if (installing || modelProgress in 1..99) {
                                    LinearProgressIndicator(
                                        progress = { modelProgress / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (!installing) {
                                            installing = true
                                            lifecycleScope.launch {
                                                try {
                                                    modelManager.install(
                                                        onStatus = { s -> runOnUiThread { status = s } },
                                                        onProgress = { p -> runOnUiThread { modelProgress = p } },
                                                    )
                                                    withContext(Dispatchers.Default) { engine.load() }
                                                } catch (e: Exception) {
                                                    status = e.message ?: "Voice engine installation failed"
                                                } finally {
                                                    installing = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !installing,
                                ) {
                                    Text(if (modelManager.isInstalled()) "Check / Load Engine" else "Install Voice Engine")
                                }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("2. Voice clones", fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = voiceName,
                                    onValueChange = { voiceName = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("New voice name") },
                                    singleLine = true,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        if (recording) {
                                            try {
                                                val wav = recorder.stop()
                                                recording = false
                                                profileStore.add(voiceName, wav)
                                                voiceName = ""
                                                refreshProfiles()
                                                status = "Voice clone profile saved"
                                            } catch (e: Exception) {
                                                recording = false
                                                status = e.message ?: "Could not save recording"
                                            }
                                        } else {
                                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                beginRecording()
                                            } else {
                                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }) {
                                        Text(if (recording) "Stop + Save" else "Record Voice")
                                    }
                                    OutlinedButton(onClick = { importVoice.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) {
                                        Text("Import WAV")
                                    }
                                }
                                Text("Use a clean reference sample. Imported references currently accept uncompressed 16-bit PCM WAV; the recorder creates the correct format automatically.", style = MaterialTheme.typography.bodySmall)

                                if (profiles.isEmpty()) {
                                    Text("No saved voices yet.")
                                } else {
                                    profiles.forEach { p ->
                                        val selected = p.id == selectedId
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    profileStore.select(p.id)
                                                    selectedId = p.id
                                                    status = "Selected ${p.name}"
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(if (selected) "● ${p.name}" else "○ ${p.name}", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                                    Text("Local reference", style = MaterialTheme.typography.bodySmall)
                                                }
                                                OutlinedButton(onClick = {
                                                    profileStore.delete(p.id)
                                                    refreshProfiles()
                                                    status = "Voice removed"
                                                }) { Text("Delete") }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("3. Read anything", fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { importDocument.launch(arrayOf("text/*", "application/pdf", "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/json", "*/*")) }) {
                                        Text("Import Document")
                                    }
                                    if (speaking) {
                                        OutlinedButton(onClick = {
                                            engine.stop()
                                            speaking = false
                                            status = "Stopped"
                                        }) { Text("Stop") }
                                    }
                                }

                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    modifier = Modifier.fillMaxWidth().height(260.dp),
                                    label = { Text("Paste, type, or import text") },
                                )

                                Text("Speed ${"%.2f".format(speed)}×")
                                Slider(
                                    value = speed,
                                    onValueChange = { speed = it },
                                    valueRange = 0.65f..1.65f,
                                )

                                if (speechTotal > 0 && speaking) {
                                    Text("Reading section $speechPart of $speechTotal")
                                }

                                Button(
                                    onClick = {
                                        val profile = profiles.firstOrNull { it.id == selectedId } ?: profileStore.selected()
                                        when {
                                            !modelManager.isInstalled() -> status = "Install the voice engine first"
                                            profile == null -> status = "Create or select a voice first"
                                            text.isBlank() -> status = "Add some text or import a document"
                                            speaking -> Unit
                                            else -> {
                                                speaking = true
                                                speechPart = 0
                                                speechTotal = 0
                                                lifecycleScope.launch {
                                                    try {
                                                        status = "Generating ${profile.name}…"
                                                        engine.speak(text, profile, speed) { part, total ->
                                                            runOnUiThread {
                                                                speechPart = part
                                                                speechTotal = total
                                                                status = "${profile.name} is reading"
                                                            }
                                                        }
                                                        status = if (speaking) "Finished" else "Stopped"
                                                    } catch (e: Exception) {
                                                        status = e.message ?: "Speech generation failed"
                                                    } finally {
                                                        speaking = false
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !speaking,
                                ) {
                                    Text("READ WITH SELECTED CLONE")
                                }
                            }
                        }

                        HorizontalDivider()
                        Text("Voice profiles and inference stay local. Only the neural model download requires internet. Use voice references you own or have permission to synthesize.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        recorder.cancel()
        engine.release()
        super.onDestroy()
    }
}
