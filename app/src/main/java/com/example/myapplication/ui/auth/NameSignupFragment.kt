package com.example.myapplication.ui.auth

import android.os.Bundle
import android.view.View
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNameSignupBinding.bind(view)

        setupClickListeners()
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

    /**
     * Validates name inputs.
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateInput(): Boolean {
        val firstName = binding.etFirstName.text.toString().trim()
        val lastName = binding.etLastName.text.toString().trim()

        // Validate first name
        if (firstName.isEmpty()) {
            binding.etFirstName.error = "First name is required"
            return false
        }
        if (firstName.length < 2) {
            binding.etFirstName.error = "First name must be at least 2 characters"
            return false
        }
        binding.etFirstName.error = null

        // Validate last name
        if (lastName.isEmpty()) {
            binding.etLastName.error = "Last name is required"
            return false
        }
        if (lastName.length < 2) {
            binding.etLastName.error = "Last name must be at least 2 characters"
            return false
        }
        binding.etLastName.error = null

        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
