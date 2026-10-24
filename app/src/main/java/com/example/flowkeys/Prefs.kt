package com.example.flowkeys

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val OPENAI_KEY = "openai_key"
    const val ANTHROPIC_KEY = "anthropic_key"
    const val STT_MODEL = "stt_model"
    const val LLM_MODEL = "llm_model"
    const val CLEANUP = "cleanup"
    const val DICTIONARY = "dictionary"
    const val ALWAYS_SHOW = "always_show"
    const val BUBBLE_X = "bubble_x"
    const val BUBBLE_Y = "bubble_y"

    const val DEFAULT_STT_MODEL = "gpt-4o-mini-transcribe"
    const val DEFAULT_LLM_MODEL = "claude-haiku-5-5"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("flowkeys", Context.MODE_PRIVATE)

    fun SharedPreferences.str(key: String, default: String = ""): String =
        getString(key, null)?.trim().takeUnless { it.isNullOrEmpty() } ?: default
}
