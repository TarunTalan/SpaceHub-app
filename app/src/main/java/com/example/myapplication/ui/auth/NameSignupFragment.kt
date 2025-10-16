package com.example.myapplication.ui.auth

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentNameSignupBinding

/**
 * Name signup screen - FIRST STEP where users enter their name.
 */
class NameSignupFragment : Fragment(R.layout.fragment_name_signup) {

    private var _binding: FragmentNameSignupBinding? = null
    private val binding get() = _binding!!

    private val redColor by lazy { ContextCompat.getColor(requireContext(), R.color.error_red) }

    private lateinit var firstNameTextDefault: ColorStateList
    private lateinit var lastNameTextDefault: ColorStateList

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNameSignupBinding.bind(view)

        // Request window insets; no extra scroll handling needed
        ViewCompat.requestApplyInsets(binding.root)

        initializeDefaults()
        setupTextWatchers()
        setupClickListeners()
        setupKeyboardDismiss()
    }

    private fun initializeDefaults() {
        firstNameTextDefault = binding.etFirstName.textColors
        lastNameTextDefault = binding.etLastName.textColors
    }

    private fun setupTextWatchers() {
        // First name field text watcher - clear errors when user starts typing
        binding.etFirstName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
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
                if (binding.tvLastError.isVisible) {
                    hideLastNameError()
                }
            }
        })
    }

    private fun setupClickListeners() {
        // Continue to email/password signup screen
        binding.btnProceed.setOnClickListener {
            if (validateInput()) {
                // Navigate to SignupFragment (email/password) - SECOND STEP
                findNavController().navigate(R.id.action_nameSignupFragment_to_signupFragment)
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

    private fun setupKeyboardDismiss() {
        // Hide keyboard when tapping background
        binding.root.setOnClickListener {
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    /**
     * Validates name inputs.
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateInput(): Boolean {
        val firstName = binding.etFirstName.text.toString().trim()
        val lastName = binding.etLastName.text.toString().trim()

        var isValid = true

        // Validate first name
        if (firstName.isEmpty()) {
            showFirstNameError(getString(R.string.first_name_required))
            isValid = false
        } else if (firstName.length < 2) {
            showFirstNameError(getString(R.string.first_name_min_length))
            isValid = false
        } else {
            hideFirstNameError()
        }

        // Validate last name
        if (lastName.isEmpty()) {
            showLastNameError(getString(R.string.last_name_required))
            isValid = false
        } else if (lastName.length < 2) {
            showLastNameError(getString(R.string.last_name_min_length))
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
        binding.tvFirstNameError.isVisible = false
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
        binding.tvLastError.isVisible = false
        binding.etLastName.setTextColor(lastNameTextDefault)
        binding.etLastName.setBackgroundResource(R.drawable.edit_text_outline_selector)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
