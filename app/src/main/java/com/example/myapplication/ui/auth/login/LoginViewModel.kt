package com.example.myapplication.ui.auth.login

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.auth.AuthRepository
import com.example.myapplication.data.auth.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(app)

    sealed class UiState {
        data object Idle : UiState()
        data object Loading : UiState()
        data object Success : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            when (val result = repo.login(email, password)) {
                is AuthResult.Success -> {
                    _uiState.value = UiState.Success
                    // Fire-and-forget prefetch of user data (communities + profile) so background sync runs
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val persisted = try { repo.getPersistedEmail() } catch (_: Exception) { null }
                            val toUse = persisted?.takeIf { it.isNotBlank() } ?: email
                            repo.enqueuePrefetch(toUse)
                        } catch (e: Exception) {
                            Log.w("LoginViewModel", "enqueuePrefetch exception: ${e.message}")
                        }
                    }
                }
                is AuthResult.Error -> _uiState.value = UiState.Error(result.message)
            }
        }
    }

    fun reset() { _uiState.value = UiState.Idle }
}
