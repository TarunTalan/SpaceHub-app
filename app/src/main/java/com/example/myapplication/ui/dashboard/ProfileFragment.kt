package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.example.myapplication.ui.common.ProfileImageHelper
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.example.myapplication.data.dashboard.DashboardRepository
import com.example.myapplication.data.dashboard.model.UpdateProfileRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ProfileFragment : BaseFragment(R.layout.fragment_profile) {
    private val dobFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private var hasTemporarySelection = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etFirst = view.findViewById<EditText>(R.id.etFirstName)
        val etLast = view.findViewById<EditText>(R.id.etLastName)
        val etUser = view.findViewById<EditText>(R.id.etUsername)
        val etDob = view.findViewById<EditText>(R.id.etDOB)
        val etBio = view.findViewById<EditText>(R.id.etBio)
        val imgView = view.findViewById<ImageView>(R.id.profile)

        // Use UserDataManager for centralized, reactive data access
        val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext())

        // Observe and populate fields from UserDataManager
        userDataManager.firstName.observe(viewLifecycleOwner) { first ->
            if (etFirst?.text.isNullOrBlank()) {
                first?.takeIf { it.isNotBlank() }?.let { etFirst.setText(it) }
            }
        }

        userDataManager.lastName.observe(viewLifecycleOwner) { last ->
            if (etLast?.text.isNullOrBlank()) {
                last?.takeIf { it.isNotBlank() }?.let { etLast.setText(it) }
            }
        }

        userDataManager.username.observe(viewLifecycleOwner) { username ->
            if (etUser?.text.isNullOrBlank()) {
                username?.takeIf { it.isNotBlank() }?.let { etUser.setText(it) }
            }
        }

        userDataManager.dateOfBirth.observe(viewLifecycleOwner) { dob ->
            if (etDob?.text.isNullOrBlank()) {
                dob?.takeIf { it.isNotBlank() }?.let { etDob.setText(it) }
            }
        }

        userDataManager.bio.observe(viewLifecycleOwner) { bio ->
            if (etBio?.text.isNullOrBlank()) {
                bio?.takeIf { it.isNotBlank() }?.let { etBio.setText(it) }
            }
        }

        // Profile image loading strategy:
        // Priority 1: Temporary selections from SharedViewModel (before upload)
        // Priority 2: Persisted data from UserDataManager/DataStore (after upload)


        // Observe SharedViewModel for temporary selections (gallery/camera/drawable picks)
        sharedVm.selectedImagePath.observe(viewLifecycleOwner) { path ->
            if (!path.isNullOrBlank()) {
                try {
                    val f = File(path)
                    if (f.exists()) {
                        hasTemporarySelection = true
                        Glide.with(this)
                            .load(f)
                            .signature(ObjectKey(f.absolutePath + "-" + f.lastModified()))
                            .placeholder(R.drawable.default_profile)
                            .error(R.drawable.default_profile)
                            .circleCrop()
                            .into(imgView)
                    } else {
                        hasTemporarySelection = false
                    }
                } catch (_: Exception) {
                    hasTemporarySelection = false
                }
            } else {
                hasTemporarySelection = false
            }
        }

        sharedVm.selectedDrawableRes.observe(viewLifecycleOwner) { resId ->
            if (resId != null && resId != 0) {
                try {
                    hasTemporarySelection = true
                    Glide.with(this)
                        .load(resId)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imgView)
                } catch (_: Exception) {
                    hasTemporarySelection = false
                }
            } else {
                hasTemporarySelection = false
            }
        }

        // Load from UserDataManager (persisted) only if no temporary selection
        // This ensures temporary selections take priority
        userDataManager.profileImagePathFlow.asLiveData().observe(viewLifecycleOwner) {
            // Trigger re-evaluation when persisted data changes
            if (!hasTemporarySelection) {
                loadPersistedProfileImage(imgView)
            }
        }

        userDataManager.profileImageUrlFlow.asLiveData().observe(viewLifecycleOwner) {
            if (!hasTemporarySelection) {
                loadPersistedProfileImage(imgView)
            }
        }

        // Initial load
        loadPersistedProfileImage(imgView)

        etDob?.apply {
            isFocusable = false
            isClickable = true
            isCursorVisible = false
            setOnClickListener { showMaterialDatePicker(this) }
        }

        // Wire navigation
        try {
            val backBtn = view.findViewById<ImageView>(R.id.back)
            backBtn?.setOnClickListener { try { findNavController().popBackStack() } catch (_: Exception) {} }
        } catch (_: Exception) {}

        try {
            view.findViewById<TextView>(R.id.tvChangeProfilePicture)?.setOnClickListener {
                try { findNavController().navigate(R.id.action_profileFragment_to_changeProfilePicFragment) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        try {
            imgView?.setOnClickListener {
                try { findNavController().navigate(R.id.action_profileFragment_to_changeProfilePicFragment) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Save button (top right) - update profile via API
        val tvSave = view.findViewById<TextView>(R.id.tv_save)
        tvSave?.setOnClickListener {
            try {
                val firstVal = etFirst?.text?.toString()?.trim().orEmpty()
                val lastVal = etLast?.text?.toString()?.trim().orEmpty()
                val usernameVal = etUser?.text?.toString()?.trim().orEmpty()
                val bioVal = etBio?.text?.toString()?.trim().orEmpty()

                val req = UpdateProfileRequest(
                    firstName = firstVal,
                    lastName = lastVal,
                    bio = bioVal,
                    location = "",
                    website = "",
                    isPrivate = false,
                    username = usernameVal
                )

                setLoaderVisible(true)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repo = DashboardRepository(requireContext())
                        val success = repo.updateProfile(req)

                        CoroutineScope(Dispatchers.Main).launch {
                            setLoaderVisible(false)
                            if (success) {
                                Toast.makeText(requireContext(), getString(R.string.saved), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (_: Exception) {
                        CoroutineScope(Dispatchers.Main).launch {
                            setLoaderVisible(false)
                            Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showMaterialDatePicker(target: EditText) {
        val selection: Long = runCatching {
            val text = target.text.toString().trim()
            val local = LocalDate.parse(text, dobFormat)
            local.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrElse { MaterialDatePicker.todayInUtcMilliseconds() }

        val constraints = CalendarConstraints.Builder().setEnd(System.currentTimeMillis()).build()

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select date of birth")
            .setCalendarConstraints(constraints)
            .setSelection(selection)
            .build()

        picker.addOnPositiveButtonClickListener { sel ->
            runCatching {
                val instant = Instant.ofEpochMilli(sel as Long)
                val local = instant.atZone(ZoneId.systemDefault()).toLocalDate()
                target.setText(local.format(dobFormat))
            }
        }

        picker.show(parentFragmentManager, "DOB_PICKER")
    }

    /**
     * Load persisted profile image from UserDataManager.
     * This is only called when there are no temporary selections from SharedViewModel.
     */
    private fun loadPersistedProfileImage(imageView: ImageView) {
        lifecycleScope.launch {
            val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext())
            val bestSource = userDataManager.getBestProfileImageSource()

            ProfileImageHelper.loadProfileImageIntoView(
                requireContext(),
                imageView,
                bestSource
            )
        }
    }
}
