package com.example.myapplication.ui.auth

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentSignupBinding

/**
 * Signup screen - SECOND STEP where users enter email and password.
 */
class SignupFragment : Fragment(R.layout.fragment_signup) {

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    private val redColor by lazy { "#ED2828".toColorInt() }
    private val blueColor by lazy { "#2563EB".toColorInt() }
    private val grayColor by lazy { "#ADADAD".toColorInt() }
    private val emailIconDefault by lazy { ColorStateList.valueOf(grayColor) }
    private val passwordIconDefault by lazy { ColorStateList.valueOf("#C6CAD1".toColorInt()) }
    private val redStroke by lazy { ColorStateList.valueOf(redColor) }

    private lateinit var emailTextDefault: ColorStateList
    private lateinit var passwordTextDefault: ColorStateList

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSignupBinding.bind(view)

        initializeDefaults()
        setupClickListeners()
    }

    private fun initializeDefaults() {
        emailTextDefault = binding.etEmail.textColors
        passwordTextDefault = binding.etPassword.textColors

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

    private fun setupClickListeners() {
        // Complete signup - navigate to login with underline
        binding.btnSignup.setOnClickListener {
            if (validateInput()) {
                // TODO: Complete signup process with backend
                findNavController().navigate(R.id.action_signupFragment_to_loginFragment)
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
            showEmailError("Email is required")
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showEmailError("Invalid email format")
            isValid = false
        } else {
            hideEmailError()
        }

        // Validate password
        if (password.isEmpty()) {
            showPasswordError("Password is required")
            isValid = false
        } else if (password.length < 6) {
            showPasswordError("Password must be at least 6 characters")
            isValid = false
        } else {
            hidePasswordError()
        }

        // Validate confirm password
        if (confirmPassword.isEmpty()) {
            showPasswordError("Please confirm your password")
            isValid = false
        } else if (password != confirmPassword) {
            showPasswordError("Passwords do not match")
            isValid = false
        } else if (password.isNotEmpty() && confirmPassword == password && password.length >= 6) {
            hidePasswordError()
        }

        return isValid
    }

    private fun showEmailError(message: String) {
        binding.tvEmailError.text = message
        binding.tvEmailError.visibility = View.VISIBLE
        binding.ivEmailError.visibility = View.VISIBLE
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
