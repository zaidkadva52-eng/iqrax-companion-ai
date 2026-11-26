package com.iqrax.companion.model

import android.graphics.Rect

data class ScreenElement(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isVisible: Boolean
) {
    val centerPoint: Pair<Float, Float>
        get() = Pair(bounds.exactCenterX(), bounds.exactCenterY())

    fun matchesText(query: String): Boolean {
        return (text?.contains(query, ignoreCase = true) == true) ||
                (contentDescription?.contains(query, ignoreCase = true) == true)
    }
}

data class ScreenState(
    val packageName: String,
    val elements: List<ScreenElement>,
    val timestamp: Long = System.currentTimeMillis()
)

enum class SystemKey {
    BACK, HOME, RECENTS, NOTIFICATIONS
}

sealed class AgentAction {
    data class ClickCoordinate(val x: Float, val y: Float) : AgentAction()
    data class ClickElement(val query: String) : AgentAction()
    data class InputText(val text: String) : AgentAction()
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val durationMs: Long = 300) : AgentAction()
    data class PressSystemKey(val key: SystemKey) : AgentAction()
    data class LaunchApp(val packageName: String) : AgentAction()
    data class SpeakFeedback(val message: String, val emotionTone: String = "caring") : AgentAction()
    data class CompleteTask(val summary: String) : AgentAction()
    data class FailTask(val reason: String) : AgentAction()
}
