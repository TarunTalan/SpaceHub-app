package com.example.myapplication.ui.auth.password

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentNewPasswordBinding
import com.example.myapplication.ui.auth.common.PasswordToggleUtil
import com.example.myapplication.ui.common.BaseFragment

class NewPasswordFragment : BaseFragment(R.layout.fragment_new_password) {

    private var _binding: FragmentNewPasswordBinding? = null
    private val binding get() = _binding!!

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

        // Request insets; no extra scroll helpers needed
        ViewCompat.requestApplyInsets(binding.root)

        // Ensure eye behavior: closed = masked, open = visible on both fields
        PasswordToggleUtil.attach(binding.passwordLayout, binding.etPassword)
        PasswordToggleUtil.attach(binding.confirmPasswordLayout, binding.etConfirmPassword)

        setupClickListeners()
        setupKeyboardDismiss(binding.root)
    }

    private fun setupClickListeners() {
        binding.apply {
            // Reset password button - navigate back to login after successful reset
            btnLogin.setOnClickListener {
                if (validatePasswords()) {
                    // TODO: Implement password reset logic
                    // Navigate back to login
                    findNavController().navigate(R.id.action_newPasswordFragment_to_loginFragment)
                }
            }

            // Back to login link with underline
            tvBackToLoginLink.apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    findNavController().navigate(R.id.action_newPasswordFragment_to_loginFragment)
                }
            }
        }
    }

    /**
     * Validates password inputs.
     * @return true if passwords are valid and match, false otherwise
     */
    private fun validatePasswords(): Boolean {
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (password.isEmpty()) {
            // Show error - password required
            return false
        }

        if (password.length < 8) {
            // Show error - password too short
            return false
        }

        if (confirmPassword.isEmpty()) {
            // Show error - confirm password required
            return false
        }

        if (password != confirmPassword) {
            // Show error - passwords don't match
            return false
        }

        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}