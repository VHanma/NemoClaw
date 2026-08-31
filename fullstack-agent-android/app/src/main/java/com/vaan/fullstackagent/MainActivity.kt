package com.vaan.fullstackagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var memory: AgentMemory
    private val client = AgentClient()
    private lateinit var face: FaceView
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val prefs by lazy { getSharedPreferences("agent", MODE_PRIVATE) }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startListening() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = AgentMemory(this)
        tts = TextToSpeech(this, this)
        buildUi()
        refreshTranscript()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        val title = TextView(this).apply { text = prefs.getString("name","Agent"); textSize = 24f; gravity = Gravity.CENTER }
        face = FaceView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,360) }
        transcript = TextView(this).apply { textSize = 15f }
        val scroll = ScrollView(this).apply { addView(transcript); layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        input = EditText(this).apply { hint = "Talk to your agent…" }
        val row = LinearLayout(this)
        fun button(label:String, action:()->Unit) = Button(this).apply { text=label; setOnClickListener { action() } }
        row.addView(button("Send") { submit(input.text.toString()) }, LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("Talk") { ensureMicAndListen() }, LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("Settings") { showSettings(title) }, LinearLayout.LayoutParams(0,-2,1f))
        root.addView(title); root.addView(face); root.addView(scroll); root.addView(input); root.addView(row)
        setContentView(root)
    }

    private fun submit(text:String) {
        val q=text.trim(); if(q.isEmpty()) return
        input.setText("")
        val history=memory.recent()
        memory.append("user",q); refreshTranscript(); face.state=FaceView.State.THINKING
        lifecycleScope.launch {
            val answer=try { client.chat(
                prefs.getString("endpoint","https://api.openai.com/v1/chat/completions").orEmpty(),
                prefs.getString("apiKey","").orEmpty(),
                prefs.getString("model","gpt-4.1-mini").orEmpty(),
                prefs.getString("system","You are a capable personal Android agent with persistent local memory.").orEmpty(),
                history,q
            ) } catch(e:Exception) { "Connection error: ${e.message ?: "unknown error"}" }
            memory.append("assistant",answer); refreshTranscript(); speak(answer)
        }
    }

    private fun refreshTranscript() {
        transcript.text=memory.recent(40).joinToString("\n\n") { (r,t) -> (if(r=="user") "You" else prefs.getString("name","Agent")) + ": " + t }
    }

    private fun ensureMicAndListen() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if(!SpeechRecognizer.isRecognitionAvailable(this)) { Toast.makeText(this,"Speech recognition unavailable",Toast.LENGTH_SHORT).show(); return }
        recognizer?.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object:RecognitionListener {
            override fun onReadyForSpeech(params:Bundle?) { face.state=FaceView.State.LISTENING }
            override fun onResults(results:Bundle?) { face.state=FaceView.State.IDLE; results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::submit) }
            override fun onError(error:Int) { face.state=FaceView.State.IDLE }
            override fun onBeginningOfSpeech(){}; override fun onRmsChanged(rmsdB:Float){}; override fun onBufferReceived(buffer:ByteArray?){}; override fun onEndOfSpeech(){}; override fun onPartialResults(partialResults:Bundle?){}; override fun onEvent(eventType:Int,params:Bundle?){}
        })
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM) })
    }

    private fun speak(text:String) { face.state=FaceView.State.SPEAKING; tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"reply") }

    override fun onInit(status:Int) {
        if(status==TextToSpeech.SUCCESS) {
            tts?.language=Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId:String?) { runOnUiThread { face.state=FaceView.State.SPEAKING } }
                override fun onDone(utteranceId:String?) { runOnUiThread { face.state=FaceView.State.IDLE } }
                override fun onError(utteranceId:String?) { runOnUiThread { face.state=FaceView.State.IDLE } }
            })
        }
    }

    private fun showSettings(title:TextView) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,16,32,16) }
        fun field(label:String,key:String,def:String):EditText { box.addView(TextView(this).apply { text=label }); return EditText(this).apply { setText(prefs.getString(key,def)); box.addView(this) } }
        val name=field("Agent name","name","Agent")
        val endpoint=field("OpenAI-compatible endpoint","endpoint","https://api.openai.com/v1/chat/completions")
        val model=field("Model","model","gpt-4.1-mini")
        val key=field("API key","apiKey","")
        val system=field("Personality","system","You are a capable personal Android agent with persistent local memory.")
        AlertDialog.Builder(this).setTitle("Agent Settings").setView(box).setPositiveButton("Save") { _,_->
            prefs.edit().putString("name",name.text.toString()).putString("endpoint",endpoint.text.toString()).putString("model",model.text.toString()).putString("apiKey",key.text.toString()).putString("system",system.text.toString()).apply(); title.text=name.text.toString(); refreshTranscript()
        }.setNeutralButton("Clear memory") { _,_-> memory.clear(); refreshTranscript() }.setNegativeButton("Cancel",null).show()
    }

    override fun onDestroy() { recognizer?.destroy(); tts?.shutdown(); super.onDestroy() }
}
