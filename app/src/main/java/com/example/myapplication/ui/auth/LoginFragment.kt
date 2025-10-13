package com.example.myapplication.ui.auth

import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentLoginBinding
import java.util.regex.Pattern

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val PASSWORD_PATTERN: Pattern = Pattern.compile(
        "^" + "(?=.*[0-9])" + "(?=.*[a-z])" + "(?=.*[A-Z])" + "(?=.*[!@#\\$%^&\\*])" + "(?=\\S+$)" + ".{8,}" + "$"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvForgotPassword.paintFlags =
            binding.tvForgotPassword.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.tvSignupLink.paintFlags =
            binding.tvSignupLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        val hintColor = ContextCompat.getColorStateList(requireContext(), R.color.hint_color)
        binding.emailLayout.defaultHintTextColor = hintColor
        binding.passwordLayout.defaultHintTextColor = hintColor

        // Validate email on focus change
        binding.etEmail.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateEmail()
            }
        }

        // Clear email error when typing
        binding.etEmail.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (binding.emailLayout.isErrorEnabled) {
                    binding.emailLayout.error = null
                    binding.emailLayout.isErrorEnabled = false
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Validate password on focus change
        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validatePassword()
            }
        }

        // Clear password error when typing
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (binding.passwordLayout.isErrorEnabled) {
                    binding.passwordLayout.error = null
                    binding.passwordLayout.isErrorEnabled = false
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnLogin.setOnClickListener {
            val isEmailValid = validateEmail()
            val isPasswordValid = validatePassword()

            if (isEmailValid && isPasswordValid) {
                Toast.makeText(requireContext(), "Logging in...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(requireContext(), "Forgot Password clicked", Toast.LENGTH_SHORT).show()
        }

        binding.tvSignupLink.setOnClickListener {
            Toast.makeText(requireContext(), "Sign Up clicked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateEmail(): Boolean {
        val email = binding.etEmail.text.toString().trim()

        return when {
            email.isEmpty() -> {
                binding.emailLayout.error = "Email is required"
                binding.emailLayout.isErrorEnabled = true
                false
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.emailLayout.error = "Enter a valid email address"
                binding.emailLayout.isErrorEnabled = true
                false
            }
            else -> {
                binding.emailLayout.error = null
                binding.emailLayout.isErrorEnabled = false
                true
            }
        }
    }

    private fun validatePassword(): Boolean {
        val password = binding.etPassword.text.toString().trim()

        return when {
            password.isEmpty() -> {
                binding.passwordLayout.error = "Password is required"
                binding.passwordLayout.isErrorEnabled = true
                false
            }
            !PASSWORD_PATTERN.matcher(password).matches() -> {
//                binding.passwordLayout.error = "Password must be at least 8 characters and include an uppercase letter, a number, and a special character"
                binding.passwordLayout.isErrorEnabled = true
                false
            }
            else -> {
                binding.passwordLayout.error = null
                binding.passwordLayout.isErrorEnabled = false
                true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
