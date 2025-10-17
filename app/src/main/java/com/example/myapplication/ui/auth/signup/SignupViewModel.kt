package com.example.myapplication.ui.auth.signup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.auth.AuthRepository
import com.example.myapplication.data.auth.AuthResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SignupViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(app)

    sealed class UiState {
        data object Idle : UiState()
        data object Loading : UiState()
        data class Success(val requiresVerification: Boolean) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Helper that sets Loading, executes the repo call, and maps AuthResult to UiState.
    private suspend fun performAuthCall(
        call: suspend () -> AuthResult,
        successMapper: (AuthResult.Success) -> UiState = { UiState.Success(it.requiresVerification) }
    ) {
        _uiState.value = UiState.Loading
        when (val result = call()) {
            is AuthResult.Success -> _uiState.value = successMapper(result)
            is AuthResult.Error -> _uiState.value = UiState.Error(result.message)
        }
    }

    fun signUp(firstName: String, lastName: String, email: String, password: String) {
        viewModelScope.launch {
            performAuthCall({ repo.signUp(firstName, lastName, email, password) })
        }
    }

    fun verifyOtp(email: String, otp: String) {
        viewModelScope.launch {
            performAuthCall({ repo.verifySignup(email, otp) })
        }
    }

    // Verify OTP and then perform login using provided password. This will store tokens if login succeeds.
    fun verifyOtpAndLogin(email: String, otp: String, password: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            // First verify the OTP
            when (val verifyResult = repo.verifySignup(email, otp)) {
                is AuthResult.Error -> {
                    _uiState.value = UiState.Error(verifyResult.message)
                    return@launch
                }
                is AuthResult.Success -> {
                    // continue to login
                }
            }

            // Then login to obtain tokens; reuse performAuthCall for mapping but ensure final success indicates no verification needed.
            performAuthCall({ repo.login(email, password) }) { _ -> UiState.Success(requiresVerification = false) }
        }
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }
}
