package com.example.myapplication.ui.auth.login

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.example.myapplication.ui.auth.common.InputValidationHelper
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.common.BaseFragment
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

        // Remove fragment-local back press handler which forcibly popped to onboarding.
        // The activity already manages back behavior centrally in MainActivity.
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
                            findNavController().navigate(R.id.action_loginFragment_to_logoutFragment)
                            viewModel.reset()
                        }

                        is LoginViewModel.UiState.Error -> {
                            setLoading(false)
                            val msg = state.message
                            // if message likely refers to password, show password error and latch it; otherwise show email error
                            val isPasswordError =
                                msg.contains("invalid credentials", ignoreCase = true) || msg.contains(
                                    "password",
                                    ignoreCase = true
                                ) || msg.contains("credentials", ignoreCase = true)
                            if (isPasswordError) {
                                passwordErrorLatched = true
                                binding.tvPasswordError.text = getString(R.string.invalid_password)
                                binding.tvPasswordError.visibility = View.VISIBLE
                                applyPasswordInvalidVisuals()
                            } else {
                                emailErrorLatched = true
                                binding.tvEmailError.text = msg
                                binding.tvEmailError.visibility = View.VISIBLE
                                applyEmailInvalidVisuals()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        // show fullscreen loader from BaseFragment for consistency
        setLoaderVisible(loading)
        // keep button state in sync and dim while loading
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.alpha = if (loading) 0.5f else 1.0f
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

        // Prevent users from typing whitespace into email/password fields and enforce length limits
        val noSpaceFilter = InputFilter { source, start, end, _, _, _ ->
            val out = StringBuilder()
            var removed = false
            for (i in start until end) {
                val c = source[i]
                if (!Character.isWhitespace(c)) out.append(c) else removed = true
            }
            if (!removed) null else out.toString()
        }
        val emailMax = 50
        val passwordMax = 25
        binding.etEmail.filters = arrayOf(InputFilter.LengthFilter(emailMax), noSpaceFilter)
        binding.etPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceFilter)
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

            InputValidator.EmailResult.TOO_LONG -> {
                emailErrorLatched = true
                binding.tvEmailError.text = getString(R.string.email_max_length)
                binding.tvEmailError.visibility = View.VISIBLE
                binding.ivEmailError.visibility = View.VISIBLE
                applyEmailInvalidVisuals()
                false
            }

            InputValidator.EmailResult.HAS_SPACE -> {
                emailErrorLatched = true
                binding.tvEmailError.text = getString(R.string.email_no_spaces)
                binding.tvEmailError.visibility = View.VISIBLE
                binding.ivEmailError.visibility = View.VISIBLE
                applyEmailInvalidVisuals()
                false
            }
        }
    }

    private fun validatePassword(): Boolean {
        val pwd = binding.etPassword.text?.toString()?.trim().orEmpty()
        val result = InputValidator.validatePassword(pwd)

        val isValid = result == InputValidator.PasswordResult.VALID
        passwordErrorLatched = !isValid

        if (isValid) {
            binding.tvPasswordError.visibility = View.INVISIBLE
            clearPasswordInvalidVisuals()
            return true
        } else {
            binding.tvPasswordError.text = getString(R.string.invalid_password)
            binding.tvPasswordError.visibility = View.VISIBLE
            applyPasswordInvalidVisuals()
            return false
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
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        // Call onChange whenever the text changes so UI invalid visuals can clear at the first character typed
        onChange()
    }

    override fun afterTextChanged(s: Editable?) {}
}
