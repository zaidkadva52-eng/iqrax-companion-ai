package com.iqrax.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.iqrax.companion.model.ScreenElement
import com.iqrax.companion.model.ScreenState
import com.iqrax.companion.model.SystemKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompanionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "IqraXAccessibility"
        private var instance: CompanionAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun getInstance(): CompanionAccessibilityService? = instance
    }

    private val _screenStateFlow = MutableStateFlow<ScreenState?>(null)
    val screenStateFlow: StateFlow<ScreenState?> = _screenStateFlow.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "IqraX Companion Accessibility Service Connected and Active!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rootNode = rootInActiveWindow ?: return
        try {
            val elements = mutableListOf<ScreenElement>()
            traverseNode(rootNode, elements)
            val pkg = event.packageName?.toString() ?: rootNode.packageName?.toString() ?: "unknown"
            _screenStateFlow.value = ScreenState(packageName = pkg, elements = elements)
        } catch (e: Exception) {
            Log.w(TAG, "Error traversing node hierarchy: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service destroyed")
    }

    // =========================================================================
    // SCREEN PARSING (THE "EYES")
    // =========================================================================
    fun captureCurrentScreen(): ScreenState? {
        val rootNode = rootInActiveWindow ?: return null
        val elements = mutableListOf<ScreenElement>()
        traverseNode(rootNode, elements)
        val pkg = rootNode.packageName?.toString() ?: "unknown"
        val state = ScreenState(packageName = pkg, elements = elements)
        _screenStateFlow.value = state
        return state
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, elements: MutableList<ScreenElement>) {
        if (node == null || !node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val isInteractive = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable

        if (hasText || isInteractive) {
            val element = ScreenElement(
                id = node.viewIdResourceName ?: "node_${elements.size}",
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                bounds = bounds,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isVisible = node.isVisibleToUser
            )
            elements.add(element)
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), elements)
        }
    }

    // =========================================================================
    // AUTO-TOUCH DISPATCHER (THE "HANDS")
    // =========================================================================

    /**
     * Taps at specific screen coordinates (x, y) programmatically
     */
    fun performClick(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Successfully clicked at ($x, $y)")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Click cancelled at ($x, $y)")
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Swipes from start coordinates to end coordinates
     */
    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed from ($startX, $startY) to ($endX, $endY)")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Types text into currently focused or editable input field
     */
    fun inputText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(rootNode)

        return if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } else {
            Log.w(TAG, "No editable input field found on screen")
            false
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    /**
     * Clicks an element matching text or description query
     */
    fun clickByText(query: String, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(query)
        val target = nodes.firstOrNull { it.isVisibleToUser }

        if (target != null) {
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            performClick(bounds.exactCenterX(), bounds.exactCenterY(), onComplete)
            return true
        }
        onComplete?.invoke(false)
        return false
    }

    /**
     * System Hardware Keys (Back, Home, Recents)
     */
    fun pressSystemKey(key: SystemKey): Boolean {
        return when (key) {
            SystemKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            SystemKey.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            SystemKey.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            SystemKey.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    /**
     * Finds and clicks an element by text OR content description
     */
    fun findAndClick(query: String, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val target = findNodeMatching(rootNode, query)
        if (target != null) {
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            performClick(bounds.exactCenterX(), bounds.exactCenterY(), onComplete)
            return true
        }
        onComplete?.invoke(false)
        return false
    }

    private fun findNodeMatching(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val q = query.lowercase()
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if ((text.contains(q) || desc.contains(q)) && (node.isClickable || node.parent?.isClickable == true)) {
            return if (node.isClickable) node else node.parent
        }
        for (i in 0 until node.childCount) {
            val found = findNodeMatching(node.getChild(i), query)
            if (found != null) return found
        }
        return null
    }

    /**
     * Extracts all visible text from current screen (like ALINA OCR screen reader)
     */
    fun getAllVisibleText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val textList = mutableListOf<String>()
        collectText(rootNode, textList)
        return textList.distinct().joinToString("\n")
    }

    private fun collectText(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrBlank()) list.add(t)
        if (!d.isNullOrBlank() && d != t) list.add(d)
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), list)
        }
    }

    /**
     * Launch an application by its package name
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package $packageName: ${e.message}")
            false
        }
    }
}
