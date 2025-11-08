package com.example.myapplication.ui.group.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class EditGroupViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LocalGroupRepository.getInstance(app.applicationContext)

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    fun update(localGroupId: String, requesterEmail: String, name: String, imagePart: okhttp3.MultipartBody.Part?) {
        if (name.isBlank()) { _toast.value = "Name required"; return }
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                val requesterBody: RequestBody = requesterEmail.toRequestBody("text/plain".toMediaTypeOrNull())
                val nameBody: RequestBody = name.trim().toRequestBody("text/plain".toMediaTypeOrNull())
                val res = withContext(Dispatchers.IO) { repo.updateLocalGroupSettings(localGroupId, requesterBody, nameBody, imagePart) }
                _loading.postValue(false)
                res.onSuccess { _toast.postValue("Group updated") }
                    .onFailure { _toast.postValue(it.message ?: "Failed to update group") }
            } catch (t: Throwable) {
                _loading.postValue(false)
                _toast.postValue(t.message ?: "Failed to update group")
            }
        }
    }
}
