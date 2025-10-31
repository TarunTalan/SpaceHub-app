package com.example.myapplication.ui.auth.login

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
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
                            // Clear persisted signup email on successful login to avoid stale data
                            try {
                                com.example.myapplication.data.session.SessionManager.clearSignupEmail(requireContext())
                            } catch (_: Exception) { }

//                            Toast.makeText(requireContext(), "Login successful", Toast.LENGTH_SHORT).show()
                            try {
                                val navOptions = NavOptions.Builder()
                                    .setPopUpTo(R.id.auth_nav_graph, true)
                                    .build()
                                // Navigate to dashboard (primary post-login screen) and clear backstack
                                findNavController().navigate(R.id.dashboardFragment, null, navOptions)
                            } catch (_: Exception) {
                                // fallback to simple navigation
                                try { findNavController().navigate(R.id.dashboardFragment) } catch (_: Exception) {}
                            }
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

        // Keep length limits but allow symbols/special characters
        val emailMax = 50
        val passwordMax = 25
        // Filter: disallow whitespace and emoji; allow letters, digits and punctuation/symbol characters
        val noSpaceOrEmojiFilter = InputFilter { source, start, end, _, _, _ ->
            val out = StringBuilder()
            var i = start
            val allowedTypes = setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt()
            )
            while (i < end) {
                val cp = Character.codePointAt(source, i)
                val charCount = Character.charCount(cp)
                val isSpace = Character.isWhitespace(cp)
                val isEmoji = (cp in 0x1F600..0x1F64F) || (cp in 0x1F300..0x1F5FF) || (cp in 0x1F680..0x1F6FF) || (cp in 0x1F1E6..0x1F1FF) || (cp in 0x2600..0x26FF) || (cp in 0x2700..0x27BF) || (cp in 0x1F900..0x1F9FF) || (cp in 0x1FA70..0x1FAFF) || (cp in 0xFE00..0xFE0F)
                val type = Character.getType(cp)
                val isLetterOrDigit = Character.isLetterOrDigit(cp)
                val isAllowedSymbol = allowedTypes.contains(type)
                if (!isSpace && !isEmoji && (isLetterOrDigit || isAllowedSymbol)) {
                    out.appendCodePoint(cp)
                }
                i += charCount
            }
            if (out.length == end - start) null else out.toString()
        }

        binding.etEmail.filters = arrayOf(InputFilter.LengthFilter(emailMax), noSpaceOrEmojiFilter)
        binding.etPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceOrEmojiFilter)
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
        return when (result) {
            InputValidator.PasswordResult.EMPTY -> {
                passwordErrorLatched = true
                binding.tvPasswordError.text = getString(R.string.password_required)
                binding.tvPasswordError.visibility = View.VISIBLE
                applyPasswordInvalidVisuals()
                false
            }
            InputValidator.PasswordResult.VALID -> {
                passwordErrorLatched = false
                binding.tvPasswordError.visibility = View.INVISIBLE
                clearPasswordInvalidVisuals()
                true
            }
            else -> {
                // For all other invalid cases show a single generic invalid-password message
                passwordErrorLatched = true
                binding.tvPasswordError.text = getString(R.string.invalid_password)
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
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        // Call onChange whenever the text changes so UI invalid visuals can clear at the first character typed
        onChange()
    }

    override fun afterTextChanged(s: Editable?) {}
}
