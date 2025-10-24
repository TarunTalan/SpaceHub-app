package com.example.myapplication.ui.auth.signup

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import com.example.myapplication.ui.common.BaseFragment
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentNameSignupBinding

/**
 * Name signup screen - FIRST STEP where users enter their name.
 */
class NameSignupFragment : BaseFragment(R.layout.fragment_name_signup) {

    private var _binding: FragmentNameSignupBinding? = null
    private val binding get() = _binding!!

    private val redColor by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }

    private lateinit var firstNameTextDefault: ColorStateList
    private lateinit var lastNameTextDefault: ColorStateList

    // Single authoritative max length for name fields
    private val NAME_MAX = 25

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNameSignupBinding.bind(view)

        // Request window insets; no extra scroll handling needed
        ViewCompat.requestApplyInsets(binding.root)

        initializeDefaults()
        setupTextWatchers()
        setupClickListeners()
        setupKeyboardDismiss(binding.root)
    }

    private fun initializeDefaults() {
        firstNameTextDefault = binding.etFirstName.textColors
        lastNameTextDefault = binding.etLastName.textColors

        // Enforce max length at runtime as well (NAME_MAX)
        val lengthFilter = InputFilter.LengthFilter(NAME_MAX)

        // Allow only Unicode letters (no spaces, no digits, no punctuation)
        val lettersOnlyFilter = InputFilter { source, start, end, _, _, _ ->
            val out = StringBuilder()
            for (i in start until end) {
                val c = source[i]
                if (Character.isLetter(c)) {
                    out.append(c)
                }
            }
            val filtered = out.toString()
            // return null to accept original, or the filtered string to replace
            if (filtered.length == end - start) null else filtered
        }

        binding.etFirstName.filters = arrayOf(lengthFilter, lettersOnlyFilter)
        binding.etLastName.filters = arrayOf(lengthFilter, lettersOnlyFilter)

        // Initialize character counters
        updateFirstNameCounter(binding.etFirstName.text?.length ?: 0)
        updateLastNameCounter(binding.etLastName.text?.length ?: 0)
    }

    private fun setupTextWatchers() {
        // First name field text watcher - clear errors when user starts typing
        binding.etFirstName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                updateFirstNameCounter(len)
                if (binding.tvFirstNameError.isVisible) {
                    hideFirstNameError()
                }
            }
        })

        // Last name field text watcher - clear errors when user starts typing
        binding.etLastName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                updateLastNameCounter(len)
                if (binding.tvLastError.isVisible) {
                    hideLastNameError()
                }
            }
        })
    }

    // Helper to update the first name counter text and accessibility
    private fun updateFirstNameCounter(currentLength: Int) {
        binding.tvFirstNameCounter.text = getString(R.string.char_count_slash, currentLength, NAME_MAX)
        // Use a dedicated char-count string for accessibility: "%1$d of %2$d characters"
        binding.tvFirstNameCounter.contentDescription = getString(R.string.first_name) + ", " + getString(R.string.char_count_of_max, currentLength, NAME_MAX)
    }

    // Helper to update the last name counter text and accessibility
    private fun updateLastNameCounter(currentLength: Int) {
        binding.tvLastNameCounter.text = getString(R.string.char_count_slash, currentLength, NAME_MAX)
        binding.tvLastNameCounter.contentDescription = getString(R.string.last_name) + ", " + getString(R.string.char_count_of_max, currentLength, NAME_MAX)
    }

    private fun setupClickListeners() {
        // Continue to email/password signup screen
        binding.btnProceed.setOnClickListener {
            if (validateInput()) {
                val firstName = binding.etFirstName.text.toString().trim()
                val lastName = binding.etLastName.text.toString().trim()
                findNavController().navigate(
                    R.id.action_nameSignupFragment_to_signupFragment,
                    bundleOf(
                        "firstName" to firstName,
                        "lastName" to lastName
                    )
                )
            }
        }

        // Go back to onboarding or login with underline
        binding.tvLoginLink.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                findNavController().navigate(R.id.action_nameSignupFragment_to_loginFragment)
            }
        }
    }

    /**
     * Validates name inputs.
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateInput(): Boolean {
        val rawFirst = binding.etFirstName.text.toString()
        val firstName = rawFirst.trim()
        val rawLast = binding.etLastName.text.toString()
        val lastName = rawLast.trim()

        var isValid = true

        // Validate first name
        if (firstName.isEmpty()) {
            showFirstNameError(getString(R.string.first_name_required))
            isValid = false
        } else if (rawFirst.contains("\\s".toRegex())) {
            // Disallow any whitespace characters in the name (internal spaces, tabs, leading/trailing)
            showFirstNameError(getString(R.string.first_name_no_spaces))
            isValid = false
        } else if (!rawFirst.matches("\\p{L}+".toRegex())) {
            // Ensure name contains only letters (Unicode-aware)
            showFirstNameError(getString(R.string.first_name_no_digits))
            isValid = false
        } else if (firstName.length < 2) {
            showFirstNameError(getString(R.string.first_name_min_length))
            isValid = false
        } else if (firstName.length > NAME_MAX) {
            showFirstNameError(getString(R.string.first_name_max_length))
            isValid = false
        } else {
            hideFirstNameError()
        }

        // Validate last name
        if (lastName.isEmpty()) {
            showLastNameError(getString(R.string.last_name_required))
            isValid = false
        } else if (rawLast.contains("\\s".toRegex())) {
            // Disallow any whitespace characters in the name (including leading/trailing)
            showLastNameError(getString(R.string.last_name_no_spaces))
            isValid = false
        } else if (!rawLast.matches("\\p{L}+".toRegex())) {
            // Ensure name contains only letters (Unicode-aware)
            showLastNameError(getString(R.string.last_name_no_digits))
            isValid = false
        } else if (lastName.length < 2) {
            showLastNameError(getString(R.string.last_name_min_length))
            isValid = false
        } else if (lastName.length > NAME_MAX) {
            showLastNameError(getString(R.string.last_name_max_length))
            isValid = false
        } else {
            hideLastNameError()
        }

        return isValid
    }

    private fun showFirstNameError(message: String) {
        binding.tvFirstNameError.text = message
        binding.tvFirstNameError.isVisible = true
        binding.etFirstName.setTextColor(redColor)
        binding.etFirstName.setBackgroundResource(R.drawable.edit_text_outline_error)
    }

    private fun hideFirstNameError() {
        binding.tvFirstNameError.text = " "
        binding.etFirstName.setTextColor(firstNameTextDefault)
        binding.etFirstName.setBackgroundResource(R.drawable.edit_text_outline_selector)
    }

    private fun showLastNameError(message: String) {
        binding.tvLastError.text = message
        binding.tvLastError.isVisible = true
        binding.etLastName.setTextColor(redColor)
        binding.etLastName.setBackgroundResource(R.drawable.edit_text_outline_error)
    }

    private fun hideLastNameError() {
        binding.tvLastError.text = " "
        binding.etLastName.setTextColor(lastNameTextDefault)
        binding.etLastName.setBackgroundResource(R.drawable.edit_text_outline_selector)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
