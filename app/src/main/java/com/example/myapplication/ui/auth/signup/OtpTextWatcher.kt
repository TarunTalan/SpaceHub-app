package com.example.myapplication.ui.auth.signup

import android.text.Editable
import android.text.TextWatcher

/**
 * Reusable TextWatcher for OTP inputs. Calls callbacks for empty/valid/invalid/typing states.
 */
class OtpTextWatcher(
    private val onEmpty: () -> Unit = {},
    private val onValid: () -> Unit = {},
    private val onInvalid: () -> Unit = {},
    private val onTyping: () -> Unit = {}
) : TextWatcher {

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        val otp = s?.toString()?.trim().orEmpty()
        if (otp.isEmpty()) {
            onEmpty()
        } else if (otp.length == 6 && otp.matches(Regex("^[0-9]{6}$"))) {
            onValid()
        } else {
            onInvalid()
        }
        if (otp.isNotEmpty()) onTyping()
    }

    override fun afterTextChanged(s: Editable?) {}
}

