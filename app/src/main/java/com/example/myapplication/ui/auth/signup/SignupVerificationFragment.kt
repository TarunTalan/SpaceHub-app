package com.example.myapplication.ui.auth.signup

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import com.example.myapplication.ui.common.OtpResendCooldownHelper
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
import com.example.myapplication.ui.common.createOtpCooldownHelper
import com.example.myapplication.ui.common.startCooldownIfTokenPresent
import com.example.myapplication.ui.common.resumeCooldownFromVm

import com.example.myapplication.ui.auth.common.InputValidationHelper
import androidx.core.content.ContextCompat
import android.text.InputFilter
import androidx.core.widget.addTextChangedListener

class SignupVerificationFragment : BaseFragment(R.layout.fragment_verify_signup) {
    private var _binding: FragmentVerifySignupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SignupViewModel by viewModels()
    private var emailArg: String? = null
    private var passwordArg: String? = null
    private var isLoading = false
    // cooldown between resend OTP requests (30 seconds)
    private var resendCooldownMillis: Long = 30_000L // 30 seconds
    private var cooldownHelper: OtpResendCooldownHelper? = null

    // defaults for OTP visuals
    private lateinit var otpTextDefault: ColorStateList
    private val redColorInt by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }
    private val normalOtpBgRes = R.drawable.edit_text_outline_selector
    private val errorOtpBgRes = R.drawable.edit_text_outline_error

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

        // use reusable helper to create the cooldown helper
        cooldownHelper = createOtpCooldownHelper(
            resendCooldownMillis,
            setResendEnabled = { enabled -> binding.tvResendOtp.isEnabled = enabled },
            setResendAlpha = { alpha -> binding.tvResendOtp.alpha = alpha },
            setTimerVisible = { visible -> binding.otpTimer.isVisible = visible },
            setTimerText = { text -> binding.otpTimer.text = getString(R.string.resend_in, text) },
            onFinish = { viewModel.clearCooldown() }
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
            clearVmCooldown = { viewModel.clearCooldown() },
            cooldownHelper = cooldownHelper
        )

        // Restrict OTP input length to 6 digits and clear errors when user types
        binding.etOtp.filters = arrayOf(InputFilter.LengthFilter(6))
        binding.etOtp.addTextChangedListener { _ ->
            binding.tvOtpError.isVisible = false
            InputValidationHelper.clearEditTextInvalid(binding.etOtp, otpTextDefault, normalOtpBgRes)
            binding.ivOtpVerified.imageTintList = ColorStateList.valueOf(Color.WHITE)
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
                            binding.tvResendOtp.isEnabled = false
                            binding.tvResendOtp.alpha = 0.5f
                        }
                        is SignupViewModel.UiState.EmailSent -> {
                            updateLoading(false)
                            Toast.makeText(requireContext(), getString(R.string.otp_resent), Toast.LENGTH_SHORT).show()
                            val endMillis = System.currentTimeMillis() + resendCooldownMillis
                            viewModel.setCooldownEndMillis(endMillis)
                            cooldownHelper?.start(resendCooldownMillis)
                        }
                        is SignupViewModel.UiState.Success -> {
                            updateLoading(false)
                            Toast.makeText(requireContext(), "Signing in", Toast.LENGTH_SHORT).show()
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

    private fun showOtpError(message: String) {
        binding.tvOtpError.text = message
        binding.tvOtpError.isVisible = true
        binding.scrollRoot.post {
            binding.scrollRoot.smoothScrollTo(0, binding.tvOtpError.top)
            binding.tvOtpError.bringToFront()
            binding.tvOtpError.requestLayout()
            binding.tvOtpError.invalidate()
            binding.tvOtpError.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            btnLogin.setOnClickListener {
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
                setOnClickListener { navigateToLoginSafe() }
            }
        }
    }

    private fun navigateToLoginSafe() {
        if (!isAdded) return
        val nav = findNavController()
        // First try to pop back to an existing login fragment in the back stack
        try {
            val popped = nav.popBackStack(R.id.loginFragment, false)
            if (popped) return
        } catch (_: Exception) {
            // ignore and try navigate below
        }

        // Try to navigate directly using this NavController
        try {
            nav.navigate(R.id.loginFragment)
            return
        } catch (_: IllegalArgumentException) {
            // destination not found in this nav controller's graph; fall through to activity controller
        } catch (_: Exception) {
            // some other navigation error; fall through to activity controller
        }

        // Fallback: try to use NavController from activity's nav host (if different)
        try {
            val hostFragment = requireActivity().supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            val activityNav = (hostFragment as? androidx.navigation.fragment.NavHostFragment)?.navController
            if (activityNav != null) {
                val popped = activityNav.popBackStack(R.id.loginFragment, false)
                if (popped) return
                activityNav.navigate(R.id.loginFragment)
                return
            }
        } catch (_: Exception) {
            // ignore and show generic error below
        }

        Toast.makeText(requireContext(), getString(R.string.navigation_error), Toast.LENGTH_SHORT).show()
    }

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

    override fun onDestroyView() {
        super.onDestroyView()
        cooldownHelper?.cancel()
        _binding = null
    }
}
