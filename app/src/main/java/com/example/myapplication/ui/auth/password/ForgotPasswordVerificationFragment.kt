package com.example.myapplication.ui.auth.password

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.databinding.FragmentVerifyForgotPasswordBinding
import com.example.myapplication.ui.auth.common.InputValidationHelper
import com.example.myapplication.ui.auth.reset.ResetPasswordViewModel
import com.example.myapplication.ui.common.*
import kotlinx.coroutines.launch
import java.util.*
import android.content.res.ColorStateList as CSList
import androidx.core.content.edit

class ForgotPasswordVerificationFragment : BaseFragment(R.layout.fragment_verify_forgot_password) {

    private var _binding: FragmentVerifyForgotPasswordBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ResetPasswordViewModel by viewModels()

    private var emailArg: String? = null

    // Use shared helper with 30s cooldown to match signup flow
    private var resendCooldownMillis: Long = 30_000L // 30 seconds
    private var cooldownHelper: OtpResendCooldownHelper? = null

    // OTP visuals defaults
    private lateinit var otpTextDefault: CSList
    private val redColorInt by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }
    private val normalOtpBgRes = R.drawable.edit_text_outline_selector
    private val errorOtpBgRes = R.drawable.edit_text_outline_error


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVerifyForgotPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.requestApplyInsets(binding.root)
        emailArg = arguments?.getString("email")

        initializeDefaults()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        observeViewModel()

        // instantiate reusable cooldown helper
        cooldownHelper = createOtpCooldownHelper(
            resendCooldownMillis,
            setResendEnabled = { enabled -> binding.tvResendOtp.isEnabled = enabled },
            setResendAlpha = { alpha -> binding.tvResendOtp.alpha = alpha },
            setTimerVisible = { visible -> binding.otpTimer.isVisible = visible },
            setTimerText = { text -> binding.otpTimer.text = getString(R.string.resend_in, text) },
            onFinish = { viewModel.clearCooldown() }
        )

        // Start cooldown if token present (nav arg or ViewModel) only when fragment is first created.
        // This prevents creating a brand new countdown on configuration changes (rotation).
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

        // Always attempt to resume any existing cooldown persisted in ViewModel
        resumeCooldownFromVm(
            getVmCooldownEndMillis = { viewModel.cooldownEndMillis.value },
            clearVmCooldown = { viewModel.clearCooldown() },
            cooldownHelper = cooldownHelper
        )

        // Apply persisted lockout (if present) so UI shows timer and message
        checkAndApplyLockout()

        // Restrict OTP input length to 6 digits and clear errors when user types
        binding.etOtp.filters = arrayOf(InputFilter.LengthFilter(6))
        binding.etOtp.addTextChangedListener { _ ->
            binding.tvOtpError.isVisible = false
            InputValidationHelper.clearEditTextInvalid(binding.etOtp, otpTextDefault, normalOtpBgRes)
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
    }

    private fun initializeDefaults() {
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.ivOtpVerified.visibility = View.VISIBLE
        binding.tvOtpError.isVisible = false
        // hide timer initially
        binding.otpTimer.isVisible = false
        // Ensure resend link is disabled and faded initially until server confirms EmailSent
        binding.tvResendOtp.isEnabled = false
        binding.tvResendOtp.alpha = 0.5f

        // Ensure OTP input uses normal outline initially
        otpTextDefault = binding.etOtp.textColors
        InputValidationHelper.clearEditTextInvalid(binding.etOtp, otpTextDefault, normalOtpBgRes)
    }

    private fun setupClickListeners() {
        binding.apply {
            btnLogin.setOnClickListener {
                if (validateOtp()) {
                    // call ViewModel to verify OTP
                    val email = emailArg ?: ""
                    val otp = etOtp.text.toString().trim()
                    viewModel.verifyForgotOtp(email, otp)
                }
            }

            tvResendOtp.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    if (!isEnabled) return@setOnClickListener
                    setTextColor(ColorStateList.valueOf(Color.WHITE))
                    etOtp.text?.clear()
                    tvOtpError.isVisible = false
                    ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                    // trigger resend via ViewModel: use tempToken from server if available, otherwise fallback to initial request
                    val tokenToUse = viewModel.tempToken.value
                    if (!tokenToUse.isNullOrBlank()) {
                        viewModel.resendForgotOtp(tokenToUse)
                    } else {
                        // fallback to requesting a fresh OTP using the email
                        emailArg?.let { viewModel.requestForgotPassword(it) }
                    }
                    // cooldown will start after EmailSent (success); UI will be disabled while the request is in-flight
                }
            }

            tvBackToLoginLink.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    try {
                        findNavController().navigate(R.id.action_forgotPasswordVerificationFragment_to_loginFragment)
                    } catch (_: Exception) {
                        try {
                            findNavController().navigate(R.id.loginFragment)
                        } catch (_: Exception) {
                            try {
                                findNavController().popBackStack(R.id.loginFragment, false)
                            } catch (_: Exception) { /* ignore */
                            }
                        }
                    }
                }
            }
        }
    }

    private fun validateOtp(): Boolean {
        val otp = binding.etOtp.text.toString().trim()

        if (otp.isEmpty()) {
            binding.tvOtpError.text = getString(R.string.otp_required)
            binding.tvOtpError.isVisible = true
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
            // show error outline
            InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
            return false
        }

        if (otp.length != 6) {
            binding.tvOtpError.text = getString(R.string.otp_must_be_6_digits)
            binding.tvOtpError.isVisible = true
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
            // show error outline
            InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
            return false
        }

        if (!otp.matches(Regex("^[0-9]{6}$"))) {
            binding.tvOtpError.text = getString(R.string.invalid_otp_format)
            binding.tvOtpError.isVisible = true
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
            // show error outline
            InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
            return false
        }

        binding.tvOtpError.isVisible = false
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#00C853".toColorInt())
        // restore normal outline
        InputValidationHelper.clearEditTextInvalid(binding.etOtp, otpTextDefault, normalOtpBgRes)
        return true
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // show/hide a simple progress indicator if present
                    binding.progressBar.let { it.isVisible = state is ResetPasswordViewModel.UiState.Loading }

                    when (state) {
                        is ResetPasswordViewModel.UiState.Loading -> {
                            // Disable resend while generic loading (e.g., requestForgotPassword or verify) is in progress
                            // If helper is running, it will keep resend disabled; otherwise dim it
                            if (viewModel.cooldownEndMillis.value == null) {
                                binding.tvResendOtp.isEnabled = false
                                binding.tvResendOtp.alpha = 0.5f
                            }
                        }

                        is ResetPasswordViewModel.UiState.ResendLoading -> {
                            // Explicit resend call in-flight: always disable/dim resend
                            binding.tvResendOtp.isEnabled = false
                            binding.tvResendOtp.alpha = 0.5f
                        }

                        is ResetPasswordViewModel.UiState.EmailSent -> {
                            // On initial EmailSent (OTP requested) or after resend, show feedback and restart cooldown
                            binding.progressBar.isVisible = false
                            Toast.makeText(requireContext(), getString(R.string.otp_resent), Toast.LENGTH_SHORT).show()

                            // Persist cooldown end millis in ViewModel so rotations won't reset it
                            val endMillis = System.currentTimeMillis() + resendCooldownMillis
                            viewModel.setCooldownEndMillis(endMillis)

                            // Start the cooldown timer using helper
                            cooldownHelper?.start(resendCooldownMillis)
                        }

                        is ResetPasswordViewModel.UiState.OtpVerified -> {
                            binding.progressBar.isVisible = false
                            // Use tempToken provided by ViewModel when available, otherwise fall back to stored token, argument, or entered OTP
                            val vmToken = state.tempToken
                            val prefsToken = try { SharedPrefsTokenStore(requireContext()).getAccessToken() } catch (_: Exception) { null }
                            val vmStoredToken = viewModel.tempToken.value
                            val tokenToSend = vmToken ?: prefsToken ?: vmStoredToken ?: binding.etOtp.text.toString().trim()

                            if (emailArg.isNullOrBlank()) {
                                // replace toast with inline error
                                binding.tvOtpError.text = getString(R.string.missing_email)
                                binding.tvOtpError.isVisible = true
                                return@collect
                            }

                            val bundle = Bundle().apply {
                                putString("email", emailArg)
                                putString("tempToken", tokenToSend)
                            }

                            try {
                                findNavController().navigate(R.id.newPasswordFragment, bundle)
                            } catch (_: Exception) {
                                // Navigation failed — show inline error
                                binding.tvOtpError.text = getString(R.string.navigation_failed)
                                binding.tvOtpError.isVisible = true
                            }

                            viewModel.reset()
                        }

                        is ResetPasswordViewModel.UiState.Error -> {
                            binding.progressBar.isVisible = false
                            // Removed error toast; show inline error in tvOtpError
                            binding.tvOtpError.text = state.message
                            binding.tvOtpError.isVisible = true

                            // show red outline on error
                            InputValidationHelper.applyEditTextInvalid(binding.etOtp, redColorInt, errorOtpBgRes)
                            // show red tick for invalid OTP
                            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(redColorInt)

                            // On error, if helper is not running, re-enable resend so user can try again
                            // Helper will manage states if cooldown is active
                            if (viewModel.cooldownEndMillis.value == null) {
                                binding.tvResendOtp.isEnabled = true
                                binding.tvResendOtp.alpha = 1.0f
                            }

                            // If backend indicates too many attempts, disable login and signup navigation
                            if (isTooManyAttempts(state.message)) {
                                // disable local verify button and resend
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
                                    prefs.edit { putLong("forgot_otp_lockout_until", lockMillis) }
                                } catch (_: Exception) { /* ignore */ }

                                // update ViewModel and start helper so UI timer will run
                                try {
                                    viewModel.setCooldownEndMillis(lockMillis)
                                    cooldownHelper?.startWithEndMillis(lockMillis)
                                } catch (_: Exception) { /* ignore */ }

                                // show an inline lockout message
                                binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, 5)
                                binding.tvOtpError.isVisible = true
                            }

                        }

                        else -> {
                            // Idle, PasswordReset or other states: nothing special to do
                        }
                    }
                }
            }
        }
    }

    private fun isTooManyAttempts(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val msg = message.lowercase(Locale.getDefault())
        return msg.contains("too many") || (msg.contains("attempt") && msg.contains("limit")) || msg.contains("temporarily disabled") || msg.contains("blocked")
    }

    private fun checkAndApplyLockout() {
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val until = prefs.getLong("forgot_otp_lockout_until", 0L)
            if (System.currentTimeMillis() < until) {
                // apply UI lockout and resume timer from persisted end millis
                binding.btnLogin.isEnabled = false
                binding.btnLogin.alpha = 0.5f
                binding.tvResendOtp.isEnabled = false
                binding.tvResendOtp.alpha = 0.5f

                // set ViewModel cooldown and start helper to show timer
                try {
                    viewModel.setCooldownEndMillis(until)
                    cooldownHelper?.startWithEndMillis(until)
                } catch (_: Exception) { /* ignore */ }

                // show an inline lockout message
                binding.tvOtpError.text = getString(R.string.too_many_attempts_try_again, 5)
                binding.tvOtpError.isVisible = true
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cooldownHelper?.cancel()
        _binding = null
    }
}
