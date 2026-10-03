package com.shinigami.client.ui

import android.app.Activity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView

class DebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = "Application Crashed"

        val errorMessage = intent?.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "No error details available."
        val formattedTrace = formatStackTrace(errorMessage)

        setContentView(buildErrorView(formattedTrace))
    }

    private fun formatStackTrace(rawMessage: String): SpannableStringBuilder {
        val stringBuilder = SpannableStringBuilder()

        if (rawMessage.isBlank()) {
            stringBuilder.append("An unknown crash occurred.")
            return stringBuilder
        }

        val lines = rawMessage.lines()
        val errorType = lines.firstOrNull() ?: ""

        val friendlyMessage = errorExplanations[errorType]
        if (friendlyMessage != null) {
            stringBuilder.append(friendlyMessage).append("\n\n")
        }

        for (line in lines) {
            stringBuilder.append(line).append("\n")
        }

        return stringBuilder
    }

    private fun buildErrorView(message: SpannableStringBuilder): HorizontalScrollView {
        val textView = TextView(this).apply {
            text = message
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(32, 32, 32, 32)
        }

        val verticalScroll = ScrollView(this).apply {
            addView(textView)
        }

        return HorizontalScrollView(this).apply {
            addView(verticalScroll)
        }
    }

    companion object {
        const val EXTRA_ERROR_MESSAGE = "extra_crash_error"

        private val errorExplanations = mapOf(
            "java.lang.StringIndexOutOfBoundsException" to "A string operation accessed an invalid index.",
            "java.lang.IndexOutOfBoundsException" to "A list operation accessed an invalid index.",
            "java.lang.ArithmeticException" to "An invalid math operation was performed (e.g., division by zero).",
            "java.lang.NumberFormatException" to "Failed to convert a value to a number.",
            "android.content.ActivityNotFoundException" to "Failed to launch another application or screen.",
            "java.lang.NullPointerException" to "The application attempted to use an object that was not initialized.",
        )
    }
}
