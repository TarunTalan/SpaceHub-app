package com.example.myapplication.ui.auth.password

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.OtpResendCooldownHelper
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentVerifyForgotPasswordBinding
import com.example.myapplication.ui.auth.reset.ResetPasswordViewModel
import com.example.myapplication.data.network.SharedPrefsTokenStore
import kotlinx.coroutines.launch
import com.example.myapplication.ui.common.createOtpCooldownHelper
import com.example.myapplication.ui.common.startCooldownIfTokenPresent
import com.example.myapplication.ui.common.resumeCooldownFromVm

class ForgotPasswordVerificationFragment : BaseFragment(R.layout.fragment_verify_forgot_password) {

    private var _binding: FragmentVerifyForgotPasswordBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ResetPasswordViewModel by viewModels()

    private var emailArg: String? = null

    // Use shared helper with 30s cooldown to match signup flow
    private var resendCooldownMillis: Long = 30_000L // 30 seconds
    private var cooldownHelper: OtpResendCooldownHelper? = null

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
        setupTextWatcher()
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
    }

    private fun setupTextWatcher() {
        binding.etOtp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val otp = s?.toString()?.trim() ?: ""

                when {
                    otp.isEmpty() -> {
                        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                        binding.tvOtpError.isVisible = false
                    }
                    otp.length == 6 && otp.matches(Regex("^[0-9]{6}$")) -> {
                        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#00C853".toColorInt())
                        binding.tvOtpError.isVisible = false
                    }
                    else -> {
                        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
                        binding.tvOtpError.isVisible = false
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
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
                                Toast.makeText(requireContext(), getString(R.string.missing_email), Toast.LENGTH_SHORT).show()
                                return@collect
                            }

                            val bundle = Bundle().apply {
                                putString("email", emailArg)
                                putString("tempToken", tokenToSend)
                            }

                            try {
                                findNavController().navigate(R.id.newPasswordFragment, bundle)
                            } catch (_: Exception) {
                                // Navigation failed — show a user-friendly message
                                Toast.makeText(requireContext(), getString(R.string.navigation_failed), Toast.LENGTH_SHORT).show()
                            }

                            viewModel.reset()
                        }
                        is ResetPasswordViewModel.UiState.Error -> {
                            binding.progressBar.isVisible = false
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                            binding.tvOtpError.text = state.message
                            binding.tvOtpError.isVisible = true
                            // On error, if helper is not running, re-enable resend so user can try again
                            // Helper will manage states if cooldown is active
                            if (viewModel.cooldownEndMillis.value == null) {
                                binding.tvResendOtp.isEnabled = true
                                binding.tvResendOtp.alpha = 1.0f
                            }
                        }
                        else -> {
                            // Idle / other states handled above
                        }
                    }
                }
            }
        }
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
                    setTextColor("#2563EB".toColorInt())
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
                            try { findNavController().popBackStack(R.id.loginFragment, false) } catch (_: Exception) { /* ignore */ }
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
            return false
        }

        if (otp.length != 6) {
            binding.tvOtpError.text = getString(R.string.otp_must_be_6_digits)
            binding.tvOtpError.isVisible = true
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
            return false
        }

        if (!otp.matches(Regex("^[0-9]{6}$"))) {
            binding.tvOtpError.text = getString(R.string.invalid_otp_format)
            binding.tvOtpError.isVisible = true
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
            return false
        }

        binding.tvOtpError.isVisible = false
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#00C853".toColorInt())
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cooldownHelper?.cancel()
        _binding = null
    }
}