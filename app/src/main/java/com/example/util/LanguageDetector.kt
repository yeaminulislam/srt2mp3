package com.example.util

data class DetectedLanguage(
    val code: String,          // e.g. "en"
    val name: String,          // e.g. "English"
    val nativeName: String,    // e.g. "English"
    val confidence: Float,     // 0.0 to 1.0
    val scriptName: String     // e.g. "Latin"
)

object LanguageDetector {

    /**
     * Always returns English as requested.
     */
    fun detectLanguage(text: String): DetectedLanguage {
        return defaultEnglish()
    }

    private fun defaultEnglish() = DetectedLanguage(
        code = "en",
        name = "English",
        nativeName = "English",
        confidence = 1.0f,
        scriptName = "Latin"
    )
}
