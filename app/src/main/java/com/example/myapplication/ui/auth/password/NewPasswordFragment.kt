package com.example.myapplication.ui.auth.password

import android.annotation.SuppressLint
import android.os.Bundle
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputFilter
import kotlinx.coroutines.launch
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentNewPasswordBinding
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.auth.reset.ResetPasswordViewModel
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.auth.common.InputValidationHelper
import androidx.navigation.fragment.findNavController

class NewPasswordFragment : BaseFragment(R.layout.fragment_new_password) {

    private var _binding: FragmentNewPasswordBinding? = null
    private val binding get() = _binding!!
    private var passwordFieldWatcher: TextWatcher? = null
    // store original icon tints so we can restore them later (separate for password and confirm fields)
    private var startIconDefaultPassword: ColorStateList? = null
    private var endIconDefaultPassword: ColorStateList? = null
    private var startIconDefaultConfirm: ColorStateList? = null
    private var endIconDefaultConfirm: ColorStateList? = null
    private val viewModel: ResetPasswordViewModel by viewModels()
    private var emailArg: String? = null
    private var tempTokenArg: String? = null

    // Colors and defaults used for validation visuals
    private val redColor by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }
    private val blueColor by lazy { ContextCompat.getColor(requireContext(), R.color.primary_blue) }
    private val grayColor by lazy { ContextCompat.getColor(requireContext(), R.color.gray_medium) }
    private val subtitleGray by lazy { ContextCompat.getColor(requireContext(), R.color.subtitle_gray) }
    // don't access TextInputLayout start icon tint at class init (may not be available in this Material version)
    private lateinit var passwordTextDefault: ColorStateList

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
        // capture runtime defaults
        try { passwordTextDefault = binding.etPassword.textColors } catch (_: Exception) { passwordTextDefault = ColorStateList.valueOf(blueColor) }
        // capture original start/end icon tints (safe across Material versions via reflection)
        startIconDefaultPassword = try {
            val m = binding.passwordLayout.javaClass.getMethod("getStartIconTintList")
            m.invoke(binding.passwordLayout) as? ColorStateList
        } catch (_: Exception) { null }
        endIconDefaultPassword = try {
            val m = binding.passwordLayout.javaClass.getMethod("getEndIconTintList")
            m.invoke(binding.passwordLayout) as? ColorStateList
        } catch (_: Exception) { null }
        startIconDefaultConfirm = try {
            val m = binding.confirmPasswordLayout.javaClass.getMethod("getStartIconTintList")
            m.invoke(binding.confirmPasswordLayout) as? ColorStateList
        } catch (_: Exception) { null }
        endIconDefaultConfirm = try {
            val m = binding.confirmPasswordLayout.javaClass.getMethod("getEndIconTintList")
            m.invoke(binding.confirmPasswordLayout) as? ColorStateList
        } catch (_: Exception) { null }
        // initialize any defaults we will use for clearing invalid visuals
        emailArg = arguments?.getString("email")
        tempTokenArg = arguments?.getString("tempToken")

        // Prevent users from typing whitespace into password fields and enforce length limits
        val noSpaceFilter = InputFilter { source, start, end, _, _, _ ->
            val out = StringBuilder()
            var removed = false
            for (i in start until end) {
                val c = source[i]
                if (!Character.isWhitespace(c)) out.append(c) else removed = true
            }
            if (!removed) null else out.toString()
        }
        val passwordMax = 25
        binding.etPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceFilter)
        binding.etConfirmPassword.filters = arrayOf(InputFilter.LengthFilter(passwordMax), noSpaceFilter)

        setupPasswordFieldTextWatchers()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
        observeViewModel()
    }

    // Clear invalid visuals as soon as user starts typing in password fields
    private fun setupPasswordFieldTextWatchers() {
        val clearBoth = {
            binding.tvPasswordError.visibility = View.INVISIBLE
            // Clear outline and text color; set icons to subtitle_gray to match design
            val subtitleTint = ColorStateList.valueOf(subtitleGray)
            InputValidationHelper.clearInvalid(
                binding.passwordLayout,
                binding.etPassword,
                null,
                subtitleTint,
                subtitleTint,
                passwordTextDefault,
                blueColor,
                grayColor
            )
            InputValidationHelper.clearInvalid(
                binding.confirmPasswordLayout,
                binding.etConfirmPassword,
                null,
                subtitleTint,
                subtitleTint,
                passwordTextDefault,
                blueColor,
                grayColor
            )
            // Also explicitly set start/end icon tints on the layouts to ensure they update immediately
            try { binding.passwordLayout.setStartIconTintList(subtitleTint) } catch (_: Exception) {}
            try { binding.passwordLayout.setEndIconTintList(subtitleTint) } catch (_: Exception) {}
            try { binding.confirmPasswordLayout.setStartIconTintList(subtitleTint) } catch (_: Exception) {}
            try { binding.confirmPasswordLayout.setEndIconTintList(subtitleTint) } catch (_: Exception) {}
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { clearBoth() }
        }

        binding.etPassword.addTextChangedListener(watcher)
        binding.etConfirmPassword.addTextChangedListener(watcher)
        passwordFieldWatcher = watcher
    }

    private fun observeViewModel() {
        // Use fragment lifecycleScope and repeatOnLifecycle for lifecycle-safe collection
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Use BaseFragment loader overlay instead of layout progressBar
                    setLoaderVisible(state is ResetPasswordViewModel.UiState.Loading)

                    when (state) {
                        is ResetPasswordViewModel.UiState.PasswordReset -> {
                            // After password reset and automatic login, do not navigate to the login screen.
                            // Show a concise user message only.
                            setLoaderVisible(false)
                            android.widget.Toast.makeText(requireContext(), "Password changed. Logging in", android.widget.Toast.LENGTH_SHORT).show()
                            findNavController().navigate(R.id.action_newPasswordFragment_to_logoutFragment)
                            // Keep the ViewModel state reset so UI can return to idle.
                            viewModel.reset()
                        }

                        is ResetPasswordViewModel.UiState.Error -> {
                            // hide loader and display inline error
                            setLoaderVisible(false)
                            val msg = state.message.trim()
                            binding.tvPasswordError.text = msg
                            binding.tvPasswordError.isVisible = true

                            val lower2 = msg.lowercase()
                            if ("otp not validated" in lower2 || "token expired" in lower2 || "unauthorized" in lower2) {
                                try { findNavController().navigate(R.id.resetPasswordFragment) } catch (_: Exception) { }
                            }
                        }

                        else -> {
                            // Ensure loader hidden for other states
                            setLoaderVisible(false)
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
                    // Show inline error instead of toast
                    binding.tvPasswordError.text = getString(R.string.missing_token)
                    binding.tvPasswordError.isVisible = true
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

    @SuppressLint("SetTextI18n")
    private fun validatePasswords(password: String, confirmPassword: String): Boolean {
        // Empty check
        if (password.isEmpty()) {
            binding.tvPasswordError.text = getString(R.string.password_required)
            binding.tvPasswordError.visibility = View.VISIBLE
            InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
            InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
            return false
        }

        // Disallow spaces (defensive; filter also prevents typing spaces)
        if (password.contains("\\s".toRegex())) {
            binding.tvPasswordError.text = getString(R.string.password_no_spaces)
            binding.tvPasswordError.visibility = View.VISIBLE
            InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
            InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
            return false
        }

        // Length checks: 8..25
        if (password.length < 8) {
            binding.tvPasswordError.text = "Password must be at least 8 characters"
            binding.tvPasswordError.visibility = View.VISIBLE
            InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
            InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
            return false
        }
        if (password.length > 25) {
            binding.tvPasswordError.text = getString(R.string.password_max_length)
            binding.tvPasswordError.visibility = View.VISIBLE
            InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
            InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
            return false
        }

        // Character class checks
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }

        when {
            !hasUpper -> {
                binding.tvPasswordError.text = "Password must contain at least one uppercase letter"
                binding.tvPasswordError.visibility = View.VISIBLE
                InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
                InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
                return false
            }
            !hasLower -> {
                binding.tvPasswordError.text = getString(R.string.password_require_lowercase)
                binding.tvPasswordError.visibility = View.VISIBLE
                InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
                InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
                return false
            }
            !hasDigit -> {
                binding.tvPasswordError.text = "Password must contain at least one digit"
                binding.tvPasswordError.visibility = View.VISIBLE
                InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
                InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
                return false
            }
            !hasSpecial -> {
                binding.tvPasswordError.text = "Password must contain at least one special character (e.g. !@#\$%&*)"
                binding.tvPasswordError.visibility = View.VISIBLE
                InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
                InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
                return false
            }
            password != confirmPassword -> {
                binding.tvPasswordError.text = getString(R.string.passwords_do_not_match)
                binding.tvPasswordError.visibility = View.VISIBLE
                InputValidationHelper.applyPasswordInvalid(binding.passwordLayout, binding.etPassword, redColor, ColorStateList.valueOf(redColor))
                InputValidationHelper.applyPasswordInvalid(binding.confirmPasswordLayout, binding.etConfirmPassword, redColor, ColorStateList.valueOf(redColor))
                return false
            }
            else -> {
                // clear error visuals
                binding.tvPasswordError.visibility = View.INVISIBLE
                InputValidationHelper.clearInvalid(
                    binding.passwordLayout,
                    binding.etPassword,
                    null,
                    startIconDefaultPassword,
                    endIconDefaultPassword,
                    passwordTextDefault,
                    blueColor,
                    grayColor
                )
                InputValidationHelper.clearInvalid(
                    binding.confirmPasswordLayout,
                    binding.etConfirmPassword,
                    null,
                    startIconDefaultConfirm,
                    endIconDefaultConfirm,
                    passwordTextDefault,
                    blueColor,
                    grayColor
                )
                return true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // remove text watchers to avoid leaks
        try {
            passwordFieldWatcher?.let { pw ->
                _binding?.etPassword?.removeTextChangedListener(pw)
                _binding?.etConfirmPassword?.removeTextChangedListener(pw)
            }
        } catch (_: Exception) {}
        passwordFieldWatcher = null
        _binding = null
    }
}