package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.InputValidator
import com.example.myapplication.ui.common.ProfileImageHelper
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import android.text.InputFilter
import androidx.core.widget.addTextChangedListener

class ProfileFragment : BaseFragment(R.layout.fragment_profile) {
    private val dobFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etFirst = view.findViewById<EditText>(R.id.etFirstName)
        val etLast = view.findViewById<EditText>(R.id.etLastName)
        val etUser = view.findViewById<EditText>(R.id.etUsername)
        val etDob = view.findViewById<EditText>(R.id.etDOB)
        val etBio = view.findViewById<EditText>(R.id.etBio)
        val imgView = view.findViewById<ImageView>(R.id.profile)

        // Input filters
        val noSpacesFilter = InputFilter { source, _, _, _, _, _ -> if (source != null && source.any { it.isWhitespace() }) "" else null }
        val lettersOnlyFilter = InputFilter { source, _, _, _, _, _ -> if (source == null) null else { val filtered = source.filter { it.isLetter() }; if (filtered.length == source.length) null else filtered } }
        etFirst.filters = arrayOf(InputFilter.LengthFilter(25), noSpacesFilter, lettersOnlyFilter)
        etLast.filters = arrayOf(InputFilter.LengthFilter(25), noSpacesFilter, lettersOnlyFilter)
        etUser.filters = arrayOf(InputFilter.LengthFilter(25), noSpacesFilter)
        etBio.filters = arrayOf(InputFilter.LengthFilter(150))

        val tvFirstNameError = view.findViewById<TextView>(R.id.tvFirstNameError)
        val tvLastNameError = view.findViewById<TextView>(R.id.tvLastError)
        val tvUsernameError = view.findViewById<TextView>(R.id.tvUsernameError)
        val tvBioError = view.findViewById<TextView>(R.id.tvBioerror)

        fun clearErrors() {
            tvFirstNameError?.visibility = View.GONE
            tvLastNameError?.visibility = View.GONE
            tvUsernameError?.visibility = View.GONE
            tvBioError?.visibility = View.GONE
            etDob.error = null
        }

        fun showError(tv: TextView?, msg: String) { tv?.apply { text = msg; visibility = View.VISIBLE } }

        // Inline error clearing
        etFirst.addTextChangedListener { text -> if (!text.isNullOrBlank() && text.length <= 25) tvFirstNameError?.visibility = View.GONE }
        etLast.addTextChangedListener { text -> if (!text.isNullOrBlank() && text.length <= 25) tvLastNameError?.visibility = View.GONE }
        etUser.addTextChangedListener { text -> val value = text?.toString().orEmpty(); val res = InputValidator.validateUsername(value); if (res == InputValidator.UsernameResult.VALID || value.isBlank()) tvUsernameError?.visibility = View.GONE }
        etBio.addTextChangedListener { text -> val len = text?.length ?: 0; if (len <= 150) tvBioError?.visibility = View.GONE }
        etDob.addTextChangedListener { _ -> etDob.error = null }

        val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext())

        // Collect flows and update UI immediately. Do not overwrite when EditText has focus.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    userDataManager.firstNameFlow.collect { first -> if (!etFirst.hasFocus()) first?.takeIf { it.isNotBlank() }?.let { etFirst.setText(it) } }
                }
                launch {
                    userDataManager.lastNameFlow.collect { last -> if (!etLast.hasFocus()) last?.takeIf { it.isNotBlank() }?.let { etLast.setText(it) } }
                }
                launch {
                    userDataManager.usernameFlow.collect { uname -> if (!etUser.hasFocus()) uname?.takeIf { it.isNotBlank() }?.let { etUser.setText(it) } }
                }
                launch {
                    userDataManager.dateOfBirthFlow.collect { dob -> if (!etDob.hasFocus()) dob?.takeIf { it.isNotBlank() }?.let { etDob.setText(it) } }
                }
                launch {
                    userDataManager.bioFlow.collect { bio -> if (!etBio.hasFocus()) bio?.takeIf { it.isNotBlank() }?.let { etBio.setText(it) } }
                }
                launch {
                    userDataManager.profileImageUrlFlow.collect { url -> ProfileImageHelper.loadProfileImageIntoView(requireContext(), imgView, url) }
                }
            }
        }

        etDob.apply { isFocusable = false; isClickable = true; isCursorVisible = false; setOnClickListener { showMaterialDatePicker(this) } }

        view.findViewById<ImageView>(R.id.back)?.setOnClickListener { runCatching { findNavController().popBackStack() } }
        view.findViewById<TextView>(R.id.tvChangeProfilePicture)?.setOnClickListener { runCatching { findNavController().navigate(R.id.action_profileFragment_to_changeProfilePicFragment) } }
        imgView.setOnClickListener { runCatching { findNavController().navigate(R.id.action_profileFragment_to_changeProfilePicFragment) } }

        // Save action
        view.findViewById<TextView>(R.id.tv_save)?.setOnClickListener {
            clearErrors()
            val firstVal = etFirst.text?.toString()?.trim().orEmpty()
            val lastVal = etLast.text?.toString()?.trim().orEmpty()
            val usernameVal = etUser.text?.toString()?.trim().orEmpty()
            val bioVal = etBio.text?.toString()?.trim().orEmpty()
            val dobUi = etDob.text?.toString()?.trim().orEmpty()

            var valid = true
            if (firstVal.isBlank()) { valid = false; showError(tvFirstNameError, getString(R.string.first_name_required)) }
            else if (firstVal.length > 25) { valid = false; showError(tvFirstNameError, getString(R.string.first_name_max_length)) }

            if (lastVal.isBlank()) { valid = false; showError(tvLastNameError, getString(R.string.last_name_required)) }
            else if (lastVal.length > 25) { valid = false; showError(tvLastNameError, getString(R.string.last_name_max_length)) }

            when (InputValidator.validateUsername(usernameVal)) {
                InputValidator.UsernameResult.VALID -> { }
                InputValidator.UsernameResult.EMPTY -> { valid = false; showError(tvUsernameError, getString(R.string.username_required)) }
                InputValidator.UsernameResult.HAS_SPACE -> { valid = false; showError(tvUsernameError, getString(R.string.username_no_spaces)) }
                InputValidator.UsernameResult.HAS_DIGIT -> { valid = false; showError(tvUsernameError, getString(R.string.username_invalid_chars)) }
                InputValidator.UsernameResult.INVALID_CHAR -> { valid = false; showError(tvUsernameError, getString(R.string.username_invalid_chars)) }
            }

            if (dobUi.isNotEmpty()) {
                try { java.time.LocalDate.parse(dobUi, dobFormat) }
                catch (_: DateTimeParseException) { valid = false; etDob.error = getString(R.string.invalid_dob_format) }
            }

            if (bioVal.length > 150) { valid = false; showError(tvBioError, getString(R.string.bio_max_length)) }

            if (!valid) return@setOnClickListener

            val apiDob: String = if (dobUi.isNotEmpty()) {
                val local = java.time.LocalDate.parse(dobUi, dobFormat)
                DateTimeFormatter.ISO_LOCAL_DATE.format(local)
            } else ""

            val req = com.example.myapplication.data.dashboard.model.UpdateProfileRequest(
                firstName = firstVal,
                lastName = lastVal,
                bio = bioVal,
                location = "",
                website = "",
                isPrivate = false,
                username = usernameVal,
            )

            setLoaderVisible(true)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val repo = com.example.myapplication.data.dashboard.DashboardRepository(requireContext())
                val success = runCatching { repo.updateProfile(req) }.getOrDefault(false)
                launch(Dispatchers.Main) {
                    setLoaderVisible(false)
                    Toast.makeText(requireContext(), if (success) getString(R.string.saved) else getString(R.string.update_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Fetch fresh profile only when Profile screen is visible
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { val repo = com.example.myapplication.data.dashboard.DashboardRepository(requireContext()); repo.getProfile() }
        }
    }

    private fun showMaterialDatePicker(target: EditText) {
        val selection: Long = runCatching {
            val text = target.text.toString().trim()
            val local = java.time.LocalDate.parse(text, dobFormat)
            local.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrElse { MaterialDatePicker.todayInUtcMilliseconds() }

        val constraints = CalendarConstraints.Builder().setEnd(System.currentTimeMillis()).build()
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText("Select date of birth").setCalendarConstraints(constraints).setSelection(selection).build()
        picker.addOnPositiveButtonClickListener { sel -> runCatching { val instant = Instant.ofEpochMilli(sel as Long); val local = instant.atZone(ZoneId.systemDefault()).toLocalDate(); target.setText(local.format(dobFormat)) } }
        picker.show(parentFragmentManager, "DOB_PICKER")
    }
}
