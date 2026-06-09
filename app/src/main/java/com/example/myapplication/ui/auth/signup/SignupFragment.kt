package com.example.myapplication.ui.auth.signup

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.util.Patterns
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.*
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentSignupBinding
import com.example.myapplication.ui.auth.common.InputValidationHelper
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.InputValidator
import kotlinx.coroutines.launch
import kotlin.math.ceil

/*
  Signup screen - SECOND STEP where users enter email and password.
 */
class SignupFragment : BaseFragment(R.layout.fragment_signup) {

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    // Local lockout timer for signup send OTP button
    private var signupLockoutTimer: CountDownTimer? = null

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
    private lateinit var phoneTextDefault: ColorStateList


    // store previous softInputMode so we can restore it when the fragment is destroyed
    private var previousSoftInputMode: Int? = null

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Call super but note: we override the base keyboard scroll control below with our own
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSignupBinding.bind(view)

        // Read configurable height threshold (fallback to 680dp) so resource overrides can change behavior per device class
        val smallHeightThresholdDp = try {
            resources.getInteger(R.integer.small_height_threshold_dp)
        } catch (_: Exception) {
            680
        }

        // Determine whether to use ADJUST_RESIZE based on height breakpoint or a boolean resource — do it once.
        try {
            val screenHeightDp = try {
                resources.configuration.screenHeightDp
            } catch (_: Exception) {
                -1
            }
            val useAdjustResizeByHeight = if (screenHeightDp > 0) screenHeightDp <= smallHeightThresholdDp else false
            val useAdjustResizeRes = resources.getBoolean(R.bool.use_adjust_resize_for_small_screens)
            val finalUseAdjustResize = useAdjustResizeRes || useAdjustResizeByHeight



            if (finalUseAdjustResize) {
                try {
                    val window = requireActivity().window
                    previousSoftInputMode = window.attributes?.softInputMode
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                } catch (_: Exception) { /* ignore if activity/window not available */
                }
            }
        } catch (_: Exception) {
            // ignore environment failures; nothing critical
        }

        // Keep default window IME behavior; do not change softInputMode here beyond the decision above.
        ViewCompat.requestApplyInsets(binding.root)

        // Disable scrolling initially
        binding.scrollAuth.isNestedScrollingEnabled = false

        initializeDefaults()
        setupTextWatchers()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        // Apply any persisted OTP lockout (set by verification fragments on too-many-attempts)
        checkAndApplyLockout()
        observeViewModel()


         // Simplified IME handling: when keyboard appears enable scrolling and scroll to the inputContainer.
         ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            try {
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                if (imeVisible) {
                    // Enable scrolling and ensure the input container is visible above the IME
                    binding.scrollAuth.isNestedScrollingEnabled = true
                    binding.root.post {
                        try {
                            val y = binding.inputContainer.top
                            binding.scrollAuth.smoothScrollTo(0, y)
                        } catch (_: Exception) { }
                    }
                } else {
                    // Restore default behavior when keyboard hides
                    binding.scrollAuth.isNestedScrollingEnabled = false
                    binding.root.post {
                        try { binding.scrollAuth.smoothScrollTo(0, 0) } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
            insets
        }
    }

    override fun onDestroyView() {
        // restore previous softInputMode if we changed it
        try {
            previousSoftInputMode?.let { prev ->
                try {
                    requireActivity().window.setSoftInputMode(prev)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        // cleanup any pending callbacks to avoid leaking the view
        try {
            // nothing to cancel in simplified IME handling
        } catch (_: Exception) {
        }
        _binding = null
        super.onDestroyView()
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

                            // Persist the signup email so subsequent fragments can access it (robust fallback)
                            try {
                                requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    .edit { putString("email", emailArg) }
                            } catch (_: Exception) { }

                            // Build bundle including tempToken so the verification fragment receives it
                            val bundle = bundleOf(
                                "email" to emailArg, "password" to passwordArg, "tempToken" to state.tempToken
                            )

                            try {
                                val nav = findNavController()
                                val actionId = R.id.action_signupFragment_to_signupVerificationFragment
                                try {
                                    nav.navigate(actionId, bundle)
                                } catch (_: Exception) {
                                    try {
                                        nav.navigate(R.id.signupVerificationFragment, bundle)
                                    } catch (_: Exception) {
                                        // navigation failed; show inline error instead of toast
                                        binding.tvEmailError.text = getString(R.string.navigation_failed_try_again)
                                        binding.tvEmailError.visibility = View.VISIBLE
                                    }
                                }
                            } catch (_: Exception) {
                                binding.tvEmailError.text = getString(R.string.navigation_failed_try_again)
                                binding.tvEmailError.visibility = View.VISIBLE
                            } finally {
                                viewModel.reset()
                            }
                        }

                        is SignupViewModel.UiState.Success -> {
                            setLoading(false)
                            // If verification required, navigate; otherwise keep UI state (no toast)
                            val emailArg = binding.etEmail.text.toString().trim()
                            val passwordArg = binding.etPassword.text.toString()

                            // Persist signup email to SharedPreferences as a robust fallback for downstream flows
                            try {
                                requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    .edit { putString("email", emailArg) }
                            } catch (_: Exception) { }

                            try {
                                val nav = findNavController()
                                if (state.requiresVerification) {
                                    val bundle = bundleOf("email" to emailArg, "password" to passwordArg)
                                    try {
                                        val actionId = R.id.action_signupFragment_to_signupVerificationFragment
                                        nav.navigate(actionId, bundle)
                                    } catch (_: Exception) {
                                        try {
                                            nav.navigate(R.id.signupVerificationFragment, bundle)
                                        } catch (_: Exception) {
                                            binding.tvEmailError.text = getString(R.string.navigation_failed_try_again)
                                            binding.tvEmailError.visibility = View.VISIBLE
                                        }
                                    }
                                } else {
                                    // no verification required — leave user on screen; previously a Toast was shown here
                                    // Toast removed as requested; keep UI state unchanged
                                }
                            } catch (_: Exception) {
                                binding.tvEmailError.text = getString(R.string.navigation_failed_try_again)
                                binding.tvEmailError.visibility = View.VISIBLE
                            } finally {
                                viewModel.reset()
                            }
                        }

                        is SignupViewModel.UiState.Error -> {
                            setLoading(false)
                            val msg = state.message.trim()
                            if (msg.contains("password must contain at least one", ignoreCase = true)) {
                                binding.tvPasswordError.text = getString(R.string.password_require_special)
                                binding.tvPasswordError.visibility = View.VISIBLE
                            }
                            else {
                                binding.tvEmailError.text = msg
                                binding.tvEmailError.visibility = View.VISIBLE
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        // Use BaseFragment loader overlay so all fragments share consistent UX
        setLoaderVisible(loading)
        // keep button state in sync
        binding.btnSignup.isEnabled = !loading
        binding.btnSignup.alpha = if (loading) 0.5f else 1.0f
    }

    override fun onStart() {
        super.onStart()
        // ensure prefix is visible and phone field hint is correct; phoneTextDefault captured in initializeDefaults
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

        // Phone layout: disable built-in error display so we control visuals consistently
        try {
            binding.phoneLayout.apply {
                isErrorEnabled = false
                isHelperTextEnabled = false
                errorIconDrawable = null
            }
        } catch (_: Exception) { }

        // Prevent users from typing whitespace into email/password fields and enforce length limits.
        val noSpaceFilter = InputFilter { source, start, end, _, _, _ ->
            // Remove any whitespace characters from the input; if none removed, return null to accept original
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

        // Keep any existing filters (if present), but ensure our filters are applied
        // Keep length limits but allow symbols/special characters (do not strip spaces/symbols here)
        binding.etEmail.filters = arrayOf(InputFilter.LengthFilter(emailMax), noSpaceFilter)
        binding.etPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceFilter)
        binding.etConfirmPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceFilter)

        // Filter: disallow whitespace and emoji; allow letters, digits and punctuation/symbol characters
        val noSpaceOrEmojiFilter = InputFilter { source, start, end, _, _, _ ->
            val out = StringBuilder()
            var i = start
            while (i < end) {
                val cp = Character.codePointAt(source, i)
                val charCount = Character.charCount(cp)
                val isSpace = Character.isWhitespace(cp)
                val isEmoji = (cp in 0x1F600..0x1F64F) || (cp in 0x1F300..0x1F5FF) || (cp in 0x1F680..0x1F6FF) || (cp in 0x1F1E6..0x1F1FF) || (cp in 0x2600..0x26FF) || (cp in 0x2700..0x27BF) || (cp in 0x1F900..0x1F9FF) || (cp in 0x1FA70..0x1FAFF) || (cp in 0xFE00..0xFE0F)
                val type = Character.getType(cp)
                val isLetterOrDigit = Character.isLetterOrDigit(cp)
                // Convert Character category constants to Int and check membership to avoid Int/Byte comparison issues
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
        binding.etConfirmPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceOrEmojiFilter)
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

        // Phone field text watcher - clear phone errors when user starts typing
        try {
            binding.etPhone.addTextChangedListener(object : TextWatcher {
                private var previousText = ""

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    previousText = s?.toString() ?: ""
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val currentText = s?.toString() ?: ""
                    if (previousText != currentText && binding.tvPhoneError.isVisible) {
                        hidePhoneError()
                    }
                }
            })
        } catch (_: Exception) { }
    }

    private fun setupClickListeners() {
        binding.btnSignup.setOnClickListener {
            // block signup if OTP lockout is active
            try {
                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val until = prefs.getLong("signup_otp_lockout_until", 0L)
                if (System.currentTimeMillis() < until) {
                    val remaining = until - System.currentTimeMillis()
                    val minutesLeft = ceil(remaining / 60000.0).toInt()
                    binding.tvEmailError.text = getString(R.string.too_many_attempts_try_again, minutesLeft)
                    binding.tvEmailError.visibility = View.VISIBLE
                    binding.btnSignup.isEnabled = false
                    binding.btnSignup.alpha = 0.5f
                    startSignupLockoutTimer(until)
                    return@setOnClickListener
                }
            } catch (_: Exception) {
            }

            if (validateInput()) {
                val email = binding.etEmail.text.toString().trim()
                val password = binding.etPassword.text.toString()
                val firstName = arguments?.getString("firstName").orEmpty()
                val lastName = arguments?.getString("lastName").orEmpty()
                // Persist email
                try {
                    val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit { putString("email", email) }
                } catch (_: Exception) {}
                val phone = try { binding.etPhone.text.toString().trim() } catch (_: Exception) { "" }
                viewModel.signUp(firstName, lastName, email, password, phone)
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
        val phone = try { binding.etPhone.text.toString().trim() } catch (_: Exception) { "" }
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        var isValid = true

        // Validate email using shared InputValidator, then apply signup-specific rules
        when (InputValidator.validateEmail(email)) {
            InputValidator.EmailResult.EMPTY -> {
                showEmailError(getString(R.string.email_required))
                isValid = false
            }

            InputValidator.EmailResult.INVALID_FORMAT -> {
                showEmailError(getString(R.string.invalid_email_format))
                isValid = false
            }

            InputValidator.EmailResult.TOO_LONG -> {
                showEmailError(getString(R.string.email_max_length))
                isValid = false
            }

            InputValidator.EmailResult.HAS_SPACE -> {
                showEmailError(getString(R.string.email_no_spaces))
                isValid = false
            }

            InputValidator.EmailResult.VALID -> {
                // Additional email rules: max length (covered) and no-space (covered)
                hideEmailError()
            }
        }

        // Phone validation: require a phone number and basic format check
        try {
            if (phone.isEmpty()) {
                showPhoneError(getString(R.string.phone_required))
                isValid = false
            } else {
                val phoneOk = try {
                    Patterns.PHONE.matcher(phone).matches() || phone.all { it.isDigit() }
                } catch (_: Exception) { false }
                if (!phoneOk) {
                    showPhoneError(getString(R.string.invalid_phone))
                    isValid = false
                } else {
                    hidePhoneError()
                }
            }
        } catch (_: Exception) { /* ignore validation failures */ }

        // Stronger password validation: require length 8..25, no spaces, at least one uppercase, lowercase, digit, special char ---
        if (password.isEmpty()) {
            showPasswordError(getString(R.string.password_required))
            isValid = false
        } else if (password.contains("\\s".toRegex())) {
            showPasswordError(getString(R.string.password_no_spaces))
            isValid = false
        } else if (password.length < 8) {
            // explicit message for minimum length
            showPasswordError(getString(R.string.password_min_8))
            isValid = false
        } else if (password.length > 25) {
            showPasswordError(getString(R.string.password_max_length))
            isValid = false
        } else {
            val hasUpper = password.any { it.isUpperCase() }
            val hasLower = password.any { it.isLowerCase() }
            val hasDigit = password.any { it.isDigit() }
            val hasSpecial = password.any { !it.isLetterOrDigit() }

            when {
                !hasUpper -> {
                    showPasswordError(getString(R.string.password_require_uppercase))
                    isValid = false
                }

                !hasLower -> {
                    showPasswordError(getString(R.string.password_require_lowercase))
                    isValid = false
                }

                !hasDigit -> {
                    showPasswordError(getString(R.string.password_require_digit))
                    isValid = false
                }

                !hasSpecial -> {
                    showPasswordError(getString(R.string.password_require_special))
                    isValid = false
                }

                else -> {
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

    private fun showPhoneError(message: String) {
        binding.tvPhoneError.text = message
        binding.tvPhoneError.visibility = View.VISIBLE
        // For phone we use InputValidationHelper on the phoneLayout if available
        try {
            InputValidationHelper.applyEditTextInvalid(binding.phoneLayout, binding.etPhone, redColor, R.drawable.edit_text_outline_error)
        } catch (_: Exception) { }
    }

    private fun hidePhoneError() {
        binding.tvPhoneError.visibility = View.INVISIBLE
        try {
            InputValidationHelper.clearEditTextInvalid(binding.phoneLayout, binding.etPhone, phoneTextDefault, R.drawable.edit_text_outline)
        } catch (_: Exception) { }
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


    // Read persisted lockout and apply UI if active. Starts a local timer to update the message.
    private fun checkAndApplyLockout() {
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val until = prefs.getLong("signup_otp_lockout_until", 0L)
            if (System.currentTimeMillis() < until) {
                // disable signup send button
                binding.btnSignup.isEnabled = false
                binding.btnSignup.alpha = 0.5f
                // show an inline lockout message and start a timer to update it
                startSignupLockoutTimer(until)
            }
        } catch (_: Exception) {
        }
    }

    private fun startSignupLockoutTimer(untilMillis: Long) {
        signupLockoutTimer?.cancel()
        val remaining = untilMillis - System.currentTimeMillis()
        if (remaining <= 0L) {
            binding.btnSignup.isEnabled = true
            binding.btnSignup.alpha = 1.0f
            try {
                requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit { remove("signup_otp_lockout_until") }
            } catch (_: Exception) {
            }
            return
        }

        signupLockoutTimer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val minutesLeft = ceil(millisUntilFinished / 60000.0).toInt()
                binding.tvEmailError.text = getString(R.string.too_many_attempts_try_again, minutesLeft)
                binding.tvEmailError.visibility = View.VISIBLE
            }

            override fun onFinish() {
                binding.btnSignup.isEnabled = true
                binding.btnSignup.alpha = 1.0f
                binding.tvEmailError.visibility = View.INVISIBLE
                try {
                    requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        .edit { remove("signup_otp_lockout_until") }
                } catch (_: Exception) {
                }
                signupLockoutTimer = null
            }
        }

        signupLockoutTimer?.start()
    }
}
