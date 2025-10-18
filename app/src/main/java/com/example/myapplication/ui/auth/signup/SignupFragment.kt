package com.example.myapplication.ui.auth.signup

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.example.myapplication.ui.common.BaseFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentSignupBinding
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.common.InputValidator
import com.example.myapplication.ui.auth.common.InputValidationHelper
import kotlinx.coroutines.launch

/**
 * Signup screen - SECOND STEP where users enter email and password.
 */
class SignupFragment : BaseFragment(R.layout.fragment_signup) {

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SignupViewModel by viewModels()

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
        setupKeyboardDismiss(binding.root)
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is SignupViewModel.UiState.Idle -> {
                            setLoading(false)
                        }
                        is SignupViewModel.UiState.Loading -> {
                            setLoading(true)
                        }

                        // handle EmailSent so we navigate to verification when signup response includes a temp token
                        is SignupViewModel.UiState.EmailSent -> {
                            setLoading(false)
                            val emailArg = binding.etEmail.text.toString().trim()
                            val passwordArg = binding.etPassword.text.toString()

                            // Build bundle including tempToken so the verification fragment receives it
                            val bundle = bundleOf(
                                "email" to emailArg,
                                "password" to passwordArg,
                                "tempToken" to state.tempToken
                            )

                            try {
                                val nav = findNavController()
                                val actionId = R.id.action_signupFragment_to_signupVerificationFragment
                                try {
                                    nav.navigate(actionId, bundle)
                                } catch (_: Exception) {
                                    try { nav.navigate(R.id.signupVerificationFragment, bundle) } catch (_: Exception) {
                                        // navigation failed; show inline error instead of toast
                                        binding.tvPasswordError.text = getString(R.string.navigation_failed_try_again)
                                        binding.tvPasswordError.visibility = View.VISIBLE
                                    }
                                }
                            } catch (_: Exception) {
                                binding.tvPasswordError.text = getString(R.string.navigation_failed_try_again)
                                binding.tvPasswordError.visibility = View.VISIBLE
                            } finally {
                                viewModel.reset()
                            }
                        }

                        is SignupViewModel.UiState.Success -> {
                            setLoading(false)
                            // If verification required, navigate; otherwise keep UI state (no toast)
                            val emailArg = binding.etEmail.text.toString().trim()
                            val passwordArg = binding.etPassword.text.toString()
                            try {
                                val nav = findNavController()
                                if (state.requiresVerification) {
                                    val bundle = bundleOf("email" to emailArg, "password" to passwordArg)
                                    try {
                                        val actionId = R.id.action_signupFragment_to_signupVerificationFragment
                                        nav.navigate(actionId, bundle)
                                    } catch (_: Exception) {
                                        try { nav.navigate(R.id.signupVerificationFragment, bundle) } catch (_: Exception) {
                                            binding.tvPasswordError.text = getString(R.string.navigation_failed_try_again)
                                            binding.tvPasswordError.visibility = View.VISIBLE
                                        }
                                    }
                                } else {
                                    // no verification required — leave user on screen; show inline success message in password error field
                                    binding.tvPasswordError.text = getString(R.string.registered_successful)
                                    binding.tvPasswordError.visibility = View.VISIBLE
                                }
                            } catch (_: Exception) {
                                binding.tvPasswordError.text = getString(R.string.navigation_failed_try_again)
                                binding.tvPasswordError.visibility = View.VISIBLE
                            } finally {
                                viewModel.reset()
                            }
                        }

                        is SignupViewModel.UiState.Error -> {
                            setLoading(false)
                            // Show failure inline for registration error instead of toast
                            val msg = state.message.trim()
                            val lower = msg.lowercase()
                            // If the server says the user/email already exists, show it near the email field
                            if ("already" in lower || "exist" in lower || "user already" in lower) {
                                // show as email error
                                showEmailError(msg)
                                // clear password error if any
                                hidePasswordError()
                            } else {
                                // generic auth error — show near password field
                                binding.tvPasswordError.text = msg
                                binding.tvPasswordError.visibility = View.VISIBLE
                                // also clear email error so UI is focused on the relevant field
                                hideEmailError()
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSignup.isEnabled = !loading
        // Optionally change text or show a progress indicator if you have one
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
    }

    private fun setupClickListeners() {
        // Complete signup - navigate to OTP verification
        binding.btnSignup.setOnClickListener {
            if (validateInput()) {
                val email = binding.etEmail.text.toString().trim()
                val password = binding.etPassword.text.toString()
                val firstName = arguments?.getString("firstName").orEmpty()
                val lastName = arguments?.getString("lastName").orEmpty()
                viewModel.signUp(firstName, lastName, email, password)
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

        // Validate email using shared InputValidator
        when (InputValidator.validateEmail(email)) {
            InputValidator.EmailResult.EMPTY -> {
                showEmailError(getString(R.string.email_required))
                isValid = false
            }
            InputValidator.EmailResult.INVALID_FORMAT -> {
                showEmailError(getString(R.string.invalid_email_format))
                isValid = false
            }
            InputValidator.EmailResult.VALID -> hideEmailError()
        }

        // Validate password using shared InputValidator
        when (InputValidator.validatePassword(password)) {
            InputValidator.PasswordResult.EMPTY -> {
                showPasswordError(getString(R.string.password_required))
                isValid = false
            }
            InputValidator.PasswordResult.TOO_SHORT -> {
                showPasswordError(getString(R.string.password_min_6))
                isValid = false
            }
            InputValidator.PasswordResult.VALID -> {
                // Additional signup-only password rules
                if (!InputValidator.hasUppercase(password)) {
                    showPasswordError(getString(R.string.password_require_uppercase))
                    isValid = false
                } else if (!InputValidator.hasDigit(password)) {
                    showPasswordError(getString(R.string.password_require_digit))
                    isValid = false
                } else if (!InputValidator.hasSpecialChar(password)) {
                    showPasswordError(getString(R.string.password_require_special))
                    isValid = false
                } else {
                    // Then check confirm password
                    if (confirmPassword.isEmpty()) {
                        showPasswordError(getString(R.string.confirm_password_required))
                        isValid = false
                    } else if (password != confirmPassword) {
                        showPasswordError(getString(R.string.passwords_do_not_match))
                        isValid = false
                    } else {
                        hidePasswordError()
                    }
                }
            }
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
        InputValidationHelper.applyEmailInvalid(
            emailLayout = binding.emailLayout,
            etEmail = binding.etEmail,
            ivEmailError = binding.ivEmailError,
            redColor = redColor,
            redStroke = redStroke
        )
    }

    private fun clearEmailInvalidVisuals() {
        InputValidationHelper.clearEmailInvalid(
            emailLayout = binding.emailLayout,
            etEmail = binding.etEmail,
            ivEmailError = binding.ivEmailError,
            emailIconDefault = emailIconDefault,
            emailTextDefault = emailTextDefault,
            blueColor = blueColor,
            grayColor = grayColor
        )
    }

    private fun applyPasswordInvalidVisuals() {
        // Use helper for password + confirm fields
        InputValidationHelper.applyPasswordInvalid(
            passwordLayout = binding.passwordLayout,
            etPassword = binding.etPassword,
            redColor = redColor,
            redStroke = redStroke
        )

        InputValidationHelper.applyPasswordInvalid(
            passwordLayout = binding.confirmPasswordLayout,
            etPassword = binding.etConfirmPassword,
            redColor = redColor,
            redStroke = redStroke
        )
    }

    private fun clearPasswordInvalidVisuals() {
        InputValidationHelper.clearPasswordInvalid(
            passwordLayout = binding.passwordLayout,
            etPassword = binding.etPassword,
            passwordIconDefault = passwordIconDefault,
            passwordTextDefault = passwordTextDefault,
            blueColor = blueColor,
            grayColor = grayColor
        )

        InputValidationHelper.clearPasswordInvalid(
            passwordLayout = binding.confirmPasswordLayout,
            etPassword = binding.etConfirmPassword,
            passwordIconDefault = passwordIconDefault,
            passwordTextDefault = passwordTextDefault,
            blueColor = blueColor,
            grayColor = grayColor
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
