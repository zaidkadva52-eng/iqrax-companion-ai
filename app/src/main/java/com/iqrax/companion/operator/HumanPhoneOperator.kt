package com.iqrax.companion.operator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.iqrax.companion.agent.GeminiAgentBrain
import com.iqrax.companion.model.SystemKey
import com.iqrax.companion.service.CompanionAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Locale

/**
 * Autonomous Android Phone Action Engine
 * Handles user voice requests to navigate, send messages, and execute tasks on device.
 */
object HumanPhoneOperator {

    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
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
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phone_op_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            // TTS fallback
        }
    }

    fun execute(
        context: Context,
        commandText: String,
        onStatus: (String) -> Unit = {},
        onFinish: (Boolean) -> Unit = {}
    ) {
        val trimmed = commandText.trim()
        if (trimmed.isEmpty()) {
            onFinish(false)
            return
        }

        init(context)
        scope.launch {
            val success = processCommand(context, trimmed, onStatus)
            onFinish(success)
        }
    }

    private suspend fun processCommand(
        context: Context,
        command: String,
        onStatus: (String) -> Unit
    ): Boolean {
        val lower = command.lowercase(Locale.ROOT)
        val accessibility = CompanionAccessibilityService.getInstance()

        // 1. WhatsApp Messaging
        if (lower.contains("whatsapp")) {
            speak("WhatsApp open kar rahi hoon")
            onStatus("Opening WhatsApp…")
            val directMsg = extractMessage(command)
            val contact = extractTargetName(command, "whatsapp")

            if (directMsg.isNotBlank()) {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, directMsg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(sendIntent)
                    delay(1500)
                    if (contact.isNotBlank() && accessibility != null) {
                        accessibility.findAndClick(contact)
                        delay(600)
                        accessibility.findAndClick("Send")
                    }
                    return true
                } catch (e: Exception) {
                    // Fall back to direct app launch
                }
            }

            val launched = accessibility?.launchApp("com.whatsapp") ?: false
            if (!launched) {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                    return false
                }
            }

            if (accessibility != null && contact.isNotBlank()) {
                delay(1800)
                accessibility.findAndClick("Search")
                delay(600)
                accessibility.inputText(contact)
                delay(1000)
                accessibility.findAndClick(contact)
                if (directMsg.isNotBlank()) {
                    delay(1000)
                    accessibility.inputText(directMsg)
                    delay(500)
                    accessibility.findAndClick("Send")
                }
            }
            return true
        }

        // 2. Instagram Messaging
        if (lower.contains("instagram") || lower.contains("insta")) {
            speak("Instagram open kar rahi hoon")
            onStatus("Opening Instagram…")
            val target = extractTargetName(command, "instagram")
            val msg = extractMessage(command)

            val launched = accessibility?.launchApp("com.instagram.android") ?: false
            if (!launched) {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Instagram not installed", Toast.LENGTH_SHORT).show()
                    return false
                }
            }

            if (accessibility != null) {
                delay(2000)
                accessibility.findAndClick("Direct") || accessibility.findAndClick("Message") || accessibility.findAndClick("Chat")
                if (target.isNotBlank()) {
                    delay(1000)
                    accessibility.findAndClick("Search")
                    delay(600)
                    accessibility.inputText(target)
                    delay(1000)
                    accessibility.findAndClick(target)
                    if (msg.isNotBlank()) {
                        delay(1000)
                        accessibility.inputText(msg)
                        delay(500)
                        accessibility.findAndClick("Send")
                    }
                }
            }
            return true
        }

        // 3. Screen Text Reader
        if (lower.contains("screen") && (lower.contains("read") || lower.contains("padho") || lower.contains("likha"))) {
            if (accessibility == null) {
                speak("Accessibility service off hai")
                return false
            }
            speak("Screen read kar rahi hoon")
            onStatus("Extracting visible screen text…")
            val text = accessibility.getAllVisibleText()
            if (text.isNotBlank()) {
                val preview = if (text.length > 200) text.take(200) + "..." else text
                speak("Screen text: $preview")
                onStatus(preview)
            } else {
                speak("Screen par text nahi mila")
            }
            return true
        }

        // 4. YouTube
        if (lower.contains("youtube")) {
            speak("YouTube open kar rahi hoon")
            onStatus("Opening YouTube…")
            val query = lower.replace("youtube", "").replace("pe", "").replace("par", "").replace("search", "").replace("kholo", "").replace("chalao", "").trim()
            if (query.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                accessibility?.launchApp("com.google.android.youtube") ?: context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
            return true
        }

        // 5. System Keys
        if (lower.contains("home") || lower.contains("ghar")) {
            speak("Home screen")
            onStatus("Pressing Home…")
            return accessibility?.pressSystemKey(SystemKey.HOME) ?: false
        }
        if (lower.contains("back") || lower.contains("piche")) {
            speak("Back")
            onStatus("Pressing Back…")
            return accessibility?.pressSystemKey(SystemKey.BACK) ?: false
        }
        if (lower.contains("scroll down") || lower.contains("neeche")) {
            speak("Scrolling down")
            onStatus("Scrolling…")
            accessibility?.performSwipe(500f, 1400f, 500f, 400f, 350)
            return true
        }
        if (lower.contains("scroll up") || lower.contains("upar")) {
            speak("Scrolling up")
            onStatus("Scrolling…")
            accessibility?.performSwipe(500f, 400f, 500f, 1400f, 350)
            return true
        }
        if (lower.contains("camera")) {
            speak("Camera")
            context.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return true
        }
        if (lower.contains("setting")) {
            speak("Settings")
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return true
        }

        // 6. Autonomous Gemini 2.0 Reasoning
        val prefs = context.getSharedPreferences("iqrax_companion_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isNotBlank() && accessibility != null) {
            speak("Bilkul, main abhi karti hoon.")
            onStatus("Autonomous reasoning on device…")
            val brain = GeminiAgentBrain(apiKey)
            return brain.executeGoal(
                userGoal = command,
                onStatusUpdate = { onStatus(it) },
                onSpeechResponse = { text, _ -> speak(text) }
            )
        } else {
            speak("Task perform kar rahi hoon")
            return accessibility?.findAndClick(command) ?: false
        }
    }

    private fun extractTargetName(raw: String, appKeyword: String): String {
        val low = raw.lowercase()
        val appIdx = low.indexOf(appKeyword)
        val candidate = if (appIdx != -1) raw.substring(appIdx + appKeyword.length) else raw
        val parts = candidate.split(" ")
        for (p in parts) {
            val clean = p.trim().replace("ko", "").replace("pe", "").replace("me", "").replace("mein", "")
            if (clean.length > 2 && !clean.equals("message", true) && !clean.equals("chat", true) && !clean.equals("karo", true) && !clean.equals("bhejo", true)) {
                return clean
            }
        }
        return ""
    }

    private fun extractMessage(raw: String): String {
        val markers = listOf("message karo", "message bhejo", "likh do", "likho", "bolo", "chat me")
        val low = raw.lowercase()
        for (m in markers) {
            val idx = low.indexOf(m)
            if (idx != -1) {
                return raw.substring(idx + m.length).trim()
            }
        }
        return ""
    }
}
