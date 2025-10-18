package com.example.myapplication.ui.auth.common

import android.content.res.ColorStateList
import android.view.View
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout

object InputValidationHelper {

    private val focusStates = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf())

    private fun boxStrokeColorList(primary: Int, secondary: Int) =
        ColorStateList(focusStates, intArrayOf(primary, secondary))

    fun applyInvalid(
        layout: TextInputLayout,
        editText: EditText? = null,
        ivError: View? = null,
        redColor: Int,
        redStroke: ColorStateList
    ) {
        layout.setBoxStrokeColorStateList(boxStrokeColorList(redColor, redColor))
        ivError?.visibility = View.VISIBLE
        layout.setStartIconTintList(redStroke)
        try {
            layout.setEndIconTintList(redStroke)
        } catch (_: Throwable) { /* ignore if layout has no end icon */ }
        editText?.setTextColor(redColor)
    }

    fun clearInvalid(
        layout: TextInputLayout,
        editText: EditText? = null,
        ivError: View? = null,
        startIconDefault: ColorStateList? = null,
        endIconDefault: ColorStateList? = null,
        textDefault: ColorStateList? = null,
        blueColor: Int,
        grayColor: Int
    ) {
        layout.setBoxStrokeColorStateList(boxStrokeColorList(blueColor, grayColor))
        ivError?.visibility = View.INVISIBLE
        startIconDefault?.let { layout.setStartIconTintList(it) }
        endIconDefault?.let { layout.setEndIconTintList(it) }
        textDefault?.let { editText?.setTextColor(it) }
    }

    fun applyEmailInvalid(
        emailLayout: TextInputLayout,
        etEmail: EditText,
        ivEmailError: View?,
        redColor: Int,
        redStroke: ColorStateList
    ) = applyInvalid(emailLayout, etEmail, ivEmailError, redColor, redStroke)

    fun clearEmailInvalid(
        emailLayout: TextInputLayout,
        etEmail: EditText,
        ivEmailError: View?,
        emailIconDefault: ColorStateList,
        emailTextDefault: ColorStateList,
        blueColor: Int,
        grayColor: Int
    ) = clearInvalid(emailLayout, etEmail, ivEmailError, emailIconDefault, null, emailTextDefault, blueColor, grayColor)

    fun applyPasswordInvalid(
        passwordLayout: TextInputLayout,
        etPassword: EditText,
        redColor: Int,
        redStroke: ColorStateList
    ) = applyInvalid(passwordLayout, etPassword, null, redColor, redStroke)

    fun clearPasswordInvalid(
        passwordLayout: TextInputLayout,
        etPassword: EditText,
        passwordIconDefault: ColorStateList,
        passwordTextDefault: ColorStateList,
        blueColor: Int,
        grayColor: Int
    ) = clearInvalid(passwordLayout, etPassword, null, passwordIconDefault, passwordIconDefault, passwordTextDefault, blueColor, grayColor)

    // Helpers for plain EditText (not wrapped by TextInputLayout) to set background drawable and text color
    fun applyEditTextInvalid(editText: EditText, redColor: Int, errorBackgroundRes: Int) {
        try {
            editText.setTextColor(redColor)
            editText.setBackgroundResource(errorBackgroundRes)
        } catch (_: Throwable) { }
    }

    fun clearEditTextInvalid(editText: EditText, textDefault: ColorStateList, normalBackgroundRes: Int) {
        try {
            editText.setTextColor(textDefault)
            editText.setBackgroundResource(normalBackgroundRes)
        } catch (_: Throwable) {  }
    }

}
