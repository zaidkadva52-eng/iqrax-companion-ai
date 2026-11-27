package com.iqrax.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.iqrax.companion.R
import com.iqrax.companion.operator.HumanPhoneOperator
import java.util.Locale

class VoiceCommandActivity : AppCompatActivity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var etManual: EditText
    private lateinit var btnMic: ImageView
    private var isListening = false

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSpeechListening()
        } else {
            tvStatus.text = "Microphone permission zaroori hai"
            Toast.makeText(this, "Audio permission needed for voice commands", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_command)

        tvStatus = findViewById(R.id.tvVoiceStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        etManual = findViewById(R.id.etManualCommand)
        btnMic = findViewById(R.id.btnMicTrigger)

        setupButtons()
        checkPermissionAndListen()
    }

    private fun setupButtons() {
        findViewById<View>(R.id.btnCloseVoice).setOnClickListener { finish() }
        findViewById<View>(R.id.rootVoiceContainer).setOnClickListener { finish() }
        findViewById<View>(R.id.cardVoice).setOnClickListener { /* prevent dismiss */ }

        btnMic.setOnClickListener {
            if (isListening) {
                stopSpeechListening()
            } else {
                checkPermissionAndListen()
            }
        }

        findViewById<Button>(R.id.btnSendManual).setOnClickListener {
            val text = etManual.text.toString().trim()
            if (text.isNotEmpty()) {
                executeTask(text)
            }
        }

        // Quick action chips
        findViewById<View>(R.id.chipYouTube)?.setOnClickListener { executeTask("Open YouTube") }
        findViewById<View>(R.id.chipWhatsApp)?.setOnClickListener { executeTask("Open WhatsApp") }
        findViewById<View>(R.id.chipHome)?.setOnClickListener { executeTask("Home") }
        findViewById<View>(R.id.chipScrollDown)?.setOnClickListener { executeTask("Scroll down") }
        findViewById<View>(R.id.chipBack)?.setOnClickListener { executeTask("Back") }
    }

    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSpeechListening()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeechListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fallbackIntentRecognition()
            return
        }

        stopSpeechListening()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye...")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                tvStatus.text = "Sun rahi hoon... Boliye"
            }
            override fun onBeginningOfSpeech() {
                tvStatus.text = "Listening…"
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                tvStatus.text = "Processing…"
            }
            override fun onError(error: Int) {
                isListening = false
                tvStatus.text = "Tap mic to speak again"
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spoken = matches[0]
                    tvTranscript.text = spoken
                    executeTask(spoken)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    tvTranscript.text = matches[0]
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun fallbackIntentRecognition() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye...")
            }
            speechIntentLauncher.launch(intent)
        } catch (e: Exception) {
            tvStatus.text = "Please type command below"
        }
    }

    private val speechIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spoken = matches[0]
                tvTranscript.text = spoken
                executeTask(spoken)
            }
        }
    }

    private fun stopSpeechListening() {
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun executeTask(command: String) {
        tvStatus.text = "Executing: $command"
        HumanPhoneOperator.execute(
            context = applicationContext,
            commandText = command,
            onStatus = { status ->
                runOnUiThread { tvStatus.text = status }
            },
            onFinish = {
                Handler(Looper.getMainLooper()).postDelayed({
                    finish()
                }, 800)
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpeechListening()
    }
}
