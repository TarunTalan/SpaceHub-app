package com.example.myapplication.ui.auth

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentLoginBinding
import java.util.regex.Pattern

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val PASSWORD_PATTERN: Pattern = Pattern.compile(
        "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#\$%^&*])(?=\\S+$).{8,}$"
    )

    private val redColor by lazy { "#ED2828".toColorInt() }
    private val blueColor by lazy { "#2563EB".toColorInt() }
    private val grayColor by lazy { "#ADADAD".toColorInt() }
    private val emailIconDefault by lazy { ColorStateList.valueOf(grayColor) }
    private val passwordIconDefault by lazy { ColorStateList.valueOf("#C6CAD1".toColorInt()) }
    private val redStroke by lazy { ColorStateList.valueOf(redColor) }

    private var emailErrorLatched = false
    private var passwordErrorLatched = false

    private lateinit var emailTextDefault: ColorStateList
    private lateinit var passwordTextDefault: ColorStateList

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeDefaults()
        setupTextWatchers()
        setupClickListeners()
    }

    private fun initializeDefaults() {
        emailTextDefault = binding.etEmail.textColors
        passwordTextDefault = binding.etPassword.textColors

        // Disable built-in error handling
        binding.emailLayout.apply {
            isErrorEnabled = false
            isHelperTextEnabled = false
            errorIconDrawable = null
            setErrorTextColor(redStroke)
            setStartIconTintList(emailIconDefault)
        }

        binding.passwordLayout.apply {
            isErrorEnabled = false
            isHelperTextEnabled = false
            errorIconDrawable = null
            setErrorTextColor(redStroke)
            setStartIconTintList(passwordIconDefault)
            setEndIconTintList(passwordIconDefault)
        }

        binding.tvEmailError.visibility = View.INVISIBLE
        binding.ivEmailError.visibility = View.INVISIBLE
        binding.tvPasswordError.visibility = View.INVISIBLE
    }

    private fun setupTextWatchers() {
        binding.etEmail.addTextChangedListener(SimpleWatcher {
            if (emailErrorLatched) {
                emailErrorLatched = false
                clearEmailInvalidVisuals()
                binding.tvEmailError.visibility = View.INVISIBLE
                binding.ivEmailError.visibility = View.INVISIBLE
            }
        })

        binding.etPassword.addTextChangedListener(SimpleWatcher {
            if (passwordErrorLatched) {
                passwordErrorLatched = false
                clearPasswordInvalidVisuals()
                binding.tvPasswordError.visibility = View.INVISIBLE
            }
        })
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            val emailOk = validateEmail()
            val passOk = validatePassword()
            if (emailOk && passOk) {
                // Proceed with login
            }
        }

        // Forgot Password with underline and blue color on focus
        binding.tvForgotPassword.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus ->
                setTextColor(if (hasFocus) "#2563EB".toColorInt() else Color.WHITE)
            }
            setOnClickListener {
                requestFocus()
                // Navigate to forgot password screen
            }
        }

        // Sign-up link with underline
        binding.tvSignupLink.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                // Navigate to NameSignupFragment (first step of signup)
                findNavController().navigate(R.id.action_loginFragment_to_signupFragment)
            }
        }
    }

    private fun validateEmail(): Boolean {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val valid = email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()

        if (valid) {
            emailErrorLatched = false
            binding.tvEmailError.visibility = View.INVISIBLE
            binding.ivEmailError.visibility = View.INVISIBLE
            clearEmailInvalidVisuals()
        } else {
            emailErrorLatched = true
            val errorMsg = if (email.isEmpty()) "Email is required" else "Invalid email format"
            binding.tvEmailError.text = errorMsg
            binding.tvEmailError.visibility = View.VISIBLE
            applyEmailInvalidVisuals()
        }
        return valid
    }

    private fun validatePassword(): Boolean {
        val pwd = binding.etPassword.text?.toString()?.trim().orEmpty()

        if (pwd.isEmpty()) {
            passwordErrorLatched = true
            binding.tvPasswordError.text = "Password is required"
            binding.tvPasswordError.visibility = View.VISIBLE
            applyPasswordInvalidVisuals()
            return false
        }

//        val missingRequirements = mutableListOf<String>()
//
//        if (pwd.length < 8) {
//            missingRequirements.add("8 characters")
//        } else if (!pwd.matches(Regex(".*[0-9].*"))) {
//            missingRequirements.add("number")
//        } else if (!pwd.matches(Regex(".*[a-z].*"))) {
//            missingRequirements.add("lowercase letter")
//        } else if (!pwd.matches(Regex(".*[A-Z].*"))) {
//            missingRequirements.add("uppercase letter")
//        }
//        if (missingRequirements.isNotEmpty()) {
//            passwordErrorLatched = true
//            binding.tvPasswordError.text = "Missing: ${missingRequirements.joinToString(", ")}"
//            binding.tvPasswordError.visibility = View.VISIBLE
//            applyPasswordInvalidVisuals()
//            return false
//        }

        // Password is valid
        passwordErrorLatched = false
        binding.tvPasswordError.visibility = View.INVISIBLE
        clearPasswordInvalidVisuals()
        return true
    }


    private fun applyEmailInvalidVisuals() {
        binding.emailLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(redColor, redColor)
            )
        )
        binding.ivEmailError.visibility = View.VISIBLE
        binding.emailLayout.setStartIconTintList(redStroke)
        binding.etEmail.setTextColor(redColor)
    }

    private fun clearEmailInvalidVisuals() {
        binding.emailLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(blueColor, grayColor)
            )
        )
        binding.emailLayout.setStartIconTintList(emailIconDefault)
        binding.etEmail.setTextColor(emailTextDefault)
    }

    private fun applyPasswordInvalidVisuals() {
        binding.passwordLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(redColor, redColor)
            )
        )
        binding.passwordLayout.setStartIconTintList(redStroke)
        binding.passwordLayout.setEndIconTintList(redStroke)
        binding.etPassword.setTextColor(redColor)
    }

    private fun clearPasswordInvalidVisuals() {
        binding.passwordLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(blueColor, grayColor)
            )
        )
        binding.passwordLayout.setStartIconTintList(passwordIconDefault)
        binding.passwordLayout.setEndIconTintList(passwordIconDefault)
        binding.etPassword.setTextColor(passwordTextDefault)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SimpleWatcher(private val onChange: () -> Unit) : TextWatcher {
    private var previousText = ""

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        previousText = s?.toString() ?: ""
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        val currentText = s?.toString() ?: ""
        if (previousText != currentText) {
            onChange()
        }
    }

    override fun afterTextChanged(s: Editable?) {}
}
