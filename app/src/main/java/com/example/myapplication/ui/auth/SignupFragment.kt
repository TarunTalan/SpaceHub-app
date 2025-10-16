package com.example.myapplication.ui.auth

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentSignupBinding
import com.example.myapplication.ui.util.PasswordToggleUtil

/**
 * Signup screen - SECOND STEP where users enter email and password.
 */
class SignupFragment : Fragment(R.layout.fragment_signup) {

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    private val redColor by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }
    private val blueColor by lazy { ContextCompat.getColor(requireContext(), R.color.primary_blue) }
    private val grayColor by lazy { ContextCompat.getColor(requireContext(), R.color.gray_medium) }
    private val grayLightColor by lazy { ContextCompat.getColor(requireContext(), R.color.gray_light) }
    private val emailIconDefault by lazy { ColorStateList.valueOf(grayColor) }
    private val passwordIconDefault by lazy { ColorStateList.valueOf(grayLightColor) }
    private val redStroke by lazy { ColorStateList.valueOf(redColor) }

    private lateinit var emailTextDefault: ColorStateList
    private lateinit var passwordTextDefault: ColorStateList

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSignupBinding.bind(view)

        // Keep default window IME behavior; do not change softInputMode here.
        ViewCompat.requestApplyInsets(binding.root)

        initializeDefaults()
        setupTextWatchers()
        setupClickListeners()
        setupKeyboardDismiss()
    }

    private fun initializeDefaults() {
        emailTextDefault = binding.etEmail.textColors
        passwordTextDefault = binding.etPassword.textColors

        // Ensure eye behavior: closed = masked, open = visible on both fields
        PasswordToggleUtil.attach(binding.passwordLayout, binding.etPassword)
        PasswordToggleUtil.attach(binding.confirmPasswordLayout, binding.etConfirmPassword)

        // Disable built-in error handling for custom error display
        binding.emailLayout.apply {
            isErrorEnabled = false
            isHelperTextEnabled = false
            errorIconDrawable = null
        }

        binding.passwordLayout.apply {
            isErrorEnabled = false
            isHelperTextEnabled = false
            errorIconDrawable = null
        }

        binding.confirmPasswordLayout.apply {
            isErrorEnabled = false
            isHelperTextEnabled = false
            errorIconDrawable = null
        }
    }

    private fun setupTextWatchers() {
        // Email field text watcher - clear errors when user starts typing
        binding.etEmail.addTextChangedListener(object : TextWatcher {
            private var previousText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previousText = s?.toString() ?: ""
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val currentText = s?.toString() ?: ""
                if (previousText != currentText && binding.tvEmailError.isVisible) {
                    hideEmailError()
                }
            }
        })

        // Password field text watcher - clear errors when user starts typing
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            private var previousText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previousText = s?.toString() ?: ""
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val currentText = s?.toString() ?: ""
                if (previousText != currentText && binding.tvPasswordError.isVisible) {
                    hidePasswordError()
                }
            }
        })

        // Confirm password field text watcher - clear errors when user starts typing
        binding.etConfirmPassword.addTextChangedListener(object : TextWatcher {
            private var previousText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previousText = s?.toString() ?: ""
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val currentText = s?.toString() ?: ""
                if (previousText != currentText && binding.tvPasswordError.isVisible) {
                    hidePasswordError()
                }
            }
        })
    }

    private fun setupClickListeners() {
        // Complete signup - navigate to OTP verification
        binding.btnSignup.setOnClickListener {
            if (validateInput()) {
                findNavController().navigate(R.id.action_signupFragment_to_signupVerificationFragment)
            }
        }

        // Already have account - navigate to login with underline
        binding.tvLoginLink.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                findNavController().navigate(R.id.action_signupFragment_to_loginFragment)
            }
        }
    }

    private fun setupKeyboardDismiss() {
        // Hide keyboard when tapping the background container
        binding.root.setOnClickListener {
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    /**
     * Validates email and password inputs.
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateInput(): Boolean {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        var isValid = true

        // Validate email
        if (email.isEmpty()) {
            showEmailError(getString(R.string.email_required))
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showEmailError(getString(R.string.invalid_email_format))
            isValid = false
        } else {
            hideEmailError()
        }

        // Validate password first
        if (password.isEmpty()) {
            showPasswordError(getString(R.string.password_required))
            isValid = false
        } else if (password.length < 6) {
            showPasswordError(getString(R.string.password_min_6))
            isValid = false
        } else if (confirmPassword.isEmpty()) {
            // Only check confirm password if password is valid
            showPasswordError(getString(R.string.confirm_password_required))
            isValid = false
        } else if (password != confirmPassword) {
            showPasswordError(getString(R.string.passwords_do_not_match))
            isValid = false
        } else {
            hidePasswordError()
        }

        return isValid
    }

    private fun showEmailError(message: String) {
        binding.tvEmailError.text = message
        binding.tvEmailError.visibility = View.VISIBLE
        binding.ivEmailError.visibility = View.VISIBLE
        binding.ivEmailError.imageTintList = redStroke
        applyEmailInvalidVisuals()
    }

    private fun hideEmailError() {
        binding.tvEmailError.visibility = View.INVISIBLE
        binding.ivEmailError.visibility = View.INVISIBLE
        clearEmailInvalidVisuals()
    }

    private fun showPasswordError(message: String) {
        binding.tvPasswordError.text = message
        binding.tvPasswordError.visibility = View.VISIBLE
        applyPasswordInvalidVisuals()
    }

    private fun hidePasswordError() {
        binding.tvPasswordError.visibility = View.INVISIBLE
        clearPasswordInvalidVisuals()
    }

    private fun applyEmailInvalidVisuals() {
        // Set red stroke for email field
        binding.emailLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(redColor, redColor)
            )
        )
        binding.emailLayout.setStartIconTintList(redStroke)
        binding.etEmail.setTextColor(redColor)
    }

    private fun clearEmailInvalidVisuals() {
        // Reset to normal colors
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
        // Set red stroke for password fields
        binding.passwordLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(redColor, redColor)
            )
        )
        binding.passwordLayout.setStartIconTintList(redStroke)
        binding.passwordLayout.setEndIconTintList(redStroke)
        binding.etPassword.setTextColor(redColor)

        binding.confirmPasswordLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(redColor, redColor)
            )
        )
        binding.confirmPasswordLayout.setStartIconTintList(redStroke)
        binding.confirmPasswordLayout.setEndIconTintList(redStroke)
        binding.etConfirmPassword.setTextColor(redColor)
    }

    private fun clearPasswordInvalidVisuals() {
        // Reset to normal colors
        binding.passwordLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(blueColor, grayColor)
            )
        )
        binding.passwordLayout.setStartIconTintList(passwordIconDefault)
        binding.passwordLayout.setEndIconTintList(passwordIconDefault)
        binding.etPassword.setTextColor(passwordTextDefault)

        binding.confirmPasswordLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(blueColor, grayColor)
            )
        )
        binding.confirmPasswordLayout.setStartIconTintList(passwordIconDefault)
        binding.confirmPasswordLayout.setEndIconTintList(passwordIconDefault)
        binding.etConfirmPassword.setTextColor(passwordTextDefault)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
