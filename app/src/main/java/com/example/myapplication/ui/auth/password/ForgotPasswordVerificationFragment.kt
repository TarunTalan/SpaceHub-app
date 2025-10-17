package com.example.myapplication.ui.auth.password

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.myapplication.ui.common.BaseFragment
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentVerifyForgotPasswordBinding

class ForgotPasswordVerificationFragment : BaseFragment(R.layout.fragment_verify_forgot_password) {

    private var _binding: FragmentVerifyForgotPasswordBinding? = null
    private val binding get() = _binding!!

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
        // Request insets only; remove extra scroll helpers
        ViewCompat.requestApplyInsets(binding.root)
        initializeDefaults()
        setupTextWatcher()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
    }

    private fun initializeDefaults() {
        // Set verification tick to white by default and always visible
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.ivOtpVerified.visibility = View.VISIBLE
        binding.tvOtpError.isVisible = false
    }

    private fun setupTextWatcher() {
        binding.etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val otp = s?.toString()?.trim() ?: ""

                when {
                    otp.isEmpty() -> {
                        // White tick when empty
                        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                        binding.tvOtpError.isVisible = false
                    }
                    otp.length == 6 && otp.matches(Regex("^[0-9]{6}$")) -> {
                        // Valid OTP - show green tick
                        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#00C853".toColorInt())
                        binding.tvOtpError.isVisible = false
                    }
                    else -> {
                        // Invalid OTP - show red tick
                        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
                        binding.tvOtpError.isVisible = false
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupClickListeners() {
        binding.apply {
            // Verify OTP button
            btnLogin.setOnClickListener {
                if (validateOtp()) {
                    // Navigate to new password screen
                    findNavController().navigate(R.id.action_forgotPasswordVerificationFragment_to_newPasswordFragment)
                }
            }

            // Resend OTP link
            tvResendOtp.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    // Change color to blue when clicked
                    setTextColor("#2563EB".toColorInt())
                    // TODO: Implement resend OTP logic
                    // For now, just show a message or clear the OTP field
                    etOtp.text?.clear()
                    tvOtpError.isVisible = false
                    ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                }
            }

            // Back to login link with underline
            tvBackToLoginLink.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    findNavController().navigate(R.id.action_forgotPasswordVerificationFragment_to_loginFragment)
                }
            }
        }
    }


    /**
     * Validates OTP input.
     * @return true if OTP is valid, false otherwise
     */
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
        _binding = null
    }
}