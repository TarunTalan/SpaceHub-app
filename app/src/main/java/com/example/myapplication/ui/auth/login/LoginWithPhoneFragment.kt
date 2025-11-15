package com.example.myapplication.ui.auth.login

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentLoginWithPhoneBinding
import com.example.myapplication.ui.auth.common.InputValidationHelper
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.InputValidator
import kotlinx.coroutines.launch

class LoginWithPhoneFragment : BaseFragment(R.layout.fragment_login_with_phone) {

    private var _binding: FragmentLoginWithPhoneBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    // Colors and icon tints (copied from LoginFragment for consistent visuals)
    private val redColor by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }
    private val blueColor by lazy { ContextCompat.getColor(requireContext(), R.color.primary_blue) }
    private val grayColor by lazy { ContextCompat.getColor(requireContext(), R.color.gray_medium) }
    private val grayLightColor by lazy { ContextCompat.getColor(requireContext(), R.color.gray_light) }
    private val redStroke by lazy { ColorStateList.valueOf(redColor) }

    private lateinit var phoneTextDefault: ColorStateList
    private lateinit var passwordTextDefault: ColorStateList

    private var phoneErrorLatched = false
    private var passwordErrorLatched = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginWithPhoneBinding.inflate(inflater, container, false)
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

    private fun initializeDefaults() {
        // Capture default text colors to restore when clearing invalid visuals
        phoneTextDefault = binding.etPhone.textColors
        passwordTextDefault = binding.etPasswordPhone.textColors

        // Attach password toggle behavior (same as LoginFragment)
        PasswordToggleUtil.attach(binding.passwordLayoutPhone, binding.etPasswordPhone)
        // Ensure end icon is visible and initialized (defensive: sometimes theme or xml attrs hide it)
        try {
            binding.passwordLayoutPhone.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM
            binding.passwordLayoutPhone.setEndIconDrawable(R.drawable.eye_off_icon)
            binding.passwordLayoutPhone.isEndIconVisible = true
            try { binding.passwordLayoutPhone.setEndIconTintList(ColorStateList.valueOf(grayLightColor)) } catch (_: Exception) {}
        } catch (_: Exception) {}

        // Disable built-in error handling on the TextInputLayout (we show our own error text below)
        try {
            binding.passwordLayoutPhone.apply {
                isErrorEnabled = false
                isHelperTextEnabled = false
                errorIconDrawable = null
                setErrorTextColor(redStroke)
                setStartIconTintList(ColorStateList.valueOf(grayLightColor))
                try { setEndIconTintList(ColorStateList.valueOf(grayLightColor)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        binding.tvPasswordErrorPhone.visibility = View.GONE
        // Ensure phone error view starts hidden (matches signup behavior)
        try { binding.tvPhoneError.visibility = View.INVISIBLE } catch (_: Exception) {}

        // Input filters: disallow whitespace and emoji (same as LoginFragment)
        val noSpaceOrEmojiFilter = InputFilter { source, start, end, _, _, _ ->
            val out = StringBuilder()
            var i = start
            val allowedTypes = setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt()
            )
            while (i < end) {
                val cp = Character.codePointAt(source, i)
                val charCount = Character.charCount(cp)
                val isSpace = Character.isWhitespace(cp)
                val isEmoji = (cp in 0x1F600..0x1F64F) || (cp in 0x1F300..0x1F5FF) || (cp in 0x1F680..0x1F6FF) || (cp in 0x1F1E6..0x1F1FF) || (cp in 0x2600..0x26FF) || (cp in 0x2700..0x27BF) || (cp in 0x1F900..0x1F9FF) || (cp in 0x1FA70..0x1FAFF) || (cp in 0xFE00..0xFE0F)
                val type = Character.getType(cp)
                val isLetterOrDigit = Character.isLetterOrDigit(cp)
                val isAllowedSymbol = allowedTypes.contains(type)
                if (!isSpace && !isEmoji && (isLetterOrDigit || isAllowedSymbol)) {
                    out.appendCodePoint(cp)
                }
                i += charCount
            }
            if (out.length == end - start) null else out.toString()
        }

        // Apply length/filter constraints: phone (10), password (max 25) and disallow unsupported chars
        binding.etPhone.filters = arrayOf(InputFilter.LengthFilter(10), noSpaceOrEmojiFilter)
        binding.etPasswordPhone.filters = arrayOf(InputFilter.LengthFilter(25), noSpaceOrEmojiFilter)
    }

    private fun setupTextWatchers() {
        binding.etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (phoneErrorLatched) {
                    phoneErrorLatched = false
                    // Restore visuals
                    try { InputValidationHelper.clearEditTextInvalid(binding.etPhone, phoneTextDefault, R.drawable.edit_text_outline_selector) } catch (_: Exception) {}
                    binding.tvPhoneError.visibility = View.INVISIBLE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etPasswordPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (passwordErrorLatched) {
                    passwordErrorLatched = false
                    try { InputValidationHelper.clearPasswordInvalid(binding.passwordLayoutPhone, binding.etPasswordPhone, ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray_light)), passwordTextDefault, blueColor, grayColor) } catch (_: Exception) {}
                    binding.tvPasswordErrorPhone.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is LoginViewModel.UiState.Idle -> setLoading(false)
                        is LoginViewModel.UiState.Loading -> setLoading(true)
                        is LoginViewModel.UiState.Success -> {
                            setLoading(false)
                            try {
                                val navOptions = NavOptions.Builder()
                                    .setPopUpTo(R.id.auth_nav_graph, true)
                                    .build()
                                findNavController().navigate(R.id.dashboardFragment, null, navOptions)
                            } catch (_: Exception) {
                                try { findNavController().navigate(R.id.dashboardFragment) } catch (_: Exception) {}
                            }
                            viewModel.reset()
                        }
                        is LoginViewModel.UiState.Error -> {
                            setLoading(false)
                            val msg = state.message
                            val isPasswordError = msg.contains("invalid credentials", ignoreCase = true) || msg.contains("password", ignoreCase = true) || msg.contains("credentials", ignoreCase = true)
                            if (isPasswordError) {
                                passwordErrorLatched = true
                                binding.tvPasswordErrorPhone.text = getString(R.string.invalid_password)
                                binding.tvPasswordErrorPhone.visibility = View.VISIBLE
                                try { InputValidationHelper.applyPasswordInvalid(binding.passwordLayoutPhone, binding.etPasswordPhone, redColor, redStroke) } catch (_: Exception) {}
                            } else {
                                phoneErrorLatched = true
                                // If backend returned a verbose message mentioning email/phone, clean it for display
                                val displayMsg = cleanPhoneErrorMessage(msg)
                                binding.tvPhoneError.text = displayMsg
                                binding.tvPhoneError.visibility = View.VISIBLE
                                try { InputValidationHelper.applyEditTextInvalid(binding.etPhone, redColor, R.drawable.edit_text_outline_error) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun validatePhone(): Boolean {
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        if (phone.isEmpty()) {
            phoneErrorLatched = true
            binding.tvPhoneError.text = getString(R.string.phone_required)
            binding.tvPhoneError.visibility = View.VISIBLE
            try { InputValidationHelper.applyEditTextInvalid(binding.etPhone, redColor, R.drawable.edit_text_outline_error) } catch (_: Exception) {}
            return false
        }
        if (phone.length < 10) {
            phoneErrorLatched = true
            binding.tvPhoneError.text = getString(R.string.invalid_phone)
            binding.tvPhoneError.visibility = View.VISIBLE
            try { InputValidationHelper.applyEditTextInvalid(binding.etPhone, redColor, R.drawable.edit_text_outline_error) } catch (_: Exception) {}
            return false
        }
        return true
    }

    private fun validatePassword(): Boolean {
        val pwd = binding.etPasswordPhone.text?.toString()?.trim().orEmpty()
        val result = InputValidator.validatePassword(pwd)
        return when (result) {
            InputValidator.PasswordResult.EMPTY -> {
                passwordErrorLatched = true
                binding.tvPasswordErrorPhone.text = getString(R.string.password_required)
                binding.tvPasswordErrorPhone.visibility = View.VISIBLE
                try { InputValidationHelper.applyPasswordInvalid(binding.passwordLayoutPhone, binding.etPasswordPhone, redColor, redStroke) } catch (_: Exception) {}
                false
            }
            InputValidator.PasswordResult.VALID -> {
                passwordErrorLatched = false
                binding.tvPasswordErrorPhone.visibility = View.GONE
                try { InputValidationHelper.clearPasswordInvalid(binding.passwordLayoutPhone, binding.etPasswordPhone, ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray_light)), passwordTextDefault, blueColor, grayColor) } catch (_: Exception) {}
                true
            }
            InputValidator.PasswordResult.TOO_SHORT -> {
                passwordErrorLatched = true
                binding.tvPasswordErrorPhone.text = getString(R.string.password_min_6)
                binding.tvPasswordErrorPhone.visibility = View.VISIBLE
                try { InputValidationHelper.applyPasswordInvalid(binding.passwordLayoutPhone, binding.etPasswordPhone, redColor, redStroke) } catch (_: Exception) {}
                false
            }
            else -> {
                passwordErrorLatched = true
                binding.tvPasswordErrorPhone.text = getString(R.string.invalid_password)
                binding.tvPasswordErrorPhone.visibility = View.VISIBLE
                try { InputValidationHelper.applyPasswordInvalid(binding.passwordLayoutPhone, binding.etPasswordPhone, redColor, redStroke) } catch (_: Exception) {}
                false
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            // clear any previous errors
            binding.tvPasswordErrorPhone.visibility = View.GONE
            binding.tvPhoneError.visibility = View.INVISIBLE
            val okPhone = validatePhone()
            val okPass = validatePassword()
            if (okPhone && okPass) {
                val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
                val pwd = binding.etPasswordPhone.text?.toString()?.trim().orEmpty()
                hideKeyboard()
                // Prefix phone with country code if user entered plain digits. Backend expects canonical identifier.
                val identifier = if (phone.startsWith("+")) phone else "+91$phone"
                // Reuse existing LoginViewModel which calls AuthRepository.login(identifier, password)
                viewModel.login(identifier, pwd)
            }
        }

        // Sign-up link with underline (same behaviour as LoginFragment)
        binding.tvSignupLinkPhone.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                try { findNavController().navigate(R.id.nameSignupFragment) } catch (_: Exception) {}
            }
        }

        // Clear error when typing
        binding.etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { binding.tvPhoneError.visibility = View.INVISIBLE; try { InputValidationHelper.clearEditTextInvalid(binding.etPhone, phoneTextDefault, R.drawable.edit_text_outline_selector) } catch (_: Exception) {} }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etPasswordPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { binding.tvPasswordErrorPhone.visibility = View.GONE; try { InputValidationHelper.clearPasswordInvalid(binding.passwordLayoutPhone, binding.etPasswordPhone, ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray_light)), passwordTextDefault, blueColor, grayColor) } catch (_: Exception) {} }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setLoading(loading: Boolean) {
        // show fullscreen loader from BaseFragment for consistency
        setLoaderVisible(loading)
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.alpha = if (loading) 0.5f else 1.0f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Heuristic similar to SignupFragment to detect phone-related backend messages
    private fun isPhoneRelatedError(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        val lower = msg.lowercase()
        if (lower.contains("phone") || lower.contains("mobile")) return true
        if (msg.contains("+91") || msg.contains("91")) {
            val phoneRegex = Regex("\\+?91[0-9]{8,12}")
            if (phoneRegex.containsMatchIn(msg)) return true
        }
        val genericNum = Regex("\\+?\\d{10,13}")
        if (genericNum.containsMatchIn(msg) && (lower.contains("not found") || lower.contains("exists") || lower.contains("already") || lower.contains("invalid"))) return true
        return false
    }

    // Clean common backend phone error formats for nicer inline display. E.g. "User not found with email: +917..." -> "User not found"
    private fun cleanPhoneErrorMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // If pattern like 'User not found with email: +91...' strip the suffix
        val patterns = listOf(
            Regex("(?i)user not found with email:.*"),
            Regex("(?i)user not found with phone:.*"),
            Regex("(?i)user not found:.*"),
        )
        patterns.forEach { p ->
            if (p.containsMatchIn(raw)) {
                return raw.substring(0, p.find(raw)!!.range.first).trim().ifEmpty { raw }
            }
        }
        // Otherwise, if message contains a phone-like token, remove the token portion after colon
        val colonIdx = raw.indexOf(":")
        if (colonIdx >= 0 && colonIdx < raw.length - 1) {
            val suffix = raw.substring(colonIdx + 1)
            val phoneRegex = Regex("\\+?\\d{6,}")
            if (phoneRegex.containsMatchIn(suffix)) {
                return raw.substring(0, colonIdx).trim()
            }
        }
        return raw
    }
}
