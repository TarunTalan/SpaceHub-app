package com.example.myapplication.ui.auth.password

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.myapplication.ui.common.BaseFragment
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentResetPasswordBinding
import androidx.core.view.isVisible
import android.widget.ProgressBar
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.ui.auth.reset.ResetPasswordViewModel
import com.example.myapplication.ui.auth.common.InputValidationHelper
import kotlinx.coroutines.launch
import android.os.CountDownTimer
import androidx.core.content.edit
import android.content.Context

class ResetPasswordFragment : BaseFragment(R.layout.fragment_reset_password) {

    private var _binding: FragmentResetPasswordBinding? = null
    private val binding get() = _binding!!

    // Timer used to display remaining lockout time when "too many attempts" is active
    private var lockoutTimer: CountDownTimer? = null

    private val redColor by lazy { "#ED2828".toColorInt() }
    private val blueColor by lazy { "#2563EB".toColorInt() }
    private val grayColor by lazy { "#ADADAD".toColorInt() }
    private val emailIconDefault by lazy { ColorStateList.valueOf(grayColor) }
    private val redStroke by lazy { ColorStateList.valueOf(redColor) }

    private lateinit var emailTextDefault: ColorStateList

    private val viewModel: ResetPasswordViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResetPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.requestApplyInsets(binding.root)
        initializeDefaults()
        setupTextWatchers()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        // Apply any persisted OTP lockout (set by verification fragments on too-many-attempts)
        checkAndApplyLockout()
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val progressBar = binding.root.findViewById<ProgressBar>(R.id.progressBar)
                    when (state) {
                        is ResetPasswordViewModel.UiState.Loading -> progressBar?.let { it.isVisible = true }
                        is ResetPasswordViewModel.UiState.EmailSent -> {
                            progressBar?.let { it.isVisible = false }
                            // include tempToken in bundle so verification fragment can use it for debugging/prefill
                            val temp = state.tempToken
                            findNavController().navigate(R.id.action_resetPasswordFragment_to_verifyForgotPasswordFragment,
                                Bundle().apply {
                                    putString("email", binding.etEmail.text.toString().trim())
                                    putString("tempToken", temp)
                                })
                            viewModel.reset()
                        }
                        is ResetPasswordViewModel.UiState.Error -> {
                            progressBar?.let { it.isVisible = false }
                            // Use helper to display email-related errors consistently
                            showEmailError(state.message)
                        }
                        else -> progressBar?.let { it.isVisible = false }
                    }
                }
            }
        }
    }

    private fun initializeDefaults() {
        emailTextDefault = binding.etEmail.textColors

        // Disable built-in error handling for custom error display
        binding.emailLayout.apply {
            isErrorEnabled = false
            isHelperTextEnabled = false
            errorIconDrawable = null
        }
        // Ensure initial visuals are the normal (non-error) state
        clearEmailInvalidVisuals()

        // Prevent users from typing whitespace into the email field and enforce length limit
        val noSpaceFilter = InputFilter { source, start, end, _, _, _ ->
            val out = StringBuilder()
            var removed = false
            for (i in start until end) {
                val c = source[i]
                if (!Character.isWhitespace(c)) out.append(c) else removed = true
            }
            if (!removed) null else out.toString()
        }
        val emailMax = 50
        binding.etEmail.filters = arrayOf(InputFilter.LengthFilter(emailMax), noSpaceFilter)
    }

    private fun setupTextWatchers() {
        // Email field text watcher - clear errors when user starts typing
        binding.etEmail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding.tvEmailError.isVisible) {
                    hideEmailError()
                }
            }
        })
    }

    private fun setupClickListeners() {
        // Single click listener: validate email, show errors, and request forgot-password OTP
        binding.btnLogin.setOnClickListener {
            // Defensive: if a lockout is active, block sending and show message
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val until = prefs.getLong("forgot_otp_lockout_until", 0L)
            if (System.currentTimeMillis() < until) {
                // show remaining minutes rounded up
                val remaining = until - System.currentTimeMillis()
                val minutesLeft = Math.ceil(remaining / 60000.0).toInt()
                binding.tvEmailError.text = getString(R.string.too_many_attempts_try_again, minutesLeft)
                binding.tvEmailError.visibility = View.VISIBLE
                // ensure button is disabled visually
                binding.btnLogin.isEnabled = false
                binding.btnLogin.alpha = 0.5f
                // start/update the local countdown UI so user sees time left
                startLockoutTimer(until)
                return@setOnClickListener
            }
            val email = binding.etEmail.text.toString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showEmailError(getString(R.string.invalid_email_format))
                return@setOnClickListener
            }
            hideEmailError()
            // Call ViewModel to request OTP; observer will navigate on success
            viewModel.requestForgotPassword(email)
        }

        // Back to login link with underline
        binding.tvBackToLoginLink.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                findNavController().navigate(R.id.action_resetPasswordFragment_to_loginFragment)
            }
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        lockoutTimer?.cancel()
        _binding = null
    }

    // Read persisted lockout and apply UI if active. Starts a local timer to update the message.
    private fun checkAndApplyLockout() {
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val until = prefs.getLong("forgot_otp_lockout_until", 0L)
            if (System.currentTimeMillis() < until) {
                // disable send button
                binding.btnLogin.isEnabled = false
                binding.btnLogin.alpha = 0.5f

                // show an inline lockout message and start a timer to update it
                startLockoutTimer(until)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun startLockoutTimer(untilMillis: Long) {
        lockoutTimer?.cancel()
        val remaining = untilMillis - System.currentTimeMillis()
        if (remaining <= 0L) {
            binding.btnLogin.isEnabled = true
            binding.btnLogin.alpha = 1.0f
            binding.tvEmailError.visibility = View.INVISIBLE
            // clear persisted lockout
            try { requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit { remove("forgot_otp_lockout_until") } } catch (_: Exception) {}
            return
        }

        // Update message every second. Show minutes rounded up in the translatable message.
        lockoutTimer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val minutesLeft = Math.ceil(millisUntilFinished / 60000.0).toInt()
                binding.tvEmailError.text = getString(R.string.too_many_attempts_try_again, minutesLeft)
                binding.tvEmailError.visibility = View.VISIBLE
            }

            override fun onFinish() {
                binding.btnLogin.isEnabled = true
                binding.btnLogin.alpha = 1.0f
                binding.tvEmailError.visibility = View.INVISIBLE
                // clear persisted lockout
                try { requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit { remove("forgot_otp_lockout_until") } } catch (_: Exception) {}
                lockoutTimer = null
            }
        }

        lockoutTimer?.start()
    }
}