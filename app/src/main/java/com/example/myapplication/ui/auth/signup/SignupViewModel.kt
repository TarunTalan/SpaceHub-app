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
        // Distinct state used when an explicit resend request is in-flight
        data object ResendLoading : UiState()
        // Emitted when an OTP email/message is successfully sent (initial or resend)
        data class EmailSent(val tempToken: String?) : UiState()
        data class Success(val requiresVerification: Boolean) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Persist temporary token (returned by signup / resend) so resend can use it and it survives rotations
    private val _tempToken = MutableStateFlow<String?>(null)
    val tempToken = _tempToken.asStateFlow()

    // Persist cooldown end time (millis since epoch). Null when no cooldown active.
    private val _cooldownEndMillis = MutableStateFlow<Long?>(null)
    val cooldownEndMillis = _cooldownEndMillis.asStateFlow()

    fun setCooldownEndMillis(endMillis: Long) {
        _cooldownEndMillis.value = endMillis
    }

    fun clearCooldown() {
        _cooldownEndMillis.value = null
    }

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
            _uiState.value = UiState.Loading
            when (val result = repo.signUp(firstName, lastName, email, password)) {
                is AuthResult.Success -> {
                    // If backend returned a tempToken with signup, persist it and notify UI that email/OTP was sent
                    if (!result.tempToken.isNullOrBlank()) {
                        _tempToken.value = result.tempToken
                        _uiState.value = UiState.EmailSent(result.tempToken)
                    } else {
                        // Server didn't return a temp token in signup response – request it explicitly
                        when (val otpResult = repo.sendSignupOtp(email)) {
                            is AuthResult.Success -> {
                                // store token if backend provided one, otherwise continue
                                if (!otpResult.tempToken.isNullOrBlank()) _tempToken.value = otpResult.tempToken
                                // signal EmailSent so fragment can start cooldown & show timer; pass token if any
                                _uiState.value = UiState.EmailSent(otpResult.tempToken)
                            }
                            is AuthResult.Error -> {
                                // Failed to request OTP – surface the error to the UI
                                _uiState.value = UiState.Error(otpResult.message)
                            }
                        }
                    }
                }
                is AuthResult.Error -> _uiState.value = UiState.Error(result.message)
            }
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

    // Explicit resend using email + session token
    fun resendOtp(email: String, sessionToken: String) {
        viewModelScope.launch {
            _uiState.value = UiState.ResendLoading
            when (val result = repo.resendSignupOtp(email, sessionToken)) {
                is AuthResult.Success -> {
                    if (!result.tempToken.isNullOrBlank()) _tempToken.value = result.tempToken
                    _uiState.value = UiState.EmailSent(result.tempToken)
                }
                is AuthResult.Error -> _uiState.value = UiState.Error(result.message)
            }
        }
    }

    fun reset() {
        _uiState.value = UiState.Idle
        _tempToken.value = null
        _cooldownEndMillis.value = null
    }
}
