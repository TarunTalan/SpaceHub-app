package com.example.myapplication.ui.auth.signup

import android.content.Context
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

    // Helper to check if the email is locked (robust: checks multiple variants to cover older persisted keys)
    private fun isEmailLocked(email: String?): Boolean {
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val variants = mutableListOf<String?>()
            val norm = normalizeEmail(email)
            variants.add(norm)
            // also check raw and trimmed variants in case previous code saved under different form
            variants.add(email)
            variants.add(email?.trim())
            // dedupe while preserving null handling
            val checked = linkedSetOf<String?>()
            variants.forEach { checked.add(it) }
            // check both signup and forgot prefixes so a lock from either flow applies
            val prefixes = listOf("signup_otp_lockout_until_", "forgot_otp_lockout_until_")
            for (v in checked) {
                for (p in prefixes) {
                    val key = p + (v ?: "unknown")
                    val until = prefs.getLong(key, 0L)
                    if (now < until) return true
                }
            }
        } catch (_: Exception) {  }
        return false
    }

    // Apply UI lock state for a specific email: disable/dim controls and show message if locked
    private fun applyLockStateForEmail(email: String?) {
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // gather variants and prefixes to search
            val variants = listOf(normalizeEmail(email), email, email?.trim())
            val prefixes = listOf("signup_otp_lockout_until_", "forgot_otp_lockout_until_")
            var foundUntil = 0L
            var locked = false
            for (v in variants) {
                for (p in prefixes) {
                    val key = p + (v ?: "unknown")
                    val saved = prefs.getLong(key, 0L)
                    if (saved > now) {
                        foundUntil = saved
                        locked = true
                        break
                    }
                }
                if (locked) break
            }

            if (locked) {
                binding.btnLogin.isEnabled = false
                binding.btnLogin.alpha = 0.5f
                binding.tvResendOtp.isEnabled = false
                binding.tvResendOtp.alpha = 0.5f
                val minutesLeft = Math.ceil((foundUntil - System.currentTimeMillis()) / 60000.0).toInt().coerceAtLeast(1)
                binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, minutesLeft)
                binding.tvOtpError.isVisible = true
                // start/resume visual timer in case ViewModel needs it
                try { viewModel.setCooldownEndMillis(foundUntil); cooldownHelper?.startWithEndMillis(foundUntil) } catch (_: Exception) {}
            } else {
                // if there's an active cooldown in VM for another reason, keep resend disabled accordingly
                val vmCooldown = viewModel.cooldownEndMillis.value
                if (vmCooldown == null || System.currentTimeMillis() >= vmCooldown) {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.alpha = 1.0f
                    binding.tvResendOtp.isEnabled = true
                    binding.tvResendOtp.alpha = 1.0f
                    binding.tvOtpError.isVisible = false
                } else {
                    // VM cooldown active — respect it
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.alpha = 1.0f
                    binding.tvResendOtp.isEnabled = false
                    binding.tvResendOtp.alpha = 0.5f
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVerifySignupBinding.inflate(inflater, container, false)
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
                } else {
                    binding.tvResendOtp.isEnabled = false
                }
            },
            setResendAlpha = { alpha ->
                // reflect disabled state visually when email locked
                if (isEmailLocked(emailArg)) binding.tvResendOtp.alpha = 0.5f else binding.tvResendOtp.alpha = alpha
            },
            setTimerVisible = { visible -> binding.otpTimer.isVisible = visible },
            setTimerText = { text -> binding.otpTimer.text = getString(R.string.resend_in, text) },
            onFinish = {
                // clear VM cooldown marker
                viewModel.clearCooldown()
                // when cooldown finishes, reset local attempt counter and restore controls only if email not locked
                if (isAdded) {
                    failedAttempts = 0
                    try {
                        if (!isEmailLocked(emailArg)) {
                            binding.btnLogin.isEnabled = true
                            binding.btnLogin.alpha = 1.0f
                            binding.tvResendOtp.isEnabled = true
                            binding.tvResendOtp.alpha = 1.0f
                        } else {
                            binding.btnLogin.isEnabled = false
                            binding.btnLogin.alpha = 0.5f
                            binding.tvResendOtp.isEnabled = false
                            binding.tvResendOtp.alpha = 0.5f
                        }
                        binding.tvOtpError.isVisible = false
                    } catch (_: Exception) {
                        // view may be gone; ignore
                    }
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
                        binding.tvResendOtp.isEnabled = true
                        binding.tvResendOtp.alpha = 1.0f
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
                        }
                        is SignupViewModel.UiState.EmailSent -> {
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
                            showOtpError(message)
                            InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
                            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(redColorInt)
                            binding.tvResendOtp.isEnabled = true
                            binding.tvResendOtp.alpha = 1.0f
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
                                try {
                                    val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    val lockoutKey = getLockoutKey(emailArg)
                                    prefs.edit { putLong(lockoutKey, lockMillis) }
                                } catch (_: Exception) { /* ignore */ }

                                // update ViewModel and start helper so UI timer will run
                                try {
                                    viewModel.setCooldownEndMillis(lockMillis)
                                    // start visual countdown only if this fragment's email matches lock key
                                    cooldownHelper?.startWithEndMillis(lockMillis)
                                } catch (_: Exception) { /* ignore */ }

                                // ensure UI immediately reflects the persisted lock for current email
                                applyLockStateForEmail(emailArg)

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
                                    try {
                                        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        val lockoutKey = getLockoutKey(emailArg)
                                        prefs.edit { putLong(lockoutKey, lockMillis) }
                                    } catch (_: Exception) { /* ignore */ }

                                    try {
                                        viewModel.setCooldownEndMillis(lockMillis)
                                        cooldownHelper?.startWithEndMillis(lockMillis)
                                    } catch (_: Exception) { /* ignore */ }

                                    // ensure UI reflects local enforced lock
                                    applyLockStateForEmail(emailArg)

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
    }

    private fun initializeDefaults() {
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.ivOtpVerified.visibility = View.VISIBLE
        binding.tvOtpError.isVisible = false
        // reset local failed attempts counter when fragment starts
        failedAttempts = 0
        // Start with the button enabled so the user can press it to trigger validation
        // and see inline errors (e.g., "OTP must be 6 digits"). The text watcher will
        // keep the button enabled only when OTP looks valid while typing.
        binding.btnLogin.isEnabled = true
        binding.tvResendOtp.isEnabled = false
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
                    setTextColor("#2563EB".toColorInt())
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
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val lockoutKey = getLockoutKey(emailArg)
            val until = prefs.getLong(lockoutKey, 0L)
            if (System.currentTimeMillis() < until) {
                // disable verify/resend controls
                binding.btnLogin.isEnabled = false
                binding.btnLogin.alpha = 0.5f
                binding.tvResendOtp.isEnabled = false
                binding.tvResendOtp.alpha = 0.5f
                // show an inline lockout message and start a timer to update it
                val minutesLeft = Math.ceil((until - System.currentTimeMillis()) / 60000.0).toInt()
                binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, minutesLeft)
                binding.tvOtpError.isVisible = true
                // resume viewmodel/visual timer if helpful
                try { viewModel.setCooldownEndMillis(until); cooldownHelper?.startWithEndMillis(until) } catch (_: Exception) {}
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cooldownHelper?.cancel()
        _binding = null
    }
}
