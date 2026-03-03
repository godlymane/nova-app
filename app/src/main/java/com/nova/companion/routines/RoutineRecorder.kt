package com.nova.companion.routines

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records user actions during teach-by-demonstration mode.
 *
 * When teaching is active, accessibility events are routed here.
 * Each event is translated into a RecordedStep and added to the recording.
 * Rapid-fire events (e.g. multiple TYPE_VIEW_TEXT_CHANGED per keystroke)
 * are deduplicated into a single "type" step.
 */
object RoutineRecorder {

    private const val TAG = "RoutineRecorder"

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _steps = mutableListOf<RecordedStep>()
    private var routineName: String = ""
    private var lastTextChangeTime: Long = 0
    private var pendingTypedText: String = ""
    private var pendingTextPackage: String = ""

    // Debounce: ignore events within this window after the last one of same type
    private const val TEXT_DEBOUNCE_MS = 800L

    fun startRecording(name: String) {
        routineName = name
        _steps.clear()
        lastTextChangeTime = 0
        pendingTypedText = ""
        pendingTextPackage = ""
        _isRecording.value = true
        Log.i(TAG, "Started recording routine: $name")
    }

    fun stopRecording(): List<RecordedStep> {
        // Flush any pending text input
        flushPendingText()
        _isRecording.value = false
        val result = _steps.toList()
        Log.i(TAG, "Stopped recording. Captured ${result.size} steps for: $routineName")
        return result
    }

    fun getRoutineName(): String = routineName

    /**
     * Called from NovaAccessibilityService.onAccessibilityEvent() when recording is active.
     */
    fun onEvent(event: AccessibilityEvent) {
        if (!_isRecording.value) return

        val pkg = event.packageName?.toString() ?: return
        // Skip our own app's events
        if (pkg == "com.nova.companion") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Flush any pending text before recording a tap
                flushPendingText()
                recordTap(event, pkg)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                recordTextChange(event, pkg)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                flushPendingText()
                recordScroll(event, pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                flushPendingText()
                recordAppSwitch(event, pkg)
            }
        }
    }

    private fun recordTap(event: AccessibilityEvent, pkg: String) {
        val text = event.text.joinToString(" ")
        val desc = event.contentDescription?.toString() ?: ""
        val cls = event.className?.toString() ?: ""

        // Skip taps with no identifiable target
        if (text.isBlank() && desc.isBlank()) return

        val step = RecordedStep(
            action = "tap",
            packageName = pkg,
            targetText = text,
            targetDesc = desc,
            targetClass = cls
        )
        _steps.add(step)
        Log.d(TAG, "Recorded tap: text='$text' desc='$desc' in $pkg")
    }

    private fun recordTextChange(event: AccessibilityEvent, pkg: String) {
        val now = System.currentTimeMillis()
        val newText = event.text.joinToString("")
        if (newText.isBlank()) return

        // Debounce: accumulate text changes into a single step
        if (now - lastTextChangeTime < TEXT_DEBOUNCE_MS && pendingTextPackage == pkg) {
            // Update the pending text with latest value
            pendingTypedText = newText
        } else {
            // Flush previous pending text and start new accumulation
            flushPendingText()
            pendingTypedText = newText
            pendingTextPackage = pkg
        }
        lastTextChangeTime = now
    }

    private fun recordScroll(event: AccessibilityEvent, pkg: String) {
        // Determine scroll direction from scroll delta
        val direction = if (event.scrollY > 0) "down" else "up"

        val step = RecordedStep(
            action = "scroll",
            packageName = pkg,
            scrollDirection = direction
        )
        _steps.add(step)
        Log.d(TAG, "Recorded scroll: $direction in $pkg")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun recordAppSwitch(event: AccessibilityEvent, pkg: String) {
        // Only record if this is a different app than the last step
        val lastPkg = _steps.lastOrNull()?.packageName ?: ""
        if (pkg != lastPkg && pkg.contains(".")) {
            val step = RecordedStep(
                action = "open_app",
                packageName = pkg
            )
            _steps.add(step)
            Log.d(TAG, "Recorded app switch to: $pkg")
        }
    }

    private fun flushPendingText() {
        if (pendingTypedText.isNotBlank()) {
            val step = RecordedStep(
                action = "type",
                packageName = pendingTextPackage,
                typedText = pendingTypedText
            )
            _steps.add(step)
            Log.d(TAG, "Recorded type: '${pendingTypedText.take(30)}...' in $pendingTextPackage")
            pendingTypedText = ""
            pendingTextPackage = ""
        }
    }
}
