package com.example.myapplication.ui.auth.reset

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.auth.AuthRepository
import com.example.myapplication.data.auth.AuthResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ResetPasswordViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(app)

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class EmailSent(val tempToken: String?) : UiState()
        data class OtpVerified(val tempToken: String?) : UiState()
        object PasswordReset : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun requestForgotPassword(email: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            when (val result = repo.sendForgotPasswordOtp(email)) {
                is AuthResult.Success -> _uiState.value = UiState.EmailSent(result.tempToken)
                is AuthResult.Error -> _uiState.value = UiState.Error(result.message)
            }
        }
    }

    fun verifyForgotOtp(email: String, otp: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            when (val result = repo.verifyForgotPasswordOtp(email, otp)) {
                is AuthResult.Success -> {
                    // If repo returned a temp token in success, forward it to the UI state
                    _uiState.value = UiState.OtpVerified(result.tempToken)
                }
                is AuthResult.Error -> _uiState.value = UiState.Error(result.message)
            }
        }
    }

    fun resetPassword(email: String, newPassword: String, tempToken: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            // Call repository resetPassword; cast to concrete type to avoid analysis ambiguity
            when (val result = repo.resetPassword(email, newPassword, tempToken)) {
                is AuthResult.Error -> _uiState.value = UiState.Error(result.message)
                is AuthResult.Success -> {
                    // After successful reset, attempt to login with the new password
                    when (val loginResult = repo.login(email, newPassword)) {
                        is AuthResult.Success -> _uiState.value = UiState.PasswordReset
                        is AuthResult.Error -> _uiState.value = UiState.Error(loginResult.message)
                    }
                }
            }
        }
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }
}
