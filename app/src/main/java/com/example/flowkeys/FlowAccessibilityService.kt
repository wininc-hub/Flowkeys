package com.example.flowkeys

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.flowkeys.Prefs.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Floating dictation bubble. Lives on top of every app (including next to Gboard),
 * records on tap, and types the result into whichever text field has focus.
 */
class FlowAccessibilityService : AccessibilityService() {

    private enum class State { IDLE, RECORDING, WORKING }

    private data class Target(val pkg: String?, val cls: String?, val viewId: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var api: FlowApi
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

    private lateinit var bubbleRoot: FrameLayout
    private lateinit var bubble: ImageView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleShown = false

    private lateinit var statusView: TextView
    private lateinit var statusParams: WindowManager.LayoutParams
    private var statusShown = false

    private var state = State.IDLE
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recordStart = 0L
    private var heardSound = false
    private var job: Job? = null
    private var generation = 0
    private var target: Target? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleRefresh() }

    // ---- Service lifecycle ----

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs.get(this)
        api = FlowApi(prefs)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildBubble()
        buildStatus()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scheduleRefresh()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        releaseRecorder()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (::wm.isInitialized) {
            setBubbleShown(false)
            removeStatus()
        }
        super.onDestroy()
    }

    // ---- Bubble ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildBubble() {
        bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.bg_mic_idle)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = dp(6).toFloat()
            contentDescription = "FlowKeys dictation"
        }
        // Extra room around the bubble so the voice-level pulse isn't clipped.
        bubbleRoot = FrameLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(bubble, FrameLayout.LayoutParams(dp(56), dp(56)))
        }

        val dm = resources.displayMetrics
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable: tapping the bubble must never pull focus out of the text field.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.BUBBLE_X, dm.widthPixels - dp(72))
            y = prefs.getInt(Prefs.BUBBLE_Y, dm.heightPixels / 2)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        bubbleRoot.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            var longPressed = false
            val longPress = Runnable {
                if (!dragging) {
                    longPressed = true
                    bubbleRoot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onBubbleLongPress()
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = bubbleParams.x; startY = bubbleParams.y
                        dragging = false; longPressed = false
                        handler.postDelayed(longPress, 600)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && hypot(dx, dy) > slop) {
                            dragging = true
                            handler.removeCallbacks(longPress)
                        }
                        if (dragging) {
                            bubbleParams.x = (startX + dx).toInt()
                            bubbleParams.y = (startY + dy).toInt()
                            updateBubble()
                            positionStatus()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPress)
                        if (dragging) snapToEdgeAndSave()
                        else if (!longPressed) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onBubbleTapped()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
                }
                return true
            }
        })
    }

    private fun buildStatus() {
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = dp(280)
            maxLines = 3
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xEE26262B.toInt())
            }
        }
        statusParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun setBubbleShown(want: Boolean) {
        if (want && !bubbleShown) {
            clampBubble()
            bubbleShown = runCatching { wm.addView(bubbleRoot, bubbleParams) }.isSuccess
        } else if (!want && bubbleShown) {
            runCatching { wm.removeView(bubbleRoot) }
            bubbleShown = false
        }
    }

    private fun updateBubble() {
        if (bubbleShown) runCatching { wm.updateViewLayout(bubbleRoot, bubbleParams) }
    }

    private fun clampBubble() {
        val dm = resources.displayMetrics
        val size = dp(72)
        bubbleParams.x = bubbleParams.x.coerceIn(0, max(0, dm.widthPixels - size))
        bubbleParams.y = bubbleParams.y.coerceIn(0, max(0, dm.heightPixels - size))
    }

    private fun snapToEdgeAndSave() {
        val dm = resources.displayMetrics
        val size = dp(72)
        clampBubble()
        bubbleParams.x = if (bubbleParams.x + size / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - size
        updateBubble()
        positionStatus()
        prefs.edit().putInt(Prefs.BUBBLE_X, bubbleParams.x).putInt(Prefs.BUBBLE_Y, bubbleParams.y).apply()
    }

    // ---- Status pill ----

    private val clearStatus = Runnable { removeStatus() }

    private fun showStatus(text: String) {
        handler.removeCallbacks(clearStatus)
        statusView.text = text
        if (!statusShown) statusShown = runCatching { wm.addView(statusView, statusParams) }.isSuccess
        positionStatus()
    }

    /** Shows a message that disappears on its own. */
    private fun flash(text: String) {
        showStatus(text)
        handler.postDelayed(clearStatus, 3500)
    }

    private fun removeStatus() {
        handler.removeCallbacks(clearStatus)
        if (statusShown) {
            runCatching { wm.removeView(statusView) }
            statusShown = false
        }
    }

    private fun positionStatus() {
        if (!statusShown) return
        val dm = resources.displayMetrics
        statusView.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels - dp(24), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED
        )
        val w = statusView.measuredWidth
        val h = statusView.measuredHeight
        val centerX = bubbleParams.x + dp(36)
        statusParams.x = (centerX - w / 2).coerceIn(dp(12), max(dp(12), dm.widthPixels - w - dp(12)))
        val above = bubbleParams.y + dp(8) - h - dp(8)
        statusParams.y = if (above >= dp(24)) above else bubbleParams.y + dp(64) + dp(8)
        runCatching { wm.updateViewLayout(statusView, statusParams) }
    }

    // ---- Visibility: show the bubble only while a text field is focused ----

    private val refresh = Runnable {
        val want = prefs.getBoolean(Prefs.ALWAYS_SHOW, false) ||
            state != State.IDLE ||
            focusedEditable() != null
        setBubbleShown(want)
    }

    private fun scheduleRefresh() {
        if (!::prefs.isInitialized) return
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 150)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return focused.takeIf { it.isEditable }
    }

    private fun signature(n: AccessibilityNodeInfo) =
        Target(n.packageName?.toString(), n.className?.toString(), n.viewIdResourceName)

    // ---- Dictation flow ----

    private fun onBubbleTapped() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndProcess()
            State.WORKING -> Unit
        }
    }

    private fun onBubbleLongPress() {
        if (state != State.IDLE) {
            cancelAll("Cancelled")
        } else {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            flash("Open FlowKeys and allow the microphone")
            return
        }
        if (prefs.str(Prefs.OPENAI_KEY).isEmpty()) {
            flash("Add your OpenAI key in the FlowKeys app")
            return
        }
        val node = focusedEditable()
        if (node == null) {
            flash("Tap a text field first")
            return
        }
        if (node.isPassword) {
            flash("FlowKeys skips password fields")
            return
        }
        target = signature(node)

        val file = File(cacheDir, "dictation-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(48_000)
            r.setMaxDuration(MAX_RECORDING_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    handler.post { if (state == State.RECORDING) stopAndProcess() }
                }
            }
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            flash("Couldn't start the microphone")
            return
        }

        recorder = r
        audioFile = file
        recordStart = SystemClock.elapsedRealtime()
        heardSound = false
        state = State.RECORDING
        render()
        handler.post(meter)
    }

    private fun stopAndProcess() {
        val file = audioFile
        val elapsed = SystemClock.elapsedRealtime() - recordStart
        val ok = try {
            recorder?.stop(); true
        } catch (e: RuntimeException) {
            false // stop() throws if almost nothing was recorded
        }
        val heard = heardSound
        releaseRecorder()

        if (!ok || file == null || elapsed < MIN_RECORDING_MS) {
            file?.delete()
            finishIdle("Didn't catch that. Tap and talk a bit longer.")
            return
        }
        if (!heard) {
            file.delete()
            finishIdle("No audio captured. Android may be blocking the mic in the background.")
            return
        }

        state = State.WORKING
        render()
        val appPackage = target?.pkg
        val expected = target
        val gen = ++generation
        job = scope.launch {
            try {
                showStatus("Transcribing…")
                val raw = withContext(Dispatchers.IO) { api.transcribe(file) }
                if (raw.isBlank()) {
                    flash("Didn't hear any words")
                    return@launch
                }
                var text = raw
                if (prefs.getBoolean(Prefs.CLEANUP, true)) {
                    showStatus("Polishing…")
                    text = withContext(Dispatchers.IO) {
                        runCatching { api.cleanup(raw, appPackage) }.getOrDefault(raw)
                    }
                }
                deliver(text, expected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flash(e.message ?: "Something went wrong")
            } finally {
                file.delete()
                // A cancelled run can finish late; only reset if no newer dictation has started.
                if (gen == generation) {
                    state = State.IDLE
                    job = null
                    render()
                    scheduleRefresh()
                }
            }
        }
    }

    /** Types the text into the same field it was dictated for; otherwise puts it on the clipboard. */
    private fun deliver(text: String, expected: Target?) {
        if (text.isBlank()) {
            flash("Nothing to insert")
            return
        }
        val node = focusedEditable()
        if (node != null && !node.isPassword && signature(node) == expected && insertInto(node, text)) {
            flash("Inserted")
        } else {
            copyToClipboard(text)
            flash("Copied. Paste it where you want it.")
        }
    }

    private fun insertInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0) {
            start = current.length; end = current.length
        }
        val lo = min(start, end).coerceIn(0, current.length)
        val hi = max(start, end).coerceIn(lo, current.length)
        val before = current.substring(0, lo)
        val after = current.substring(hi)
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val inserted = before + prefix + text

        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inserted + after)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            val caret = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, inserted.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, inserted.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caret)
            return true
        }
        // Some apps (web views, custom editors) reject SET_TEXT; paste works in most of them.
        copyToClipboard(prefix + text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    private fun cancelAll(message: String?) {
        generation++
        job?.cancel()
        job = null
        releaseRecorder()
        audioFile?.delete()
        finishIdle(message)
    }

    private fun finishIdle(message: String?) {
        state = State.IDLE
        render()
        if (message != null) flash(message) else removeStatus()
        scheduleRefresh()
    }

    private fun releaseRecorder() {
        handler.removeCallbacks(meter)
        recorder?.let { r ->
            runCatching { r.reset() }
            r.release()
        }
        recorder = null
    }

    /** Pulses the bubble with your voice and shows elapsed time. */
    private val meter = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
            if (amp > 0) heardSound = true
            val scale = 1f + (amp / 14_000f).coerceAtMost(0.3f)
            bubble.animate().scaleX(scale).scaleY(scale).setDuration(70).start()
            val secs = (SystemClock.elapsedRealtime() - recordStart) / 1000
            showStatus("Listening %d:%02d · tap to finish · hold to cancel".format(secs / 60, secs % 60))
            handler.postDelayed(this, 80)
        }
    }

    private fun render() {
        when (state) {
            State.IDLE -> {
                bubble.setBackgroundResource(R.drawable.bg_mic_idle)
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            State.RECORDING -> bubble.setBackgroundResource(R.drawable.bg_mic_rec)
            State.WORKING -> {
                bubble.setBackgroundResource(R.drawable.bg_mic_busy)
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
        }
        // setBackgroundResource resets padding on older Android versions.
        bubble.setPadding(dp(15), dp(15), dp(15), dp(15))
    }

    companion object {
        private const val MIN_RECORDING_MS = 600L
        private const val MAX_RECORDING_MS = 10 * 60 * 1000
    }
}
