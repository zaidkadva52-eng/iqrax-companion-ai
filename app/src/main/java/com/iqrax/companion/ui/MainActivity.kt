package com.iqrax.companion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iqrax.companion.agent.GeminiAgentBrain
import com.iqrax.companion.databinding.ActivityMainBinding
import com.iqrax.companion.service.CompanionAccessibilityService
import com.iqrax.companion.service.FloatingOverlayService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("iqrax_companion_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupListeners() {
        // 1. Accessibility Service Button
        binding.btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable 'IqraX Companion' in the list", Toast.LENGTH_LONG).show()
        }

        // 2. Overlay Permission Button
        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Save API Key
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            binding.etApiKey.setText(savedKey)
        }

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                prefs.edit().putString("gemini_api_key", key).apply()
                Toast.makeText(this, "Gemini API Key Saved!", Toast.LENGTH_SHORT).show()
                appendLog("API Key updated and stored securely.")
            }
        }

        // 4. Toggle Floating Companion Orb
        binding.btnToggleOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Overlay permission first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            appendLog("Floating Companion Orb launched on screen!")
            Toast.makeText(this, "IqraX Companion Orb Started!", Toast.LENGTH_SHORT).show()
        }

        // 5. Test Command
        binding.btnExecuteCommand.setOnClickListener {
            val command = binding.etTestCommand.text.toString().trim()
            val apiKey = prefs.getString("gemini_api_key", "") ?: ""

            if (apiKey.isBlank()) {
                Toast.makeText(this, "Please enter and save your Gemini API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (command.isBlank()) {
                Toast.makeText(this, "Enter a task first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!CompanionAccessibilityService.isRunning()) {
                Toast.makeText(this, "Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            appendLog("Starting autonomous action: \"$command\"…")
            val brain = GeminiAgentBrain(apiKey)

            lifecycleScope.launch {
                brain.executeGoal(
                    userGoal = command,
                    onStatusUpdate = { status ->
                        runOnUiThread { appendLog("Agent: $status") }
                    },
                    onSpeechResponse = { response, tone ->
                        runOnUiThread { appendLog("AI Voice ($tone): \"$response\"") }
                    }
                )
            }
        }
    }

    private fun updateStatus() {
        val isA11y = CompanionAccessibilityService.isRunning()
        val isOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

        binding.btnAccessibility.text = if (isA11y) "Accessibility: ACTIVE (Ready)" else "Enable Accessibility Service"
        binding.btnOverlayPermission.text = if (isOverlay) "Overlay: GRANTED (Ready)" else "Grant Overlay Permission"
    }

    private fun appendLog(line: String) {
        val current = binding.tvLogs.text.toString()
        binding.tvLogs.text = "$current\n> $line"
    }
}
