package com.example.myapplication.ui.auth.password

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentNewPasswordBinding
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.auth.reset.ResetPasswordViewModel
import com.example.myapplication.ui.common.BaseFragment
import androidx.navigation.fragment.findNavController

class NewPasswordFragment : BaseFragment(R.layout.fragment_new_password) {

    private var _binding: FragmentNewPasswordBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ResetPasswordViewModel by viewModels()
    private var emailArg: String? = null
    private var tempTokenArg: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.requestApplyInsets(binding.root)
        PasswordToggleUtil.attach(binding.passwordLayout, binding.etPassword)
        PasswordToggleUtil.attach(binding.confirmPasswordLayout, binding.etConfirmPassword)
        emailArg = arguments?.getString("email")
        tempTokenArg = arguments?.getString("tempToken")
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        observeViewModel()
    }

    private fun observeViewModel() {
        // Use fragment lifecycleScope and repeatOnLifecycle for lifecycle-safe collection
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // centralized progress handling using view binding's progress bar (exists in layout)
                    binding.progressBar.isVisible = state is ResetPasswordViewModel.UiState.Loading

                    when (state) {
                        is ResetPasswordViewModel.UiState.PasswordReset -> {
                            // After password reset and automatic login, do not navigate to the login screen.
                            // Show a concise user message only.
                            android.widget.Toast.makeText(requireContext(), "Password changed. Logging in", android.widget.Toast.LENGTH_SHORT).show()
                            // Keep the ViewModel state reset so UI can return to idle.
                            viewModel.reset()
                        }

                        is ResetPasswordViewModel.UiState.Error -> {
                            val msg = state.message
                            binding.tvPasswordError.text = msg
                            binding.tvPasswordError.isVisible = true

                            // If server indicates OTP not validated or token expired, guide user to request a new OTP
                            val lower = msg.lowercase()
                            if ("otp not validated" in lower || "token expired" in lower || "unauthorized" in lower) {
                                android.widget.Toast.makeText(requireContext(), getString(R.string.otp_invalid_or_expired), android.widget.Toast.LENGTH_LONG).show()
                                // navigate back to reset password screen so user can request a new OTP
                                try {
                                    findNavController().navigate(R.id.resetPasswordFragment)
                                } catch (_: Exception) {
                                    // ignore navigation failure
                                }
                            }
                        }

                        else -> {
                            // Idle or other states: nothing else to do
                        }
                    }
                }
            }
        }

    }

    private fun setupClickListeners() {
        binding.apply {
            btnLogin.setOnClickListener {
                val password = etPassword.text.toString().trim()
                val confirmPassword = etConfirmPassword.text.toString().trim()
                if (!validatePasswords(password, confirmPassword)) return@setOnClickListener
                val email = emailArg ?: ""
                val tempToken = tempTokenArg ?: ""

                if (tempToken.isBlank()) {
                    // If token missing, inform user and do not proceed
                    android.widget.Toast.makeText(requireContext(), getString(R.string.missing_token), android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                viewModel.resetPassword(email, password, tempToken)
            }
            tvBackToLoginLink.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    try {
                        findNavController().navigate(R.id.action_newPasswordFragment_to_loginFragment)
                    } catch (_: Exception) {
                        try { findNavController().navigate(R.id.loginFragment) } catch (_: Exception) { /* ignore */ }
                    }
                }
            }
        }
    }

    private fun validatePasswords(password: String, confirmPassword: String): Boolean {
        if (password.length < 6) {
            binding.tvPasswordError.text = getString(R.string.password_min_6)
            binding.tvPasswordError.visibility = View.VISIBLE
            return false
        }
        if (password != confirmPassword) {
            binding.tvPasswordError.text = getString(R.string.passwords_do_not_match)
            binding.tvPasswordError.visibility = View.VISIBLE
            return false
        }
        binding.tvPasswordError.visibility = View.INVISIBLE
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}