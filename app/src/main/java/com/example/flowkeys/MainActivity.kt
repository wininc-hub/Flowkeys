package com.example.flowkeys

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.flowkeys.Prefs.str

class MainActivity : Activity() {

    private lateinit var openaiKey: EditText
    private lateinit var anthropicKey: EditText
    private lateinit var sttModel: EditText
    private lateinit var llmModel: EditText
    private lateinit var dictionary: EditText
    private lateinit var cleanup: Switch
    private lateinit var setupStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openaiKey = findViewById(R.id.openaiKey)
        anthropicKey = findViewById(R.id.anthropicKey)
        sttModel = findViewById(R.id.sttModel)
        llmModel = findViewById(R.id.llmModel)
        dictionary = findViewById(R.id.dictionary)
        cleanup = findViewById(R.id.cleanup)
        setupStatus = findViewById(R.id.setupStatus)

        val prefs = Prefs.get(this)
        openaiKey.setText(prefs.str(Prefs.OPENAI_KEY))
        anthropicKey.setText(prefs.str(Prefs.ANTHROPIC_KEY))
        sttModel.setText(prefs.str(Prefs.STT_MODEL, Prefs.DEFAULT_STT_MODEL))
        llmModel.setText(prefs.str(Prefs.LLM_MODEL, Prefs.DEFAULT_LLM_MODEL))
        dictionary.setText(prefs.str(Prefs.DICTIONARY))
        cleanup.isChecked = prefs.getBoolean(Prefs.CLEANUP, true)

        findViewById<Button>(R.id.btnMic).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnPick).setOnClickListener {
            imm().showInputMethodPicker()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog; refresh after it closes.
        if (hasFocus) refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun save() {
        Prefs.get(this).edit()
            .putString(Prefs.OPENAI_KEY, openaiKey.text.toString().trim())
            .putString(Prefs.ANTHROPIC_KEY, anthropicKey.text.toString().trim())
            .putString(Prefs.STT_MODEL, sttModel.text.toString().trim())
            .putString(Prefs.LLM_MODEL, llmModel.text.toString().trim())
            .putString(Prefs.DICTIONARY, dictionary.text.toString().trim())
            .putBoolean(Prefs.CLEANUP, cleanup.isChecked)
            .apply()
    }

    private fun refreshStatus() {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val enabled = imm().enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true

        fun line(ok: Boolean, text: String) = (if (ok) "✅  " else "⬜  ") + text
        setupStatus.text = listOf(
            line(micOk, "Microphone allowed"),
            line(enabled, "Keyboard turned on"),
            line(selected, "FlowKeys is the current keyboard"),
        ).joinToString("\n")
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    companion object {
        private const val REQ_MIC = 1
    }
}
