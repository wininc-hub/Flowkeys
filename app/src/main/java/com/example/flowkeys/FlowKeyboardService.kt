package com.example.flowkeys

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
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

class FlowKeyboardService : InputMethodService() {

    private enum class State { IDLE, RECORDING, WORKING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var api: FlowApi

    private var state = State.IDLE
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recordStart = 0L
    private var job: Job? = null
    private var lastInserted: String? = null
    private var generation = 0

    private lateinit var status: TextView
    private lateinit var mic: ImageButton
    private lateinit var cancel: Button
    private lateinit var undo: Button

    override fun onCreate() {
        super.onCreate()
        api = FlowApi(Prefs.get(this))
    }

    // Keep the compact keyboard in landscape instead of a fullscreen editor.
    override fun onEvaluateFullscreenMode() = false

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        status = view.findViewById(R.id.status)
        mic = view.findViewById(R.id.mic)
        cancel = view.findViewById(R.id.cancel)
        undo = view.findViewById(R.id.undo)

        mic.setOnClickListener { haptic(it); onMicTapped() }
        cancel.setOnClickListener { haptic(it); cancelAll("Cancelled") }
        undo.setOnClickListener { haptic(it); undoLast() }

        view.findViewById<Button>(R.id.space).setOnClickListener {
            haptic(it); currentInputConnection?.commitText(" ", 1)
        }
        view.findViewById<Button>(R.id.enter).setOnClickListener { haptic(it); sendEnter() }

        view.findViewById<Button>(R.id.globe).apply {
            setOnClickListener { haptic(it); switchKeyboard() }
            setOnLongClickListener { inputMethodManager().showInputMethodPicker(); true }
        }

        // Backspace repeats while held.
        val backspace = view.findViewById<Button>(R.id.backspace)
        val repeat = object : Runnable {
            override fun run() {
                deleteOne()
                handler.postDelayed(this, 50)
            }
        }
        backspace.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; haptic(v); deleteOne()
                    handler.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }

        render()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) lastInserted = null
        if (::status.isInitialized) render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Leaving the text field while recording discards the take.
        if (state == State.RECORDING) cancelAll(null)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        releaseRecorder()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    // ---- Dictation flow ----

    private fun onMicTapped() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndProcess()
            State.WORKING -> Unit
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Allow the microphone in the FlowKeys app")
            openSettings()
            return
        }
        if (Prefs.get(this).str(Prefs.OPENAI_KEY).isEmpty()) {
            setStatus("Add your OpenAI key in the FlowKeys app")
            openSettings()
            return
        }

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
            setStatus("Couldn't start the microphone")
            return
        }

        recorder = r
        audioFile = file
        recordStart = SystemClock.elapsedRealtime()
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
        releaseRecorder()

        if (!ok || file == null || elapsed < MIN_RECORDING_MS) {
            file?.delete()
            state = State.IDLE
            render()
            setStatus("Didn't catch that. Tap and talk a bit longer.")
            return
        }

        state = State.WORKING
        render()
        val appPackage = currentInputEditorInfo?.packageName
        val fieldId = currentInputEditorInfo?.fieldId
        val gen = ++generation
        job = scope.launch {
            try {
                setStatus("Transcribing…")
                val raw = withContext(Dispatchers.IO) { api.transcribe(file) }
                if (raw.isBlank()) {
                    setStatus("Didn't hear any words")
                    return@launch
                }
                var text = raw
                if (Prefs.get(this@FlowKeyboardService).getBoolean(Prefs.CLEANUP, true)) {
                    setStatus("Polishing…")
                    text = withContext(Dispatchers.IO) {
                        runCatching { api.cleanup(raw, appPackage) }.getOrDefault(raw)
                    }
                }
                val info = currentInputEditorInfo
                val sameField = info != null && info.packageName == appPackage && info.fieldId == fieldId
                if (text.isBlank()) {
                    setStatus("Nothing to insert")
                } else if (sameField) {
                    insert(text)
                    setStatus("Inserted. Tap ↶ to undo.")
                } else {
                    // You moved to another field while it was working: don't type into the wrong place.
                    copyToClipboard(text)
                    setStatus("Copied to clipboard (you left the text field)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(e.message ?: "Something went wrong")
            } finally {
                file.delete()
                // A cancelled run can finish late; only reset if no newer dictation has started.
                if (gen == generation) {
                    state = State.IDLE
                    job = null
                    render()
                }
            }
        }
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
        state = State.IDLE
        if (::status.isInitialized) {
            render()
            if (message != null) setStatus(message)
        }
    }

    private fun releaseRecorder() {
        handler.removeCallbacks(meter)
        recorder?.let { r ->
            runCatching { r.reset() }
            r.release()
        }
        recorder = null
    }

    /** Pulses the mic with your voice and shows elapsed time. */
    private val meter = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
            val scale = 1f + (amp / 14_000f).coerceAtMost(0.3f)
            mic.animate().scaleX(scale).scaleY(scale).setDuration(70).start()
            val secs = (SystemClock.elapsedRealtime() - recordStart) / 1000
            status.text = "Listening… %d:%02d  ·  tap to finish".format(secs / 60, secs % 60)
            handler.postDelayed(this, 80)
        }
    }

    // ---- Editing ----

    private fun insert(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val prefix = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        val toInsert = prefix + text
        ic.commitText(toInsert, 1)
        lastInserted = toInsert
        render()
    }

    private fun undoLast() {
        val last = lastInserted ?: return
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(last.length, 0)?.toString() == last) {
            ic.deleteSurroundingText(last.length, 0)
            setStatus("Undone")
        } else {
            setStatus("Can't undo after editing")
        }
        lastInserted = null
        render()
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (info != null && !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun switchKeyboard() {
        val switched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToPreviousInputMethod()
        if (!switched) inputMethodManager().showInputMethodPicker()
    }

    // ---- UI ----

    private fun render() {
        if (!::mic.isInitialized) return
        when (state) {
            State.IDLE -> {
                mic.setBackgroundResource(R.drawable.bg_mic_idle)
                mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                mic.isEnabled = true
                cancel.visibility = View.INVISIBLE
            }
            State.RECORDING -> {
                mic.setBackgroundResource(R.drawable.bg_mic_rec)
                mic.isEnabled = true
                cancel.visibility = View.VISIBLE
            }
            State.WORKING -> {
                mic.setBackgroundResource(R.drawable.bg_mic_busy)
                mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                mic.isEnabled = false
                cancel.visibility = View.VISIBLE
            }
        }
        undo.isEnabled = lastInserted != null && state == State.IDLE
        undo.alpha = if (undo.isEnabled) 1f else 0.35f
    }

    private fun setStatus(text: String) {
        if (::status.isInitialized) status.text = text
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun inputMethodManager() =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    companion object {
        private const val MIN_RECORDING_MS = 600L
        private const val MAX_RECORDING_MS = 10 * 60 * 1000
    }
}
