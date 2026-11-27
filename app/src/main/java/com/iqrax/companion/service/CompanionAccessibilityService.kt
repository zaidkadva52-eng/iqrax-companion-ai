package com.iqrax.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.iqrax.companion.model.ScreenElement
import com.iqrax.companion.model.ScreenState
import com.iqrax.companion.model.SystemKey
import kotlinx.coroutines.delay
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

        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else null

        val hasText = !node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank() ||
                !hint.isNullOrBlank()
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
                isVisible = node.isVisibleToUser,
                isPassword = node.isPassword,
                hintText = hint
            )
            elements.add(element)
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), elements)
        }
    }

    // =========================================================================
    // LOW-LEVEL GESTURE DISPATCHER (THE "HANDS")
    // =========================================================================

    /**
     * Physical finger tap simulation at exact screen coordinates (x, y)
     */
    fun performClick(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Touch click dispatched at ($x, $y)")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Touch click cancelled at ($x, $y)")
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Swipes between two points
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
                Log.d(TAG, "Swipe completed ($startX, $startY) -> ($endX, $endY)")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Taps the screen center of any node using gesture coordinates
     */
    fun clickNode(node: AccessibilityNodeInfo?, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        if (node == null) {
            onComplete?.invoke(false)
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        performClick(x, y, onComplete)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return true
    }

    // =========================================================================
    // INTELLIGENT NODE SEARCH & MATCHING
    // =========================================================================

    fun findNodeMatching(query: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return searchNodeRecursive(rootNode, query.trim().lowercase())
    }

    private fun searchNodeRecursive(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (query.isEmpty()) return null

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.lowercase() ?: ""
        } else ""

        if (text.contains(query) || desc.contains(query) || viewId.contains(query) || hint.contains(query)) {
            // Check if there is an explicit clickable parent (common for list items or icon wrappers)
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) return current
                current = current.parent
            }
            return node
        }

        for (i in 0 until node.childCount) {
            val found = searchNodeRecursive(node.getChild(i), query)
            if (found != null) return found
        }
        return null
    }

    fun findAndClick(query: String, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        val target = findNodeMatching(query)
        if (target != null) {
            return clickNode(target, onComplete)
        }
        onComplete?.invoke(false)
        return false
    }

    fun clickByText(query: String, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        return findAndClick(query, onComplete)
    }

    fun findAndClickAny(queries: List<String>, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        for (q in queries) {
            if (findAndClick(q, onComplete)) {
                return true
            }
        }
        onComplete?.invoke(false)
        return false
    }

    // =========================================================================
    // TEXT INPUT & FORM FILLING (EMAIL, PASSWORD, ETC.)
    // =========================================================================

    /**
     * Types text into a specific node by tapping it, focusing it, setting text, or pasting
     */
    fun typeIntoNode(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false

        // 1. Gesture tap to activate keyboard & cursor
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        performClick(bounds.exactCenterX(), bounds.exactCenterY())

        // 2. Request accessibility focus
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // 3. Set text via ACTION_SET_TEXT
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (success) return true

        // 4. Fallback: Copy to Android clipboard and paste
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("IqraX_Input", text)
            clipboard?.setPrimaryClip(clip)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard paste fallback error: ${e.message}")
            false
        }
    }

    /**
     * Types into current focused field or first editable field
     */
    fun inputText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val target = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(rootNode)
        return typeIntoNode(target, text)
    }

    /**
     * Types into a field matching a specific hint or query label
     */
    fun typeIntoField(hintOrQuery: String, text: String): Boolean {
        val node = findEditableField(listOf(hintOrQuery)) ?: findNodeMatching(hintOrQuery)
        return typeIntoNode(node, text)
    }

    fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds an editable field matching any of the given hints (e.g. "email", "username")
     */
    fun findEditableField(hints: List<String>): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return searchEditableMatching(rootNode, hints.map { it.lowercase() })
    }

    private fun searchEditableMatching(node: AccessibilityNodeInfo?, hints: List<String>): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable) {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.lowercase() ?: ""
            } else ""

            for (h in hints) {
                if (text.contains(h) || desc.contains(h) || viewId.contains(h) || hint.contains(h)) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val found = searchEditableMatching(node.getChild(i), hints)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds a password field by isPassword flag or hints
     */
    fun findPasswordField(): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return searchPasswordRecursive(rootNode)
    }

    private fun searchPasswordRecursive(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable && node.isPassword) return node

        if (node.isEditable) {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.lowercase() ?: ""
            } else ""

            val passHints = listOf("password", "passcode", "pin", "secret")
            if (passHints.any { text.contains(it) || desc.contains(it) || viewId.contains(it) || hint.contains(it) }) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val found = searchPasswordRecursive(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // =========================================================================
    // SYSTEM NAVIGATION & OCR
    // =========================================================================

    fun pressSystemKey(key: SystemKey): Boolean {
        return when (key) {
            SystemKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            SystemKey.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            SystemKey.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            SystemKey.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

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
            Log.e(TAG, "Failed to launch $packageName: ${e.message}")
            false
        }
    }
}
