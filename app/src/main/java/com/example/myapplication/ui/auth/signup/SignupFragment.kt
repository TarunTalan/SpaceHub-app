package com.example.myapplication.ui.auth.signup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
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
import com.example.myapplication.BuildConfig

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
                    if (BuildConfig.DEBUG) Log.d("SignupScroll", "IME insets bottom=${imeInsets.bottom} - scrolling focused")
                    scrollToFocusedAfterIme()
                    v.postDelayed({ try { scrollToFocusedAfterIme() } catch (_: Exception) {} }, 120)
                    v.postDelayed({ try { scrollToFocusedAfterIme() } catch (_: Exception) {} }, 300)
                } catch (_: Exception) {}
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
            if (BuildConfig.DEBUG) Log.d("SignupScroll", "scrollToFocusedAfterIme: no target focused")
            return
        }

        val targetCoords = IntArray(2)
        val scrollCoords = IntArray(2)
        try {
            target.getLocationInWindow(targetCoords)
            scrollRoot.getLocationInWindow(scrollCoords)
        } catch (_: Exception) {
            if (BuildConfig.DEBUG) Log.d("SignupScroll", "scrollToFocusedAfterIme: getLocationInWindow failed")
            return
        }

        val targetTopInScroll = targetCoords[1] - scrollCoords[1]
        val targetBottomInScroll = targetTopInScroll + target.height
        val visibleBottom = scrollRoot.height - scrollRoot.paddingBottom - offset

        if (BuildConfig.DEBUG) Log.d("SignupScroll", "focused target top=$targetTopInScroll bottom=$targetBottomInScroll visibleBottom=$visibleBottom scrollY=${scrollRoot.scrollY}")

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
            if (BuildConfig.DEBUG) Log.d("SignupScroll", "scrolling to y=$y")
            try { scrollRoot.smoothScrollTo(0, y) } catch (_: Exception) { if (BuildConfig.DEBUG) Log.d("SignupScroll", "smoothScrollTo failed") }
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

    // New helper: setup auto scroll for inputs by 70dp when clicked or focused
    private fun setupAutoScrollForInputs() {
        // Capture strong references to the views we'll use inside listeners so callbacks don't access `binding`
        val scrollRoot = binding.scrollRoot
        val rootView = binding.root
        val offset = dpToPx(70)

        // flag whether we've translated content as fallback
        var contentTranslated = false

        fun translateContentUp() {
            val child = scrollRoot.getChildAt(0) ?: return
            if (!contentTranslated) {
                contentTranslated = true
                if (BuildConfig.DEBUG) Log.d("SignupScroll", "translateContentUp called")
                try { child.animate().translationY(-offset.toFloat()).setDuration(180).start() } catch (_: Exception) {}
            }
        }

        fun resetContentTranslation() {
            val child = scrollRoot.getChildAt(0) ?: return
            if (contentTranslated) {
                contentTranslated = false
                if (BuildConfig.DEBUG) Log.d("SignupScroll", "resetContentTranslation called")
                try { child.animate().translationY(0f).setDuration(160).start() } catch (_: Exception) {}
            }
        }

        // compute Y of target relative to scrollRoot using descendant rect (robust)
        fun computeScrollYForTarget(target: View): Int {
            val rect = Rect()
            try {
                target.getDrawingRect(rect)
                // convert rect to scrollRoot coordinates
                scrollRoot.offsetDescendantRectToMyCoords(target, rect)
            } catch (_: Exception) {
                return 0
            }

            val targetTopInScroll = rect.top
            val targetBottomInScroll = rect.bottom
            val visibleBottom = scrollRoot.height - scrollRoot.paddingBottom - offset

            return if (targetBottomInScroll > visibleBottom) {
                val delta = targetBottomInScroll - visibleBottom
                var desired = scrollRoot.scrollY + delta
                val child = scrollRoot.getChildAt(0)
                val maxScroll = if (child != null) (child.height - scrollRoot.height) else 0
                if (desired > maxScroll) desired = maxScroll.coerceAtLeast(0)
                if (desired < 0) 0 else desired
            } else {
                if (targetTopInScroll < offset) {
                    val desired = (scrollRoot.scrollY + targetTopInScroll - offset).coerceAtLeast(0)
                    desired
                } else 0
            }
        }

        fun scrollToTargetWithRetries(target: View) {
            try { target.requestFocus() } catch (_: Exception) {}

            // show IME (best-effort) to help layout resize timing
            try {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {}

            if (BuildConfig.DEBUG) Log.d("SignupScroll", "scrollToTargetWithRetries for id=${'$'}{target.id}")
            // immediate attempt
            scrollRoot.post {
                try {
                    val y = computeScrollYForTarget(target)
                    if (y > 0) {
                        if (BuildConfig.DEBUG) Log.d("SignupScroll", "immediate scrollTo y=${'$'}y")
                        resetContentTranslation()
                        try { scrollRoot.scrollTo(0, y) } catch (_: Exception) {}
                        try { scrollRoot.smoothScrollTo(0, y) } catch (_: Exception) {}
                    } else {
                        // no scrollable space — translate content up as fallback
                        if (BuildConfig.DEBUG) Log.d("SignupScroll", "no scrollable space, translating content")
                        translateContentUp()
                    }
                } catch (_: Exception) {
                    // fallback: request rectangle on screen
                    try {
                        val rect = Rect()
                        target.getDrawingRect(rect)
                        if (!scrollRoot.requestChildRectangleOnScreen(target, rect, true)) {
                            translateContentUp()
                        }
                    } catch (_: Exception) { translateContentUp() }
                }
            }
            // short retries for IME animation timing
            scrollRoot.postDelayed({
                try {
                    val y = computeScrollYForTarget(target)
                    if (y > 0) {
                        if (BuildConfig.DEBUG) Log.d("SignupScroll", "retry1 scrollTo y=${'$'}y")
                        resetContentTranslation()
                        try { scrollRoot.scrollTo(0, y) } catch (_: Exception) {}
                        try { scrollRoot.smoothScrollTo(0, y) } catch (_: Exception) {}
                    } else {
                        translateContentUp()
                    }
                } catch (_: Exception) {}
            }, 120)
            scrollRoot.postDelayed({
                try {
                    val y = computeScrollYForTarget(target)
                    if (y > 0) {
                        if (BuildConfig.DEBUG) Log.d("SignupScroll", "retry2 scrollTo y=${'$'}y")
                        resetContentTranslation()
                        try { scrollRoot.scrollTo(0, y) } catch (_: Exception) {}
                        try { scrollRoot.smoothScrollTo(0, y) } catch (_: Exception) {}
                    } else {
                        translateContentUp()
                    }
                } catch (_: Exception) {}
            }, 300)
        }

        val inputs = listOf(binding.etEmail, binding.etPassword, binding.etConfirmPassword)

        inputs.forEach { input ->
            input.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    if (BuildConfig.DEBUG) Log.d("SignupScroll", "focus gained on id=${'$'}{input.id}")
                    lastFocusedInput = input
                    scrollToTargetWithRetries(input)
                } else {
                    // reset when focus lost
                    try { resetContentTranslation() } catch (_: Exception) {}
                }
            }

            input.setOnClickListener {
                if (BuildConfig.DEBUG) Log.d("SignupScroll", "clicked on id=${'$'}{input.id}")
                lastFocusedInput = input
                scrollToTargetWithRetries(input)
            }

            input.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (BuildConfig.DEBUG) Log.d("SignupScroll", "touch down on id=${'$'}{input.id}")
                    lastFocusedInput = input
                    input.requestFocus()
                    try {
                        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                    } catch (_: Exception) {}
                    scrollToTargetWithRetries(input)
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    // Accessibility: ensure performClick is called when touch leads to click
                    try { v.performClick() } catch (_: Exception) {}
                }
                false
            }
        }

        // global focus listener catches IME-driven focus changes
        val focusList = inputs.map { it as View }.toSet()
        val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is View && newFocus in focusList) {
                lastFocusedInput = newFocus
                newFocus.post { scrollToTargetWithRetries(newFocus) }
            } else {
                // If focus moved away, reset any translation
                try { resetContentTranslation() } catch (_: Exception) {}
            }
        }
        rootView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
        this.globalFocusListener = globalFocusListener

        // keyboard listener: when keyboard shows, ensure last focused input is visible.
        keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            try {
                rootView.getWindowVisibleDisplayFrame(r)
            } catch (_: Exception) {
                return@OnGlobalLayoutListener
            }
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - r.bottom
            if (keypadHeight > screenHeight * 0.15) {
                val target = lastFocusedInput ?: (requireActivity().currentFocus ?: rootView.findFocus())
                target?.let { input ->
                    // immediate + two quick retries to handle IME animation
                    input.post { try { val y = computeScrollYForTarget(input); if (y > 0) { resetContentTranslation(); scrollRoot.smoothScrollTo(0, y) } else translateContentUp() } catch (_: Exception) {} }
                    input.postDelayed({ try { val y2 = computeScrollYForTarget(input); if (y2 > 0) { resetContentTranslation(); scrollRoot.smoothScrollTo(0, y2) } else translateContentUp() } catch (_: Exception) {} }, 120)
                    input.postDelayed({ try { val y3 = computeScrollYForTarget(input); if (y3 > 0) { resetContentTranslation(); scrollRoot.smoothScrollTo(0, y3) } else translateContentUp() } catch (_: Exception) {} }, 300)
                }
            } else {
                // keyboard hidden: reset translation
                try { resetContentTranslation() } catch (_: Exception) {}
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

    override fun onDestroyView() {
        super.onDestroyView()
        // Use safe removal with the current binding if available; guard in case view already detached
        try {
            _binding?.let {
                keyboardListener?.let { listener -> it.root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
                globalFocusListener?.let { listener -> it.root.viewTreeObserver.removeOnGlobalFocusChangeListener(listener) }
            }
        } catch (_: Exception) {}
        _binding = null

        // No WindowInsetsAnimation callback to clean up (removed)
    }
}
