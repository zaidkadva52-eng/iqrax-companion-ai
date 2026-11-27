package com.iqrax.companion.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.iqrax.companion.R
import com.iqrax.companion.agent.GeminiAgentBrain
import com.iqrax.companion.operator.HumanPhoneOperator
import com.iqrax.companion.ui.VoiceCommandActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class FloatingOverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID = "iqrax_companion_overlay_channel"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_START = "ACTION_START_OVERLAY"
        const val ACTION_STOP = "ACTION_STOP_OVERLAY"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var tts: TextToSpeech? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agentBrain: GeminiAgentBrain? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        tts = TextToSpeech(this, this)
        initAgentBrain()
        createFloatingOverlay()
    }

    private fun initAgentBrain() {
        // Read API key from SharedPreferences or environment
        val prefs = getSharedPreferences("iqrax_companion_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isNotBlank()) {
            agentBrain = GeminiAgentBrain(apiKey)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // IqraX Official Brand Circular Floating Orb
        val frame = FrameLayout(this).apply {
            setPadding(12, 12, 12, 12)
        }

        val orb = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(150, 150)
            background = ContextCompat.getDrawable(this@FloatingOverlayService, R.drawable.bg_orb_glowing)
            setImageResource(R.drawable.ic_launcher)
            setPadding(10, 10, 10, 10)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        frame.addView(orb)

        frame.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = initialX + (event.rawX - initialTouchX).toInt()
                    p.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(frame, p)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        onOrbTapped()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = frame
        windowManager?.addView(frame, params)
    }

    private fun onOrbTapped() {
        try {
            val intent = Intent(this, VoiceCommandActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            speak("Voice assistant khol rahi hoon")
        }
    }

    fun executeUserCommand(command: String) {
        HumanPhoneOperator.execute(this, command)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "iqrax_speech_${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("hi", "IN")
            tts?.setPitch(1.05f)
            tts?.setSpeechRate(1.0f)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IqraX Companion Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active status of IqraX Companion"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IqraX Companion Active")
            .setContentText("Autonomous phone assistant is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        floatingView?.let { windowManager?.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
