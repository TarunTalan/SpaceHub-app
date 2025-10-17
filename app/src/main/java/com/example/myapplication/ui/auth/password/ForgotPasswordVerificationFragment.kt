package com.example.myapplication.ui.auth.password

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.example.myapplication.ui.common.BaseFragment
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

class ForgotPasswordVerificationFragment : BaseFragment(R.layout.fragment_verify_forgot_password) {

    private var _binding: FragmentVerifyForgotPasswordBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ResetPasswordViewModel by viewModels()

    private var emailArg: String? = null
    private var tempTokenArg: String? = null
    private var resendCooldownMillis: Long = 2 * 60 * 1000 // 2 minutes
    private var timer: CountDownTimer? = null

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
        tempTokenArg = arguments?.getString("tempToken")

        initializeDefaults()
        setupTextWatcher()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        observeViewModel()
        // Start initial cooldown so user can't spam resend immediately after navigation
        startResendOtpTimer()
    }

    private fun initializeDefaults() {
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.ivOtpVerified.visibility = View.VISIBLE
        binding.tvOtpError.isVisible = false
        // hide timer initially if not present in layout it's fine
        binding.otpTimer.isVisible = false
    }

    private fun setupTextWatcher() {
        binding.etOtp.addTextChangedListener(object : TextWatcher {
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

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // show/hide a simple progress indicator if present
                    binding.progressBar.let { it.isVisible = state is ResetPasswordViewModel.UiState.Loading }

                    when (state) {
                        is ResetPasswordViewModel.UiState.EmailSent -> {
                            // On initial EmailSent (OTP requested), just show user-facing feedback and restart cooldown
                            binding.progressBar.isVisible = false
                            Toast.makeText(requireContext(), getString(R.string.otp_resent), Toast.LENGTH_SHORT).show()
                            startResendOtpTimer()
                        }
                        is ResetPasswordViewModel.UiState.OtpVerified -> {
                            binding.progressBar.isVisible = false
                            // Use tempToken provided by ViewModel when available, otherwise fall back to stored token, argument, or entered OTP
                            val vmToken = state.tempToken
                            val prefsToken = try { SharedPrefsTokenStore(requireContext()).getAccessToken() } catch (_: Exception) { null }
                            val tokenToSend = vmToken ?: prefsToken ?: tempTokenArg ?: binding.etOtp.text.toString().trim()

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
                        }
                        else -> {
                            // Idle / Loading handled above
                        }
                    }
                }
            }
        }
    }

    private fun startResendOtpTimer() {
        timer?.cancel()
        binding.tvResendOtp.isEnabled = false
        binding.otpTimer.isVisible = true
        timer = object : CountDownTimer(resendCooldownMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val min = seconds / 60
                val sec = seconds % 60
                binding.otpTimer.text = String.format(java.util.Locale.getDefault(), "%d:%02d", min, sec)
            }

            override fun onFinish() {
                binding.otpTimer.text = getString(R.string.otp_timer_zero)
                binding.tvResendOtp.isEnabled = true
            }
        }.start()
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
                    // trigger resend via ViewModel
                    emailArg?.let { viewModel.requestForgotPassword(it) }
                    // start cooldown; we will also restart from EmailSent handler
                    startResendOtpTimer()
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
        timer?.cancel()
        _binding = null
    }
}