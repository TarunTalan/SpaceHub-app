package com.example.myapplication.ui.auth.signup

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams as CLP
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
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

    // store original margins so we can restore on IME hide
    private var originalContentTopMargin: Int? = null
    private var originalInputTopMargin: Int? = null
    // original margins for inputContainer child groups
    private var originalPasswordGroupTop: Int? = null
    private var originalConfirmGroupTop: Int? = null
    private var originalSignupGroupTop: Int? = null

    // Runnable used to delay margin restore to avoid bouncing when IME hides
    private var imeRestoreRunnable: Runnable? = null
    // Reduced delay so elements restore faster when keyboard hides — read from resources for easy tuning
    private val imeRestoreDelayMs: Long by lazy { resources.getInteger(R.integer.ime_restore_delay_ms).toLong() }
    // Duration used to animate margin transitions when IME appears/disappears — read from resources
    private val imeAnimationDurationMs: Long by lazy { resources.getInteger(R.integer.ime_animation_duration_ms).toLong() }
    // Active animators per view so we can cancel previous animations when a new one starts
    private val runningAnimators: MutableMap<View, ValueAnimator> = mutableMapOf()
    // Track maximum IME height during an animation to normalize progress
    private var imeMaxHeight: Int = 0

    // Remember last observed IME visibility/height so focus switches don't trigger layout updates
    private var lastImeVisible: Boolean? = null
    private var lastImeHeight: Int = 0

    // When true, UI is locked in the "IME visible" state and must not react to further IME changes
    private var imeLockedWhileVisible: Boolean = false

    // Flag so OnApplyWindowInsetsListener does not fight the WindowInsetsAnimation callback
    private var isImeAnimating: Boolean = false

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

        // NOTE: keyboard auto-scroll and IME insets handling for scrollRoot removed — layout will not auto-scroll.

        // Capture original top margins (safe) so we can restore them when IME hides
        try {
            val contentLp = binding.contentLayout.layoutParams as? CLP
            val inputLp = binding.inputContainer.layoutParams as? CLP
            originalContentTopMargin = contentLp?.topMargin ?: 0
            originalInputTopMargin = inputLp?.topMargin ?: 0

            // capture original group margins
            val pwdLp = binding.passwordGroup.layoutParams as? CLP
            val confLp = binding.confirmGroup.layoutParams as? CLP
            val signupLp = binding.signupGroup.layoutParams as? CLP
            originalPasswordGroupTop = pwdLp?.topMargin ?: 0
            originalConfirmGroupTop = confLp?.topMargin ?: 0
            originalSignupGroupTop = signupLp?.topMargin ?: 0
        } catch (_: Exception) {
            originalContentTopMargin = 0
            originalInputTopMargin = 0
            originalPasswordGroupTop = 0
            originalConfirmGroupTop = 0
            originalSignupGroupTop = 0
        }

        // Listen for IME (keyboard) visibility and update margins accordingly.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            try {
                // If animation is running, skip the immediate apply listener to avoid conflicts
                if (isImeAnimating) return@setOnApplyWindowInsetsListener insets

                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                val imeHeight = imeInsets.bottom

                // If IME is visible and we've already locked the UI for visible state, do nothing
                if (imeVisible && imeLockedWhileVisible) {
                    // update observed height but don't trigger layout changes for small fluctuations
                    lastImeHeight = imeHeight
                    lastImeVisible = true
                    return@setOnApplyWindowInsetsListener insets
                }

                // If we have seen the same visibility already (for example when moving focus between inputs), skip re-applying margins
                if (lastImeVisible != null && lastImeVisible == imeVisible) {
                    // update observed height but don't trigger layout changes for small fluctuations
                    lastImeHeight = imeHeight
                    return@setOnApplyWindowInsetsListener insets
                }

                // record new visibility
                lastImeVisible = imeVisible
                lastImeHeight = imeHeight

                val px20 = resources.getDimensionPixelSize(R.dimen.ime_input_top)
                val imeContentTop = resources.getDimensionPixelSize(R.dimen.ime_content_top)
                val compactPx = resources.getDimensionPixelSize(R.dimen.spacing_form_group_compact)
                val signupMin = resources.getDimensionPixelSize(R.dimen.signup_group_min_compact)

                if (imeVisible) {
                    // IME visible (fallback path when animation not present): cancel scheduled restore and apply compact margins immediately
                    imeRestoreRunnable?.let { binding.root.removeCallbacks(it) }
                    imeRestoreRunnable = null

                    // Apply compact spacing once and lock UI while IME remains visible
                    try {
                        // animate transitions rather than abrupt changes
                        animateTopMargin(binding.contentLayout, imeContentTop)
                        animateTopMargin(binding.inputContainer, px20)
                        animateTopMargin(binding.passwordGroup, compactPx)
                        animateTopMargin(binding.confirmGroup, compactPx)
                        animateTopMargin(binding.signupGroup, compactPx.coerceAtLeast(signupMin))
                        // lock UI so subsequent IME inset changes (focus switches) don't change layout
                        imeLockedWhileVisible = true
                    } catch (_: Exception) { }
                } else {
                    // IME hidden: clear the lock and schedule a delayed restore to prevent immediate bounce
                    imeLockedWhileVisible = false
                    imeRestoreRunnable?.let { binding.root.removeCallbacks(it) }
                    val restoreRunnable = Runnable {
                        try {
                            // animate restore to original margins
                            animateTopMargin(binding.contentLayout, originalContentTopMargin ?: imeContentTop)
                            animateTopMargin(binding.inputContainer, originalInputTopMargin ?: px20)
                            animateTopMargin(binding.passwordGroup, originalPasswordGroupTop ?: (resources.getDimensionPixelSize(R.dimen.spacing_form_group)))
                            animateTopMargin(binding.confirmGroup, originalConfirmGroupTop ?: (resources.getDimensionPixelSize(R.dimen.spacing_form_group)))
                            animateTopMargin(binding.signupGroup, originalSignupGroupTop ?: (resources.getDimensionPixelSize(R.dimen.margin_input_container_top) / 2))
                        } catch (_: Exception) {
                            // ignore
                        }
                    }
                    imeRestoreRunnable = restoreRunnable
                    binding.root.postDelayed(restoreRunnable, imeRestoreDelayMs)
                }
            } catch (_: Exception) {
                // ignore layout update failures
            }

            // return insets so other listeners can use them
            insets
        }

        // Use WindowInsetsAnimationCompat to animate margins smoothly when IME animates (API 30+ effectively)
        try {
            ViewCompat.setWindowInsetsAnimationCallback(binding.root, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    // cancel any scheduled restore
                    imeRestoreRunnable?.let { binding.root.removeCallbacks(it) }
                    imeRestoreRunnable = null
                    // reset max tracker
                    imeMaxHeight = 0
                    // reset last observed height so animation measurements start fresh
                    lastImeHeight = 0
                    // mark that we're animating so the apply-listener won't stomp
                    isImeAnimating = true
                }

                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    try {
                        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                        val imeHeight = imeInsets.bottom

                        // If IME is visible and we've already locked UI for visible state, ignore progressive updates
                        if (imeHeight > 0 && imeLockedWhileVisible) {
                            lastImeHeight = imeHeight
                            lastImeVisible = true
                            return insets
                        }

                        // If IME just started showing and UI isn't locked yet, apply compact spacing once and lock
                        else if (imeHeight > 0) {
                            val px20 = resources.getDimensionPixelSize(R.dimen.ime_input_top)
                            val imeContentTop = resources.getDimensionPixelSize(R.dimen.ime_content_top)
                            val compactPx = resources.getDimensionPixelSize(R.dimen.spacing_form_group_compact)
                            val signupMin = resources.getDimensionPixelSize(R.dimen.signup_group_min_compact)

                            try {
                                // animate to the compact layout when IME shows
                                animateTopMargin(binding.contentLayout, imeContentTop)
                                animateTopMargin(binding.inputContainer, px20)
                                animateTopMargin(binding.passwordGroup, compactPx)
                                animateTopMargin(binding.confirmGroup, compactPx)
                                animateTopMargin(binding.signupGroup, compactPx.coerceAtLeast(signupMin))
                                imeLockedWhileVisible = true
                                lastImeVisible = true
                                lastImeHeight = imeHeight
                            } catch (_: Exception) {
                                // ignore
                            }
                            return insets
                        }

                        // If IME is hiding (height == 0) don't do progressive animation — let apply-listener schedule restore
                        return insets
                    } catch (_: Exception) {
                        // ignore
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    // reset max and mark animation finished
                    imeMaxHeight = 0
                    isImeAnimating = false
                    // If IME is hidden now, ensure lock is cleared so apply-listener can restore
                    // We delay requestApplyInsets slightly to let the system settle before running the apply-listener
                    try {
                        binding.root.postDelayed({
                            try {
                                imeLockedWhileVisible = false
                                // request apply so the normal restore runnable animates back to original state
                                binding.root.requestApplyInsets()
                            } catch (_: Exception) { }
                        }, 0L)
                    } catch (_: Exception) { }
                }
            })
        } catch (_: Exception) {
            // If animation callback isn't supported, we'll keep the fallback listener
        }
    }

    override fun onDestroyView() {
        // cleanup any pending callbacks to avoid leaking the view
        try {
            imeRestoreRunnable?.let { binding.root.removeCallbacks(it) }
            imeRestoreRunnable = null
            // cancel any running margin animations to avoid leaking view references
            try {
                runningAnimators.values.forEach { it.cancel() }
            } catch (_: Exception) { }
            runningAnimators.clear()
        } catch (_: Exception) { }
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

    // Helper to animate the top margin of a view's ConstraintLayout.LayoutParams smoothly.
    private fun animateTopMargin(view: View, to: Int, duration: Long = imeAnimationDurationMs) {
        try {
            val lp = view.layoutParams as? CLP ?: return
            val from = lp.topMargin
            if (from == to) return

            // cancel previous animator for this view
            runningAnimators[view]?.cancel()

            val animator = ValueAnimator.ofInt(from, to).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Int
                    try {
                        view.updateLayoutParams<CLP> { topMargin = value }
                    } catch (_: Exception) { }
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationEnd(animation: android.animation.Animator) { runningAnimators.remove(view) }
                    override fun onAnimationCancel(animation: android.animation.Animator) { runningAnimators.remove(view) }
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                })
            }

            runningAnimators[view] = animator
            animator.start()
        } catch (_: Exception) { /* ignore animation failures */ }
    }
}
