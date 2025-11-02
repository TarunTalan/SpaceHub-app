package com.example.myapplication.ui.common

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class ProfileSharedViewModel(app: Application) : AndroidViewModel(app) {

    private val _selectedImagePath = MutableLiveData<String?>()
    val selectedImagePath: LiveData<String?> = _selectedImagePath

    private val _selectedDrawableRes = MutableLiveData<Int?>()
    val selectedDrawableRes: LiveData<Int?> = _selectedDrawableRes

    // Store the original content Uri (when image was picked from gallery)
    private val _selectedContentUri = MutableLiveData<Uri?>()
    val selectedContentUri: LiveData<Uri?> = _selectedContentUri

    // Store the URL returned by the server after uploading the profile
    private val _uploadedProfileUrl = MutableLiveData<String?>()
    val uploadedProfileUrl: LiveData<String?> = _uploadedProfileUrl

    // Store community name and description for create community flow
    private val _communityName = MutableLiveData<String?>()
    val communityName: LiveData<String?> = _communityName
    private val _commDescription = MutableLiveData<String?>()
    val communityDescription: LiveData<String?> = _commDescription

    fun setImagePath(path: String?) {
        _selectedImagePath.value = path
        // clear drawable selection when an image file path is chosen
        if (path != null) _selectedDrawableRes.value = null
        // when we have a freshly written cached file from camera, clear original content Uri
        if (path != null) _selectedContentUri.value = null
    }

    fun setDrawableRes(resId: Int?) {
        _selectedDrawableRes.value = resId
        // clear any cached image path when a drawable resource is chosen
        if (resId != null) _selectedImagePath.value = null
        // clear content Uri as this is a resource-based selection
        if (resId != null) _selectedContentUri.value = null
    }

    fun setSelectedContentUri(uri: Uri?) {
        _selectedContentUri.value = uri
    }

    fun setUploadedProfileUrl(url: String?) {
        // If we're on the main thread, set value immediately so callers can observe synchronously.
        // Otherwise, postValue schedules the update on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _uploadedProfileUrl.value = url
        } else {
            _uploadedProfileUrl.postValue(url)
        }
    }

    fun setCommunityName(name: String?) {
        _communityName.value = name
    }

    fun setCommunityDescription(description: String?) {
        _commDescription.value = description
    }

    fun clear() {
        _selectedImagePath.value = null
        _selectedDrawableRes.value = null
        _uploadedProfileUrl.value = null
        _selectedContentUri.value = null
        _communityName.value = null
        _commDescription.value = null
    }
}
