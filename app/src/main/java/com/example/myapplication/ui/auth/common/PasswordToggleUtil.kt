package com.example.myapplication.ui.auth.common

import android.text.method.PasswordTransformationMethod
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.example.myapplication.R

object PasswordToggleUtil {
    fun attach(
        passwordLayout: TextInputLayout,
        passwordEditText: TextInputEditText,
        openIconRes: Int = R.drawable.eye_open_icon,
        closedIconRes: Int = R.drawable.eye_off_icon
    ) {
        // Use default Android password masking (stable, IME-safe)
        val mask = PasswordTransformationMethod.getInstance()

        // Control the end icon to guarantee mapping: closed=masked, open=visible
        passwordLayout.endIconMode = TextInputLayout.END_ICON_CUSTOM
        passwordLayout.setEndIconDrawable(closedIconRes)
        passwordLayout.isEndIconVisible = true

        // Apply default mask immediately
        passwordEditText.transformationMethod = mask
        passwordEditText.setSelection(passwordEditText.text?.length ?: 0)

        passwordLayout.setEndIconOnClickListener {
            val isVisible = passwordEditText.transformationMethod == null
            if (isVisible) {
                // Hide -> show closed eye and default dots
                passwordEditText.transformationMethod = mask
                passwordLayout.setEndIconDrawable(closedIconRes)
            } else {
                // Show -> show open eye and plain text
                passwordEditText.transformationMethod = null
                passwordLayout.setEndIconDrawable(openIconRes)
            }
            // Keep cursor at end
            passwordEditText.setSelection(passwordEditText.text?.length ?: 0)
        }
    }
}
