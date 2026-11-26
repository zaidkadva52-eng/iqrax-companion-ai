package com.iqrax.companion.agent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.iqrax.companion.model.AgentAction
import com.iqrax.companion.model.ScreenState
import com.iqrax.companion.model.SystemKey
import com.iqrax.companion.persona.EmotionalCompanionPersona
import com.iqrax.companion.service.CompanionAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiAgentBrain(
    private val apiKey: String
) {
    companion object {
        private const val TAG = "IqraXAgentBrain"
        private const val GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Executes an autonomous goal over a multi-step observe-think-act loop
     */
    suspend fun executeGoal(
        userGoal: String,
        onStatusUpdate: (String) -> Unit,
        onSpeechResponse: (text: String, tone: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val service = CompanionAccessibilityService.getInstance()
        if (service == null) {
            onStatusUpdate("Accessibility Service is not enabled. Please enable it in Settings.")
            return@withContext false
        }

        onStatusUpdate("Understanding task: \"$userGoal\"…")
        var currentStep = 0
        val maxSteps = 12

        while (currentStep < maxSteps) {
            currentStep++
            delay(1200) // Brief stabilization wait between screen transitions

            val screen = service.captureCurrentScreen()
            if (screen == null) {
                onStatusUpdate("Capturing screen state…")
                delay(800)
                continue
            }

            onStatusUpdate("Step $currentStep: Analyzing screen (${screen.packageName})…")

            val decision = planNextAction(userGoal, screen, currentStep)
            Log.d(TAG, "Agent Decision: $decision")

            when (decision) {
                is AgentAction.ClickCoordinate -> {
                    onStatusUpdate("Tapping screen at (${decision.x.toInt()}, ${decision.y.toInt()})…")
                    service.performClick(decision.x, decision.y)
                }
                is AgentAction.ClickElement -> {
                    onStatusUpdate("Clicking on \"${decision.query}\"…")
                    val clicked = service.clickByText(decision.query)
                    if (!clicked) {
                        Log.w(TAG, "Element text not found directly: ${decision.query}")
                    }
                }
                is AgentAction.InputText -> {
                    onStatusUpdate("Typing text: \"${decision.text}\"…")
                    service.inputText(decision.text)
                }
                is AgentAction.Swipe -> {
                    onStatusUpdate("Scrolling screen…")
                    service.performSwipe(decision.startX, decision.startY, decision.endX, decision.endY, decision.durationMs)
                }
                is AgentAction.PressSystemKey -> {
                    onStatusUpdate("Pressing ${decision.key}…")
                    service.pressSystemKey(decision.key)
                }
                is AgentAction.LaunchApp -> {
                    onStatusUpdate("Launching app ${decision.packageName}…")
                    service.launchApp(decision.packageName)
                }
                is AgentAction.SpeakFeedback -> {
                    onSpeechResponse(decision.message, decision.emotionTone)
                }
                is AgentAction.CompleteTask -> {
                    onStatusUpdate("Task Complete: ${decision.summary}")
                    onSpeechResponse(decision.summary, "warm")
                    return@withContext true
                }
                is AgentAction.FailTask -> {
                    onStatusUpdate("Unable to complete: ${decision.reason}")
                    onSpeechResponse("Mujhe yeh karne me dikkat aayi: ${decision.reason}", "empathic")
                    return@withContext false
                }
            }
        }

        onStatusUpdate("Task timed out after $maxSteps steps.")
        false
    }

    private fun planNextAction(goal: String, screen: ScreenState, stepNumber: Int): AgentAction {
        try {
            val payload = buildPromptPayload(goal, screen, stepNumber)
            val request = Request.Builder()
                .url("$GEMINI_API_URL?key=$apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error ${response.code}: $responseBody")
                return AgentAction.FailTask("Gemini API error code: ${response.code}")
            }

            return parseModelAction(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Error planning action: ${e.message}")
            return AgentAction.FailTask("Network error: ${e.message}")
        }
    }

    private fun buildPromptPayload(goal: String, screen: ScreenState, stepNumber: Int): String {
        val root = JsonObject()
        val contents = JsonArray()
        val content = JsonObject()
        val parts = JsonArray()

        val promptText = StringBuilder()
        promptText.append(EmotionalCompanionPersona.SYSTEM_PROMPT)
        promptText.append("\n\n=== USER GOAL ===\n\"$goal\"\n\n")
        promptText.append("=== CURRENT SCREEN STATE (Step $stepNumber) ===\n")
        promptText.append("Active Package: ${screen.packageName}\n")
        promptText.append("Visible Elements (${screen.elements.size} found):\n")

        screen.elements.take(35).forEachIndexed { index, el ->
            promptText.append("[$index] text=\"${el.text ?: el.contentDescription ?: ""}\" id=\"${el.id}\" center=(${el.bounds.centerX()}, ${el.bounds.centerY()}) bounds=[${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}]\n")
        }

        promptText.append("\nReturn ONLY a single valid JSON object representing the next action to perform:")
        promptText.append("""
{
  "action": "click_coordinate" | "click_text" | "type_text" | "swipe" | "press_key" | "launch_app" | "complete" | "fail",
  "x": float,
  "y": float,
  "query": "string",
  "text": "string",
  "packageName": "string",
  "key": "BACK" | "HOME" | "RECENTS",
  "message": "Spoken feedback to user with emotion"
}
        """.trimIndent())

        val part = JsonObject().apply { addProperty("text", promptText.toString()) }
        parts.add(part)
        content.add("parts", parts)
        contents.add(content)
        root.add("contents", contents)

        val genConfig = JsonObject().apply {
            addProperty("temperature", 0.1)
            addProperty("responseMimeType", "application/json")
        }
        root.add("generationConfig", genConfig)

        return gson.toJson(root)
    }

    private fun parseModelAction(jsonResponse: String): AgentAction {
        try {
            val rootObj = gson.fromJson(jsonResponse, JsonObject::class.java)
            val candidates = rootObj.getAsJsonArray("candidates")
            val text = candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject
                .get("text").asString

            val actionObj = gson.fromJson(text, JsonObject::class.java)
            val actionType = actionObj.get("action")?.asString?.lowercase() ?: "complete"

            return when (actionType) {
                "click_coordinate" -> {
                    val x = actionObj.get("x")?.asFloat ?: 500f
                    val y = actionObj.get("y")?.asFloat ?: 1000f
                    AgentAction.ClickCoordinate(x, y)
                }
                "click_text" -> {
                    val query = actionObj.get("query")?.asString ?: ""
                    AgentAction.ClickElement(query)
                }
                "type_text" -> {
                    val str = actionObj.get("text")?.asString ?: ""
                    AgentAction.InputText(str)
                }
                "swipe" -> {
                    val startX = actionObj.get("startX")?.asFloat ?: 500f
                    val startY = actionObj.get("startY")?.asFloat ?: 1500f
                    val endX = actionObj.get("endX")?.asFloat ?: 500f
                    val endY = actionObj.get("endY")?.asFloat ?: 500f
                    AgentAction.Swipe(startX, startY, endX, endY)
                }
                "press_key" -> {
                    val keyName = actionObj.get("key")?.asString?.uppercase() ?: "BACK"
                    val key = when (keyName) {
                        "HOME" -> SystemKey.HOME
                        "RECENTS" -> SystemKey.RECENTS
                        else -> SystemKey.BACK
                    }
                    AgentAction.PressSystemKey(key)
                }
                "launch_app" -> {
                    val pkg = actionObj.get("packageName")?.asString ?: "com.google.android.youtube"
                    AgentAction.LaunchApp(pkg)
                }
                "complete" -> {
                    val msg = actionObj.get("message")?.asString ?: "Task has been completed."
                    AgentAction.CompleteTask(msg)
                }
                else -> {
                    val reason = actionObj.get("message")?.asString ?: "Goal reached"
                    AgentAction.CompleteTask(reason)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse model response: ${e.message}")
            return AgentAction.CompleteTask("Completed step.")
        }
    }
}
