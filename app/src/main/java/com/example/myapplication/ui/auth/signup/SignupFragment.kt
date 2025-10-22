package com.example.myapplication.ui.auth.signup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
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

/**
 * Signup screen - SECOND STEP where users enter email and password.
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

    // track last focused input so keyboard-open events can scroll to it
    private var lastFocusedInput: View? = null
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSignupBinding.bind(view)

        // Request the activity to resize the window when the keyboard appears so ScrollView can resize
        try {
            requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (_: Exception) {
            // ignore if activity/window is not available
        }

        // Keep default window IME behavior; do not change softInputMode here.
        ViewCompat.requestApplyInsets(binding.root)

        initializeDefaults()
        setupTextWatchers()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        // Apply any persisted OTP lockout (set by verification fragments on too-many-attempts)
        checkAndApplyLockout()
        observeViewModel()

        // Add auto-scroll behavior for input fields (scroll up 70dp when focused or clicked)
        setupAutoScrollForInputs()

        // Apply IME insets as bottom padding to the scrollRoot so NestedScrollView resizes correctly
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollRoot) { v, insets ->
            val imeInsets = insets.getInsets(Type.ime() or Type.systemBars())
            // keep existing padding left/top/right; update bottom to accommodate IME
            v.updatePadding(bottom = imeInsets.bottom)

            // If IME appeared, try to scroll the last-focused input into view (short retries to handle animation)
            if (imeInsets.bottom > 0) {
                try {
                    // scroll into view; logging removed
                    scrollToFocusedAfterIme()
                    v.postDelayed({
                        try {
                            scrollToFocusedAfterIme()
                        } catch (_: Exception) {
                        }
                    }, 120)
                    v.postDelayed({
                        try {
                            scrollToFocusedAfterIme()
                        } catch (_: Exception) {
                        }
                    }, 300)
                } catch (_: Exception) {
                }
            }

            // return the insets unchanged
            insets
        }
        ViewCompat.requestApplyInsets(binding.scrollRoot)

        // NOTE: Removed WindowInsetsAnimationCompat callback code — the insets listener + retries handle IME timing.
    }

    // Ensure when IME appears we attempt to scroll the focused input into view.
    private fun scrollToFocusedAfterIme() {
        val scrollRoot = binding.scrollRoot
        val offset = dpToPx()
        val target = lastFocusedInput ?: (requireActivity().currentFocus ?: binding.root.findFocus()) ?: run {
            // logging removed
            return
        }

        val targetCoords = IntArray(2)
        val scrollCoords = IntArray(2)
        try {
            target.getLocationInWindow(targetCoords)
            scrollRoot.getLocationInWindow(scrollCoords)
        } catch (_: Exception) {
            // logging removed
            return
        }

        val targetTopInScroll = targetCoords[1] - scrollCoords[1]
        val targetBottomInScroll = targetTopInScroll + target.height
        val visibleBottom = scrollRoot.height - scrollRoot.paddingBottom - offset

        // logging removed

        val desired = when {
            targetBottomInScroll > visibleBottom -> {
                val delta = targetBottomInScroll - visibleBottom
                var d = scrollRoot.scrollY + delta
                val child = scrollRoot.getChildAt(0)
                val maxScroll = if (child != null) (child.height - scrollRoot.height) else 0
                if (d > maxScroll) d = maxScroll.coerceAtLeast(0)
                if (d < 0) 0 else d
            }

            targetTopInScroll < offset -> {
                val d = (scrollRoot.scrollY + targetTopInScroll - offset).coerceAtLeast(0)
                d
            }

            else -> null
        }

        desired?.let { y ->
            // logging removed
            try {
                scrollRoot.smoothScrollTo(0, y)
            } catch (_: Exception) { /* ignore */
            }
        }
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
                                    // no verification required — leave user on screen; show inline success message in password error field
                                    Toast.makeText(context, "Registration Successful", Toast.LENGTH_SHORT).show()
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
                            // Show failure inline for registration error instead of toast
                            val msg = state.message.trim()
                            msg.lowercase()
                            // If the server says the user/email already exists, show it near the email field
                            // generic auth error — show near password field
                            binding.tvEmailError.text = msg
                            binding.tvEmailError.visibility = View.VISIBLE
                            // also clear email error so UI is focused on the relevant field
                            hidePasswordError()

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
        binding.etEmail.filters = arrayOf(InputFilter.LengthFilter(emailMax), noSpaceFilter)
        binding.etPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceFilter)
        binding.etConfirmPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceFilter)
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
            // Defensive: block signup if OTP lockout is active
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
            } catch (_: Exception) { /* ignore and continue */
            }

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

        // --- Stronger password validation: require length 8..25, no spaces, at least one uppercase, lowercase, digit, special char ---
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

    // New helper: setup auto scroll for inputs by 70dp when clicked or focused
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAutoScrollForInputs() {
        val scrollRoot = binding.scrollRoot
        val rootView = binding.root
        val offset = dpToPx(70)

        fun computeScrollYForTarget(target: View): Int {
            val rect = Rect()
            try {
                target.getDrawingRect(rect)
                scrollRoot.offsetDescendantRectToMyCoords(target, rect)
            } catch (_: Exception) {
                return 0
            }

            val targetTop = rect.top
            val targetBottom = rect.bottom
            val visibleBottom = scrollRoot.height - scrollRoot.paddingBottom - offset

            return when {
                targetBottom > visibleBottom -> {
                    val delta = targetBottom - visibleBottom
                    var desired = scrollRoot.scrollY + delta
                    val child = scrollRoot.getChildAt(0)
                    val maxScroll = if (child != null) (child.height - scrollRoot.height) else 0
                    if (desired > maxScroll) desired = maxScroll.coerceAtLeast(0)
                    if (desired < 0) 0 else desired
                }
                targetTop < offset -> {
                    (scrollRoot.scrollY + targetTop - offset).coerceAtLeast(0)
                }
                else -> 0
            }
        }

        val inputs = listOf(binding.etEmail, binding.etPassword, binding.etConfirmPassword)

        fun scrollToView(view: View) {
            try {
                val y = computeScrollYForTarget(view)
                if (y > 0) {
                    scrollRoot.post { try { scrollRoot.smoothScrollTo(0, y) } catch (_: Exception) {} }
                }
            } catch (_: Exception) { }
        }

        inputs.forEach { input ->
            input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) scrollToView(input) }
            input.setOnClickListener { scrollToView(input) }
            input.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    input.requestFocus()
                    try {
                        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                    } catch (_: Exception) { }
                    scrollToView(input)
                }
                // allow other listeners to handle the touch
                false
            }
        }

        // Global focus listener to catch programmatic focus changes
        val focusSet = inputs.map { it as View }.toSet()
        val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is View && newFocus in focusSet) {
                lastFocusedInput = newFocus
                newFocus.post { scrollToView(newFocus) }
            }
        }
        rootView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
        this.globalFocusListener = globalFocusListener

        // Keyboard visibility listener: ensure last-focused input is visible when IME appears
        keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            try {
                rootView.getWindowVisibleDisplayFrame(r)
            } catch (_: Exception) { return@OnGlobalLayoutListener }
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - r.bottom
            if (keypadHeight > screenHeight * 0.15) {
                val target = lastFocusedInput ?: (requireActivity().currentFocus ?: rootView.findFocus())
                target?.let { t -> t.post { scrollToView(t) } }
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
    }

    // store reference so we can unregister
    private var globalFocusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    // Convert dp to pixels. Default dp is 70 for the signup scroll offset.
    private fun dpToPx(dp: Int = 70): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
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
        } catch (_: Exception) {}
    }

    private fun startSignupLockoutTimer(untilMillis: Long) {
        signupLockoutTimer?.cancel()
        val remaining = untilMillis - System.currentTimeMillis()
        if (remaining <= 0L) {
            binding.btnSignup.isEnabled = true
            binding.btnSignup.alpha = 1.0f
            try { requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit { remove("signup_otp_lockout_until") } } catch (_: Exception) { }
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
                try { requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit { remove("signup_otp_lockout_until") } } catch (_: Exception) { }
                signupLockoutTimer = null
            }
        }

        signupLockoutTimer?.start()
    }
}
