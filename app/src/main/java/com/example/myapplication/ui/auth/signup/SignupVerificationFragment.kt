package com.example.myapplication.ui.auth.signup

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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

class SignupVerificationFragment : BaseFragment(R.layout.fragment_verify_signup) {
    private var _binding: FragmentVerifySignupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SignupViewModel by viewModels()
    private var emailArg: String? = null
    private var passwordArg: String? = null
    private var isLoading = false

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
        setupTextWatcher()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is SignupViewModel.UiState.Idle -> updateLoading(false)
                        is SignupViewModel.UiState.Loading -> updateLoading(true)
                        is SignupViewModel.UiState.Success -> {
                            updateLoading(false)
                            Toast.makeText(requireContext(), "Signing in", Toast.LENGTH_SHORT).show()
                            viewModel.reset()
                        }
                        is SignupViewModel.UiState.Error -> {
                            updateLoading(false)
                            val message = state.message.ifBlank { getString(R.string.invalid_otp_format) }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                            binding.tvOtpError.text = state.message
                            binding.tvOtpError.isVisible = true
                        }
                    }
                }
            }
        }
    }

    private fun updateLoading(loading: Boolean) {
        isLoading = loading
        binding.btnLogin.isEnabled = !loading && OtpValidator.isValid(binding.etOtp.text?.toString().orEmpty())
    }

    private fun initializeDefaults() {
        binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
        binding.ivOtpVerified.visibility = View.VISIBLE
        binding.tvOtpError.isVisible = false
        binding.btnLogin.isEnabled = false
    }

    private fun setupTextWatcher() {
        binding.etOtp.addTextChangedListener(
            OtpTextWatcher(
                onEmpty = {
                    binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                    binding.btnLogin.isEnabled = !isLoading && false
                },
                onValid = {
                    binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#00C853".toColorInt())
                    binding.btnLogin.isEnabled = !isLoading
                },
                onInvalid = {
                    binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
                    binding.btnLogin.isEnabled = false
                },
                onTyping = {
                    binding.tvOtpError.isVisible = false
                }
            )
        )
    }

    private fun setupClickListeners() {
        binding.apply {
            btnLogin.setOnClickListener {
                if (!validateOtp()) return@setOnClickListener
                val email = emailArg?.trim()
                if (email.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "Missing email. Please go back and try again.", Toast.LENGTH_SHORT).show()
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
                    setTextColor("#2563EB".toColorInt())
                    etOtp.text?.clear()
                    tvOtpError.isVisible = false
                    ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                }
            }
            tvBackToLoginLink.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { navigateToLoginSafe() }
            }
        }
    }

    private fun navigateToLoginSafe() {
        if (!isAdded) return
        val nav = findNavController()
        val actionId = R.id.action_signupVerificationFragment_to_loginFragment
        try {
            val canNavigate = nav.currentDestination?.getAction(actionId) != null
            if (canNavigate) {
                nav.navigate(actionId)
            } else {
                val popped = nav.popBackStack(R.id.loginFragment, false)
                if (!popped) nav.navigate(R.id.loginFragment)
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Verified.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateOtp(): Boolean {
        val otp = binding.etOtp.text.toString().trim()
        return when (OtpValidator.validate(otp)) {
            OtpValidator.Result.EMPTY -> {
                binding.tvOtpError.text = getString(R.string.otp_required)
                binding.tvOtpError.isVisible = true
                binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
                false
            }
            OtpValidator.Result.LENGTH, OtpValidator.Result.FORMAT -> {
                binding.tvOtpError.text = getString(R.string.invalid_otp_format)
                binding.tvOtpError.isVisible = true
                binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#F44336".toColorInt())
                false
            }
            OtpValidator.Result.NONE -> {
                binding.tvOtpError.isVisible = false
                binding.ivOtpVerified.imageTintList = ColorStateList.valueOf("#00C853".toColorInt())
                true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
