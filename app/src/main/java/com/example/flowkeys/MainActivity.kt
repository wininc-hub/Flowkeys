package com.example.flowkeys

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var alwaysShow: Switch
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
        alwaysShow = findViewById(R.id.alwaysShow)
        setupStatus = findViewById(R.id.setupStatus)

        val prefs = Prefs.get(this)
        openaiKey.setText(prefs.str(Prefs.OPENAI_KEY))
        anthropicKey.setText(prefs.str(Prefs.ANTHROPIC_KEY))
        sttModel.setText(prefs.str(Prefs.STT_MODEL, Prefs.DEFAULT_STT_MODEL))
        llmModel.setText(prefs.str(Prefs.LLM_MODEL, Prefs.DEFAULT_LLM_MODEL))
        dictionary.setText(prefs.str(Prefs.DICTIONARY))
        cleanup.isChecked = prefs.getBoolean(Prefs.CLEANUP, true)
        alwaysShow.isChecked = prefs.getBoolean(Prefs.ALWAYS_SHOW, false)

        findViewById<Button>(R.id.btnMic).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
            .putBoolean(Prefs.ALWAYS_SHOW, alwaysShow.isChecked)
            .apply()
    }

    private fun refreshStatus() {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val a11yOn = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { it.startsWith("$packageName/") } == true
        val keyOk = Prefs.get(this).str(Prefs.OPENAI_KEY).isNotEmpty()

        fun line(ok: Boolean, text: String) = (if (ok) "✅  " else "⬜  ") + text
        setupStatus.text = listOf(
            line(micOk, "Microphone allowed"),
            line(a11yOn, "Floating bubble turned on"),
            line(keyOk, "OpenAI key saved"),
        ).joinToString("\n")
    }

    companion object {
        private const val REQ_MIC = 1
    }
}
