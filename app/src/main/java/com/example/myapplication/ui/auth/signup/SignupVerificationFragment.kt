package com.example.myapplication.ui.auth.signup

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import com.example.myapplication.ui.common.OtpResendCooldownHelper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.example.myapplication.ui.common.BaseFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentVerifySignupBinding
import kotlinx.coroutines.launch
import com.example.myapplication.ui.common.createOtpCooldownHelper
import com.example.myapplication.ui.common.startCooldownIfTokenPresent
import com.example.myapplication.ui.common.resumeCooldownFromVm

import com.example.myapplication.ui.auth.common.InputValidationHelper
import androidx.core.content.ContextCompat
import android.text.InputFilter
import androidx.core.widget.addTextChangedListener
import java.util.Locale
import androidx.core.content.edit
import android.widget.TextView
import android.view.View
import kotlin.math.ceil

class SignupVerificationFragment : BaseFragment(R.layout.fragment_verify_signup) {
    private var _binding: FragmentVerifySignupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SignupViewModel by viewModels()

    // Helper to detect 'too many attempts' or similar lockout messages
    private fun isTooManyAttempts(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val msg = message.lowercase(Locale.getDefault())
        return msg.contains("too many") || (msg.contains("attempt") && msg.contains("limit")) || msg.contains("temporarily disabled") || msg.contains("blocked")
    }
    private var emailArg: String? = null
    private var passwordArg: String? = null
    private var isLoading = false
    // Track local failed OTP attempts before locking out (client-side). Backend lockout still honored.
    private var failedAttempts = 0
    private val MAX_FAILED_ATTEMPTS = 3
    // cooldown between resend OTP requests (30 seconds)
    private var resendCooldownMillis: Long = 30_000L // 30 seconds
    private var cooldownHelper: OtpResendCooldownHelper? = null

    // When resend is clicked we set this flag so any Error responses from the server
    // during the resend flow are not treated as OTP-verification failures (do not
    // decrement failedAttempts). Lockout messages ("too many attempts") are still honored.
    private var isResendInFlight: Boolean = false

    // Clear OTP inline error and any parent email error view
    private fun clearOtpAndEmailErrors() {
        try {
            binding.tvOtpError.isVisible = false
            var emailErr: TextView? = binding.root.findViewById(R.id.tvEmailError)
            if (emailErr == null) emailErr = parentFragment?.view?.findViewById(R.id.tvEmailError)
            if (emailErr == null) emailErr = requireActivity().findViewById(R.id.tvEmailError)
            emailErr?.let { it.visibility = View.GONE; it.text = "" }
        } catch (_: Exception) { /* ignore */ }
    }

    // defaults for OTP visuals
    private lateinit var otpTextDefault: ColorStateList
    private val redColorInt by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }
    private val normalOtpBgRes = R.drawable.edit_text_outline_selector
    private val errorOtpBgRes = R.drawable.edit_text_outline_error

    // Helper to get lockout key per email (normalize by trimming and lowercasing)
    private fun normalizeEmail(email: String?): String? {
        return email?.trim()?.lowercase(Locale.getDefault())
    }

    private fun getLockoutKey(email: String?): String {
        val norm = normalizeEmail(email) ?: "unknown"
        return "signup_otp_lockout_until_$norm"
    }

    // Persist a lockout for the normalized email key
    private fun persistLockout(email: String?, lockMillis: Long) {
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val key = getLockoutKey(email)
            prefs.edit { putLong(key, lockMillis) }
        } catch (_: Exception) { /* ignore */ }
    }

    // Return the lockout end millis for the given email if a lock is active, otherwise null.
    private fun getLockoutUntil(email: String?): Long? {
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val norm = normalizeEmail(email)
            // check normalized, raw and trimmed variants (ignore nulls) and dedupe
            val variants = listOfNotNull(norm, email, email?.trim()).map { it }.distinct()
            val prefixes = listOf("signup_otp_lockout_until_", "forgot_otp_lockout_until_")
            for (v in variants) {
                for (p in prefixes) {
                    val until = prefs.getLong(p + v, 0L)
                    if (now < until) return until
                }
            }
        } catch (_: Exception) { /* ignore and treat as not locked */ }
        return null
    }

    // Helper that says whether there's an active lock for this email
    private fun isEmailLocked(email: String?): Boolean = getLockoutUntil(email) != null

    // Apply UI lock state for a specific email: disable/dim controls and show message if locked
    private fun applyLockStateForEmail(email: String?) {
        try {
            val foundUntil = getLockoutUntil(email)
            if (foundUntil != null) {
                setLockUI(true, foundUntil)
                return
            }

            // no persisted lock — consult ViewModel cooldown if present
            val vmCooldown = viewModel.cooldownEndMillis.value
            if (vmCooldown == null || System.currentTimeMillis() >= vmCooldown) setLockUI(false) else setLockUI(false, vmCooldown)
        } catch (_: Exception) { /* ignore */ }
    }

    // Centralized helper to set lock/normal UI state for verify/resend controls.
    private fun setLockUI(locked: Boolean, untilMillis: Long? = null) {
        if (locked) {
            binding.btnLogin.isEnabled = false
            binding.btnLogin.alpha = 0.5f
            binding.tvResendOtp.isEnabled = false
            binding.tvResendOtp.isClickable = false
            binding.tvResendOtp.alpha = 0.5f
            untilMillis?.let {
                val minutesLeft = ceil((it - System.currentTimeMillis()) / 60000.0).toInt().coerceAtLeast(1)
                binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, minutesLeft)
                binding.tvOtpError.isVisible = true
            }
        } else {
            binding.btnLogin.isEnabled = true
            binding.btnLogin.alpha = 1.0f
            // Keep resend disabled by default — it should only be enabled explicitly by the cooldown helper
            binding.tvResendOtp.isEnabled = false
            binding.tvResendOtp.isClickable = false
            binding.tvResendOtp.alpha = 0.5f
            binding.tvOtpError.isVisible = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVerifySignupBinding.inflate(inflater, container, false)
        // Defensive: always start with resend OTP disabled and dimmed until the cooldown helper
        // or an explicit EmailSent action enables it. This prevents a momentary enabled state
        // if the view XML default or other flows briefly set it enabled.
        try {
            binding.tvResendOtp.apply {
                isEnabled = false
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                alpha = 0.5f
                // remove any existing click listener; do not add a touch listener (lint requires performClick)
                setOnClickListener(null)
            }
            binding.otpTimer.isVisible = false
        } catch (_: Exception) { /* ignore if view not ready */ }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.requestApplyInsets(binding.root)
        emailArg = arguments?.getString("email")
        passwordArg = arguments?.getString("password")
        initializeDefaults()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        observeViewModel()

        // Intercept system back presses and show exit-signup confirmation
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitSignupConfirmation()
            }
        })

        // use reusable helper to create the cooldown helper
        cooldownHelper = createOtpCooldownHelper(
            resendCooldownMillis,
            setResendEnabled = { enabled ->
                // Only allow enabling resend when the current email is not locked
                if (!isEmailLocked(emailArg)) {
                    binding.tvResendOtp.isEnabled = enabled
                    binding.tvResendOtp.isClickable = enabled
                } else {
                    binding.tvResendOtp.isEnabled = false
                    binding.tvResendOtp.isClickable = false
                }
            },
            setResendAlpha = { alpha ->
                // reflect disabled state visually when email locked
                if (isEmailLocked(emailArg)) binding.tvResendOtp.alpha = 0.5f else binding.tvResendOtp.alpha = alpha
            },
            setTimerVisible = { visible -> binding.otpTimer.isVisible = visible },
            setTimerText = { text -> binding.otpTimer.text = getString(R.string.resend_in, text) },
            onFinish = {
                viewModel.clearCooldown()
                if (isAdded) {
                    failedAttempts = 0
                    try {
                        // Only restore verify button and clear errors. Do NOT touch resend button here.
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.alpha = 1.0f
                        binding.tvOtpError.isVisible = false
                        // Do not call setLockUI(false) here!
                    } catch (_: Exception) { }
                }
            }
        )

        // Start cooldown immediately if nav-arg token or VM token exists; only when fragment is first created
        if (savedInstanceState == null) {
            startCooldownIfTokenPresent(
                argToken = arguments?.getString("tempToken"),
                vmToken = viewModel.tempToken.value,
                getVmCooldownEndMillis = { viewModel.cooldownEndMillis.value },
                setVmCooldownEndMillis = { end -> viewModel.setCooldownEndMillis(end) },
                cooldownHelper = cooldownHelper,
                cooldownMillis = resendCooldownMillis
            )
        }

        // Resume cooldown from ViewModel if present
        resumeCooldownFromVm(
            getVmCooldownEndMillis = { viewModel.cooldownEndMillis.value },
            clearVmCooldown = {
                viewModel.clearCooldown()
                // ensure local failed attempts reset when VM cooldown cleared (e.g., expired)
                if (isAdded) {
                    failedAttempts = 0
                    try {
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.alpha = 1.0f
                        // keep resend disabled by default; do not enable here so incorrect OTP
                        // errors or resume flows don't prematurely enable resend. Resend should
                        // only be enabled by the cooldown helper or on explicit EmailSent.
                        binding.tvResendOtp.alpha = 0.5f
                        binding.tvOtpError.isVisible = false
                    } catch (_: Exception) { }
                }
            },
             cooldownHelper = cooldownHelper
        )

        // Apply persisted lockout (if any) so UI stays disabled when user returns after server lockout
        checkAndApplyLockout()
        // Also proactively apply lock state (in case lock exists for this emailArg)
        applyLockStateForEmail(emailArg)

        // Restrict OTP input length to 6 digits and clear errors when user types
        binding.etOtp.filters = arrayOf(InputFilter.LengthFilter(6))
        binding.etOtp.addTextChangedListener { _ ->
            binding.tvOtpError.isVisible = false
            InputValidationHelper.clearEditTextInvalid(binding.etOtp, otpTextDefault, normalOtpBgRes)
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
            // Ensure we don't re-enable controls for a locked email when user types OTP
            if (isEmailLocked(emailArg)) {
                binding.btnLogin.isEnabled = false
                binding.btnLogin.alpha = 0.5f
                binding.tvResendOtp.isEnabled = false
                binding.tvResendOtp.alpha = 0.5f
            }
        }
        // Listen for changes to the email input and update lockout state accordingly
        val emailInput = binding.root.findViewById<android.widget.EditText?>(R.id.etEmail)
        emailInput?.addTextChangedListener {
            emailArg = it?.toString()
            applyLockStateForEmail(emailArg)
        }

        // Also guard against any brief re-enablement by posting a runnable to the UI thread
        // which will run after inflation and any view-system updates.
        try {
            binding.root.post {
                try {
                    binding.tvResendOtp.isEnabled = false
                    binding.tvResendOtp.isClickable = false
                    binding.tvResendOtp.isFocusable = false
                    binding.tvResendOtp.alpha = 0.5f
                } catch (_: Exception) { }
            }
            // do not set a touch listener here; rely on isEnabled/isClickable to prevent interactions
        } catch (_: Exception) { }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is SignupViewModel.UiState.Idle -> updateLoading(false)
                        is SignupViewModel.UiState.Loading -> updateLoading(true)
                        is SignupViewModel.UiState.ResendLoading -> {
                            // keep resend disabled while request in-flight; but respect email lock
                            binding.tvResendOtp.isEnabled = false
                            binding.tvResendOtp.alpha = 0.5f
                            // mark resend flow in-flight so any server Error during this flow
                            // is not treated as an OTP verification failure on the client
                            isResendInFlight = true
                        }
                        is SignupViewModel.UiState.EmailSent -> {
                            // resend succeeded; clear in-flight flag
                            isResendInFlight = false
                            updateLoading(false)
                            // Reset failed attempts on successful resend
                            failedAttempts = 0
                            Toast.makeText(requireContext(), getString(R.string.otp_resent), Toast.LENGTH_SHORT).show()
                            val endMillis = System.currentTimeMillis() + resendCooldownMillis
                            viewModel.setCooldownEndMillis(endMillis)
                            // only start cooldown UI if the email isn't locked (otherwise keep controls disabled)
                            if (!isEmailLocked(emailArg)) {
                                cooldownHelper?.start(resendCooldownMillis)
                            } else {
                                // ensure UI shows locked state
                                applyLockStateForEmail(emailArg)
                            }
                        }
                        is SignupViewModel.UiState.Success -> {
                            updateLoading(false)
                            // Reset attempts on success
                            failedAttempts = 0
                            Toast.makeText(requireContext(), "Signing in", Toast.LENGTH_SHORT).show()
                            findNavController().navigate(R.id.action_signupVerificationFragment_to_logoutFragment)
                            viewModel.reset()
                        }
                        is SignupViewModel.UiState.Error -> {
                            updateLoading(false)
                            val message = state.message.ifBlank { getString(R.string.invalid_otp_format) }
                            // If this error happened while a resend is in-flight, and it's not a lockout
                            // message, treat it as a resend failure (do not decrement attempts or show OTP error).
                            if (isResendInFlight && !isTooManyAttempts(message)) {
                                isResendInFlight = false
                                // keep resend disabled until cooldown/timer logic enables it
                                // optionally show a short toast if you want: Toast.makeText(...)
                                return@collect
                            }

                            // Normal error handling for verification attempts or lockout messages
                            showOtpError(message)
                            InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
                            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(redColorInt)

                            // If backend indicates too many attempts, disable local verify and resend controls
                            if (isTooManyAttempts(message)) {
                                // disable verify and resend controls
                                binding.btnLogin.isEnabled = false
                                binding.btnLogin.alpha = 0.5f
                                binding.tvResendOtp.isEnabled = false
                                binding.tvResendOtp.alpha = 0.5f

                                // start a 5-minute lockout cooldown and show the timer
                                val lockDuration = 5 * 60 * 1000L // 5 minutes
                                val lockMillis = System.currentTimeMillis() + lockDuration

                                // persist lockout so it's applied if user navigates away and returns
                                // update ViewModel and start helper so UI timer will run
                                // persist and apply lockout
                                persistLockout(emailArg, lockMillis)
                                try { viewModel.setCooldownEndMillis(lockMillis); cooldownHelper?.startWithEndMillis(lockMillis) } catch (_: Exception) { }
                                setLockUI(true, lockMillis)

                                // show an inline lockout message
                                binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, 5)
                                binding.tvOtpError.isVisible = true
                            } else {
                                // Non-lockout error -> increment local failed attempts and show attempts left
                                failedAttempts = (failedAttempts + 1).coerceAtMost(MAX_FAILED_ATTEMPTS)
                                val attemptsLeft = (MAX_FAILED_ATTEMPTS - failedAttempts)
                                if (attemptsLeft > 0) {
                                    // Show polite inline attempts-left message
                                    binding.tvOtpError.text = getString(R.string.incorrect_otp_attempts_left, attemptsLeft)
                                    binding.tvOtpError.isVisible = true
                                    // Keep verify enabled so user can try again
                                    binding.btnLogin.isEnabled = true
                                    binding.btnLogin.alpha = 1.0f
                                } else {
                                    // Reached max attempts locally -> enforce lockout same as backend would
                                    binding.btnLogin.isEnabled = false
                                    binding.btnLogin.alpha = 0.5f
                                    binding.tvResendOtp.isEnabled = false
                                    binding.tvResendOtp.alpha = 0.5f

                                    val lockDuration = 5 * 60 * 1000L // 5 minutes
                                    val lockMillis = System.currentTimeMillis() + lockDuration
                                    // persist lockout so it's applied if user navigates away and returns
                                    // update ViewModel and start helper so UI timer will run
                                    // persist and apply local lockout
                                    persistLockout(emailArg, lockMillis)
                                    try { viewModel.setCooldownEndMillis(lockMillis); cooldownHelper?.startWithEndMillis(lockMillis) } catch (_: Exception) { }
                                    setLockUI(true, lockMillis)

                                    binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, 5)
                                    binding.tvOtpError.isVisible = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateLoading(loading: Boolean) {
        isLoading = loading
        // Only disable the button while a network operation is in progress.
        // Do not overwrite the enabled state based on OTP validity here — the text watcher
        // manages enabling/disabling as the user types. This allows the user to press
        // the button to trigger validation and see inline errors even when OTP < 6.
        if (loading) {
            binding.btnLogin.isEnabled = false
        }
        // Use BaseFragment loader overlay for consistent UX
        setLoaderVisible(loading)
    }

    private fun initializeDefaults() {
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.ivOtpVerified.visibility = View.VISIBLE
        binding.tvOtpError.isVisible = false
        // reset local failed attempts counter when fragment starts
        failedAttempts = 0
        binding.btnLogin.isEnabled = true
        binding.tvResendOtp.isEnabled = false
        binding.tvResendOtp.isClickable = false
        binding.tvResendOtp.alpha = 0.5f
        binding.otpTimer.isVisible = false
        otpTextDefault = binding.etOtp.textColors
        InputValidationHelper.clearEditTextInvalid(binding.etOtp, otpTextDefault, normalOtpBgRes)
    }

    // Validate OTP input (used by click listeners)
    private fun validateOtp(): Boolean {
        val otp = binding.etOtp.text.toString().trim()
        return when (OtpValidator.validate(otp)) {
            OtpValidator.Result.EMPTY -> {
                showOtpError(getString(R.string.otp_required))
                binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
                false
            }
            OtpValidator.Result.LENGTH -> {
                showOtpError(getString(R.string.otp_must_be_6_digits))
                binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
                InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
                false
            }
            OtpValidator.Result.FORMAT -> {
                showOtpError(getString(R.string.otp_must_be_numeric))
                binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
                InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
                false
            }
            OtpValidator.Result.NONE -> {
                binding.tvOtpError.isVisible = false
                binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#00C853".toColorInt())
                InputValidationHelper.clearEditTextInvalid(binding.etOtp, otpTextDefault, normalOtpBgRes)
                true
            }
        }
    }


    private fun showOtpError(message: String) {
        binding.tvOtpError.text = message
        binding.tvOtpError.isVisible = true
        // Also mirror OTP error to tvEmailError in parent/signup layout if available
        setEmailError(message)
        // Removed automatic scrolling to the error view to prevent keyboard-driven auto-scroll.
        // Keep accessibility notification so screen readers announce the error.
        try {
            binding.tvOtpError.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        } catch (_: Exception) { /* ignore */ }
    }

    // Set an email-level error view if present in the current view hierarchy
    private fun setEmailError(message: String) {
        try {
            var emailErr: TextView? = binding.root.findViewById(R.id.tvEmailError)
            if (emailErr == null) {
                // try parent fragment's view (if this fragment was opened from there)
                emailErr = parentFragment?.view?.findViewById(R.id.tvEmailError)
            }
            if (emailErr == null) {
                // try activity-level view (may exist in a host fragment layout)
                emailErr = requireActivity().findViewById(R.id.tvEmailError)
            }
            emailErr?.let {
                it.text = message
                it.visibility = View.VISIBLE
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun setupClickListeners() {
        binding.apply {
            btnLogin.setOnClickListener {
                if (isEmailLocked(emailArg)) {
                    // show inline lockout message and don't call API
                    binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, 5)
                    binding.tvOtpError.isVisible = true
                    return@setOnClickListener
                }
                if (!validateOtp()) return@setOnClickListener
                val email = emailArg?.trim()
                if (email.isNullOrEmpty()) {
                    showOtpError(getString(R.string.missing_email))
                    return@setOnClickListener
                }
                val otp = etOtp.text?.toString()?.trim().orEmpty()
                hideKeyboard()
                val password = passwordArg ?: ""
                if (password.isNotEmpty()) {
                    viewModel.verifyOtpAndLogin(email, otp, password)
                } else {
                    viewModel.verifyOtp(email, otp)
                }
            }
            tvResendOtp.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    if (isEmailLocked(emailArg)) {
                        // show inline lockout message and don't call API
                        binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, 5)
                        binding.tvOtpError.isVisible = true
                        return@setOnClickListener
                    }
                    if (!isEnabled) return@setOnClickListener
                    etOtp.text?.clear()
                    tvOtpError.isVisible = false
                    ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                    val emailToUse = emailArg ?: ""
                    if (emailToUse.isBlank()) {
                        showOtpError(getString(R.string.missing_email))
                        return@setOnClickListener
                    }
                    val sessionTokenArg = arguments?.getString("tempToken")
                    val vmToken = viewModel.tempToken.value
                    val tokenToUse = when {
                        !sessionTokenArg.isNullOrBlank() -> sessionTokenArg
                        !vmToken.isNullOrBlank() -> vmToken
                        else -> null
                    }
                    if (tokenToUse.isNullOrBlank()) {
                        showOtpError(getString(R.string.missing_token))
                        return@setOnClickListener
                    }
                    // Clear errors since resend was clicked
                    clearOtpAndEmailErrors()
                    isResendInFlight = true // Set flag to ignore any error from server during resend
                    // Start the local 30s resend cooldown immediately so resend stays inactive
                    // even if the server returns an error. The cooldown helper will update the timer UI
                    // and re-enable the resend control when finished.
                    try { cooldownHelper?.start(resendCooldownMillis) } catch (_: Exception) { }
                    viewModel.resendOtp(emailToUse, tokenToUse)
                }
            }
            tvBackToLoginLink.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { showExitSignupConfirmation() }
            }
        }
    }

    private fun showExitSignupConfirmation() {
        try {
            // Use centralized dialog helper so styling/behavior is consistent across the app
            com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(
                requireContext(),
                R.string.exit_signup_title,
                R.string.exit_signup_message,
                onPositive = {
                    try {
                        findNavController().navigate(R.id.nameSignupFragment)
                    } catch (_: Exception) {
                        try { findNavController().popBackStack(R.id.nameSignupFragment, false) } catch (_: Exception) { }
                    }
                },
                themeRes = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
            )
        } catch (_: Exception) { /* ignore UI problems */ }
    }

    // Apply persisted lockout (if any) so UI stays disabled when user returns after server lockout
    private fun checkAndApplyLockout() {
        try {
            val until = getLockoutUntil(emailArg)
            if (until != null && System.currentTimeMillis() < until) {
                setLockUI(true, until)
                try { viewModel.setCooldownEndMillis(until); cooldownHelper?.startWithEndMillis(until) } catch (_: Exception){}
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cooldownHelper?.cancel()
        _binding = null
    }
}
