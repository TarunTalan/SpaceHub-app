package com.example.myapplication.ui.common

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Shared ViewModel used to hold profile picture selection across fragments.
 * Stores a cached image file path (in app cache dir) or a drawable resource id.
 * Avoids keeping large Bitmaps in memory.
 */
class ProfileSharedViewModel(app: Application) : AndroidViewModel(app) {

    private val _selectedImagePath = MutableLiveData<String?>()
    val selectedImagePath: LiveData<String?> = _selectedImagePath

    private val _selectedDrawableRes = MutableLiveData<Int?>()
    val selectedDrawableRes: LiveData<Int?> = _selectedDrawableRes

    fun setImagePath(path: String?) {
        _selectedImagePath.value = path
        // clear drawable selection when an image file path is chosen
        if (path != null) _selectedDrawableRes.value = null
    }

    fun setDrawableRes(resId: Int?) {
        _selectedDrawableRes.value = resId
        // clear any cached image path when a drawable resource is chosen
        if (resId != null) _selectedImagePath.value = null
    }

    fun clear() {
        _selectedImagePath.value = null
        _selectedDrawableRes.value = null
    }
}
