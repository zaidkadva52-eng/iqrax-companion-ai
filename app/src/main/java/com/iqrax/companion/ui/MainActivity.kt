package com.iqrax.companion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.iqrax.companion.databinding.ActivityMainBinding
import com.iqrax.companion.operator.HumanPhoneOperator
import com.iqrax.companion.service.CompanionAccessibilityService
import com.iqrax.companion.service.FloatingOverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("iqrax_companion_prefs", Context.MODE_PRIVATE) }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateStatus()
        if (granted) {
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
            appendLog("Microphone permission granted.")
        } else {
            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        HumanPhoneOperator.init(this)
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
            appendLog("Opened Accessibility Settings.")
        }

        // 2. Overlay Permission Button
        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                appendLog("Opened Overlay Permission Settings.")
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Audio Permission Button
        binding.btnAudioPermission.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "Microphone permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. Save API Key
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            binding.etApiKey.setText(savedKey)
        }

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                prefs.edit().putString("gemini_api_key", key).apply()
                Toast.makeText(this, "Gemini API Key Saved!", Toast.LENGTH_SHORT).show()
                appendLog("Gemini API Key saved securely.")
            }
        }

        // 5. Toggle Floating Companion Orb
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
            appendLog("IqraX Floating Orb launched on screen!")
            Toast.makeText(this, "Floating Companion Orb Started!", Toast.LENGTH_SHORT).show()
        }

        // 6. Mic Trigger Button
        binding.btnMainMic.setOnClickListener {
            val intent = Intent(this, VoiceCommandActivity::class.java)
            startActivity(intent)
        }

        // 7. Quick Chips
        binding.chipMainWhatsApp.setOnClickListener {
            executeTask("WhatsApp mein ja ke Zaid ko ye message karo Salam")
        }
        binding.chipMainInsta.setOnClickListener {
            executeTask("Instagram pe ja ke is bande ko chat mein ye likh do Hello")
        }
        binding.chipMainScreen.setOnClickListener {
            executeTask("Screen par kya likha hai padho")
        }
        binding.chipMainYouTube.setOnClickListener {
            executeTask("YouTube open karo")
        }
        binding.chipMainHome.setOnClickListener {
            executeTask("Home screen")
        }

        // 8. Execute Manual Command
        binding.btnExecuteCommand.setOnClickListener {
            val command = binding.etTestCommand.text.toString().trim()
            if (command.isBlank()) {
                Toast.makeText(this, "Enter a task first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executeTask(command)
        }
    }

    private fun executeTask(command: String) {
        appendLog("Action: \"$command\"")
        HumanPhoneOperator.execute(
            context = applicationContext,
            commandText = command,
            onStatus = { status ->
                runOnUiThread { appendLog("Status: $status") }
            },
            onFinish = { success ->
                runOnUiThread {
                    appendLog(if (success) "Task completed." else "Task ended.")
                }
            }
        )
    }

    private fun updateStatus() {
        // Accessibility
        val isAccRunning = CompanionAccessibilityService.isRunning()
        binding.btnAccessibility.text = if (isAccRunning) "Active ✓" else "Enable"

        // Overlay
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        binding.btnOverlayPermission.text = if (hasOverlay) "Active ✓" else "Allow"

        // Audio
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        binding.btnAudioPermission.text = if (hasAudio) "Active ✓" else "Grant"
    }

    private fun appendLog(msg: String) {
        val current = binding.tvLogs.text.toString()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val updated = "[$timestamp] $msg\n$current"
        binding.tvLogs.text = if (updated.length > 1500) updated.take(1500) else updated
    }
}
