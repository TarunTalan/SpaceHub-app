package com.example.myapplication.ui.auth.password

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
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
import kotlinx.coroutines.launch

class ResetPasswordFragment : BaseFragment(R.layout.fragment_reset_password) {

    private var _binding: FragmentResetPasswordBinding? = null
    private val binding get() = _binding!!

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
        // Set red stroke for email field
        binding.emailLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(redColor, redColor)
            )
        )
        binding.emailLayout.setStartIconTintList(redStroke)
        binding.etEmail.setTextColor(redColor)
    }

    private fun clearEmailInvalidVisuals() {
        // Reset to normal colors
        binding.emailLayout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(blueColor, grayColor)
            )
        )
        binding.emailLayout.setStartIconTintList(emailIconDefault)
        binding.etEmail.setTextColor(emailTextDefault)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}