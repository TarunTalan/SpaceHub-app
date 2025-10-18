package com.example.myapplication.ui.auth.login

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.myapplication.ui.common.BaseFragment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentLoginBinding
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.auth.common.InputValidationHelper
import com.example.myapplication.ui.common.InputValidator
import kotlinx.coroutines.launch

class LoginFragment : BaseFragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    private val redColor by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }
    private val blueColor by lazy { ContextCompat.getColor(requireContext(), R.color.primary_blue) }
    private val grayColor by lazy { ContextCompat.getColor(requireContext(), R.color.gray_medium) }
    private val grayLightColor by lazy { ContextCompat.getColor(requireContext(), R.color.gray_light) }
    private val emailIconDefault by lazy { ColorStateList.valueOf(grayColor) }
    private val passwordIconDefault by lazy { ColorStateList.valueOf(grayLightColor) }
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
                        is LoginViewModel.UiState.Idle -> setLoading(false)
                        is LoginViewModel.UiState.Loading -> setLoading(true)
                        is LoginViewModel.UiState.Success -> {
                            setLoading(false)
                            Toast.makeText(requireContext(), "Login successful", Toast.LENGTH_SHORT).show()
                            viewModel.reset()
                        }
                        is LoginViewModel.UiState.Error -> {
                            setLoading(false)
                            binding.tvPasswordError.text = state.message
                            binding.tvPasswordError.visibility = View.VISIBLE
                            applyPasswordInvalidVisuals()
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
    }

    private fun initializeDefaults() {
        emailTextDefault = binding.etEmail.textColors
        passwordTextDefault = binding.etPassword.textColors

        // Use custom eye behavior: closed = masked, open = visible
        PasswordToggleUtil.attach(binding.passwordLayout, binding.etPassword)

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
                val email = binding.etEmail.text?.toString()?.trim().orEmpty()
                val pwd = binding.etPassword.text?.toString()?.trim().orEmpty()
                hideKeyboard()
                viewModel.login(email, pwd)
            }
        }

        // Forgot Password with underline
        binding.tvForgotPassword.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                // Change color to blue when clicked
                setTextColor(blueColor)
                // Navigate to forgot password screen
                findNavController().navigate(R.id.action_loginFragment_to_resetPasswordFragment)
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

        return when (InputValidator.validateEmail(email)) {
            InputValidator.EmailResult.VALID -> {
                emailErrorLatched = false
                binding.tvEmailError.visibility = View.INVISIBLE
                binding.ivEmailError.visibility = View.INVISIBLE
                clearEmailInvalidVisuals()
                true
            }
            InputValidator.EmailResult.EMPTY -> {
                emailErrorLatched = true
                binding.tvEmailError.text = getString(R.string.email_required)
                binding.tvEmailError.visibility = View.VISIBLE
                binding.ivEmailError.visibility = View.VISIBLE
                applyEmailInvalidVisuals()
                false
            }
            InputValidator.EmailResult.INVALID_FORMAT -> {
                emailErrorLatched = true
                binding.tvEmailError.text = getString(R.string.invalid_email_format)
                binding.tvEmailError.visibility = View.VISIBLE
                binding.ivEmailError.visibility = View.VISIBLE
                applyEmailInvalidVisuals()
                false
            }
        }
    }

    private fun validatePassword(): Boolean {
        val pwd = binding.etPassword.text?.toString()?.trim().orEmpty()

        return when (InputValidator.validatePassword(pwd)) {
            InputValidator.PasswordResult.VALID -> {
                passwordErrorLatched = false
                binding.tvPasswordError.visibility = View.INVISIBLE
                clearPasswordInvalidVisuals()
                true
            }
            InputValidator.PasswordResult.EMPTY -> {
                passwordErrorLatched = true
                binding.tvPasswordError.text = getString(R.string.password_required)
                binding.tvPasswordError.visibility = View.VISIBLE
                applyPasswordInvalidVisuals()
                false
            }
            InputValidator.PasswordResult.TOO_SHORT -> {
                passwordErrorLatched = true
                binding.tvPasswordError.text = getString(R.string.password_min_6)
                binding.tvPasswordError.visibility = View.VISIBLE
                applyPasswordInvalidVisuals()
                false
            }
        }
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
        InputValidationHelper.applyPasswordInvalid(
            passwordLayout = binding.passwordLayout,
            etPassword = binding.etPassword,
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
