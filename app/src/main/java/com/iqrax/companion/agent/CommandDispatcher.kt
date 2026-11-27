package com.iqrax.companion.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.iqrax.companion.model.SystemKey
import com.iqrax.companion.service.CompanionAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

object CommandDispatcher {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    fun initTTS(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    try {
                        tts?.language = Locale("hi", "IN")
                    } catch (e: Exception) {
                        tts?.language = Locale.getDefault()
                    }
                }
            }
        }
    }

    fun speak(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iqrax_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            // Silently ignore if TTS is unavailable
        }
    }

    /**
     * Executes either a direct instant intent or passes to GeminiAgentBrain
     */
    fun dispatch(
        context: Context,
        rawCommand: String,
        onLog: ((String) -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        initTTS(context)
        val lower = command.lowercase(Locale.ROOT)
        val accessibility = CompanionAccessibilityService.getInstance()

        // 1. Direct Instant Actions (work instantly and reliably)
        when {
            // YouTube
            lower.contains("youtube") -> {
                speak("YouTube khol rahi hoon")
                onLog?.invoke("Launching YouTube…")
                val launched = accessibility?.launchApp("com.google.android.youtube") ?: false
                if (!launched) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (ex: Exception) {
                            Toast.makeText(context, "Cannot open YouTube", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                onComplete?.invoke(true)
                return
            }

            // WhatsApp
            lower.contains("whatsapp") -> {
                speak("WhatsApp open kar rahi hoon")
                onLog?.invoke("Launching WhatsApp…")
                val launched = accessibility?.launchApp("com.whatsapp") ?: false
                if (!launched) {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cannot open WhatsApp", Toast.LENGTH_SHORT).show()
                    }
                }
                onComplete?.invoke(true)
                return
            }

            // Camera
            lower.contains("camera") || lower.contains("photo") -> {
                speak("Camera open kar rahi hoon")
                onLog?.invoke("Opening Camera…")
                try {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot open Camera", Toast.LENGTH_SHORT).show()
                }
                onComplete?.invoke(true)
                return
            }

            // Settings
            lower.contains("setting") -> {
                speak("Phone Settings open kar rahi hoon")
                onLog?.invoke("Opening Settings…")
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot open Settings", Toast.LENGTH_SHORT).show()
                }
                onComplete?.invoke(true)
                return
            }

            // Home Key
            lower.contains("home") || lower.contains("ghar") -> {
                speak("Home screen par jaa rahe hain")
                onLog?.invoke("Pressing Home…")
                val res = accessibility?.pressSystemKey(SystemKey.HOME) ?: false
                onComplete?.invoke(res)
                return
            }

            // Back Key
            lower.contains("back") || lower.contains("piche") -> {
                speak("Back jaa rahe hain")
                onLog?.invoke("Pressing Back…")
                val res = accessibility?.pressSystemKey(SystemKey.BACK) ?: false
                onComplete?.invoke(res)
                return
            }

            // Scroll Down
            lower.contains("scroll down") || lower.contains("neeche") -> {
                speak("Neeche scroll kar rahi hoon")
                onLog?.invoke("Scrolling down…")
                accessibility?.performSwipe(500f, 1400f, 500f, 400f, 350)
                onComplete?.invoke(true)
                return
            }

            // Scroll Up
            lower.contains("scroll up") || lower.contains("upar") -> {
                speak("Upar scroll kar rahi hoon")
                onLog?.invoke("Scrolling up…")
                accessibility?.performSwipe(500f, 400f, 500f, 1400f, 350)
                onComplete?.invoke(true)
                return
            }
        }

        // 2. Autonomous Reasoning via Gemini 2.0 Flash
        val prefs = context.getSharedPreferences("iqrax_companion_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isBlank()) {
            speak("Is task ke liye settings me Gemini API key save karein.")
            onLog?.invoke("Gemini API Key missing. Please save key in app settings.")
            Toast.makeText(context, "Please set Gemini API Key in IqraX Companion app", Toast.LENGTH_LONG).show()
            onComplete?.invoke(false)
            return
        }

        if (accessibility == null) {
            speak("Accessibility service band hai. Kripya pehle use on karein.")
            onLog?.invoke("Accessibility Service is not connected!")
            onComplete?.invoke(false)
            return
        }

        speak("Bilkul, main abhi karti hoon.")
        onLog?.invoke("Autonomous Task Started: \"$command\"…")

        CoroutineScope(Dispatchers.Main).launch {
            val brain = GeminiAgentBrain(apiKey)
            val success = brain.executeGoal(
                userGoal = command,
                onStatusUpdate = { status ->
                    onLog?.invoke(status)
                },
                onSpeechResponse = { text, _ ->
                    speak(text)
                }
            )
            onComplete?.invoke(success)
        }
    }
}
