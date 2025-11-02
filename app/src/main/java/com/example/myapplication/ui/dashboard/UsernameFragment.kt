package com.example.myapplication.ui.dashboard

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.edit
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.data.dashboard.DashboardRepository
import com.example.myapplication.data.dashboard.DashboardResult
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.InputValidator
import com.example.myapplication.ui.common.ProfileSharedViewModel
import kotlinx.coroutines.launch
import java.io.File

class UsernameFragment : BaseFragment(R.layout.fragment_username) {
    private val sharedVm: ProfileSharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val img = view.findViewById<ImageView>(R.id.profile)
        // back arrow - navigate back to choose profile pic
        val backArrow = view.findViewById<ImageView>(R.id.back_arrow)
        backArrow?.setOnClickListener {
            try {
                findNavController().navigateUp()
            } catch (_: Exception) {
            }
        }

        val etUsername = view.findViewById<EditText>(R.id.etUsername)
        val tvUsernameError = view.findViewById<TextView>(R.id.tvUsernameError)
        val tvUsernameInstr = view.findViewById<TextView>(R.id.tvUsernameInstr)

        // Next button (start disabled) - will be enabled when a non-empty name is entered
        val nextBtn = view.findViewById<AppCompatButton>(R.id.btn_next)

        // Helper to enable/disable Next button and swap background (dull when disabled)
        fun setNextEnabled(enabled: Boolean) {
            try {
                nextBtn.isEnabled = enabled
                val bgRes = if (enabled) R.drawable.rounded_button_bg else R.drawable.rounded_button_bg_dull_blue
                nextBtn.background = AppCompatResources.getDrawable(requireContext(), bgRes)
            } catch (_: Exception) {
            }
        }

        // Helper to show or clear username error and toggle instruction visibility
        fun setUsernameError(error: String?) {
            if (error.isNullOrBlank()) {
                tvUsernameError?.visibility = View.GONE
                tvUsernameInstr?.visibility = View.VISIBLE
            } else {
                tvUsernameError?.text = error
                tvUsernameError?.visibility = View.VISIBLE
                tvUsernameInstr?.visibility = View.GONE
            }
        }

        try {
            etUsername?.filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                val segment = source.subSequence(start, end).toString()
                if (segment.isEmpty()) return@InputFilter null
                val filtered = segment.filterNot { it.isWhitespace() }
                if (filtered.length == segment.length) return@InputFilter null
                filtered.ifEmpty { "" }
            })
        } catch (_: Exception) {
        }

        val strokeIv = view.findViewById<ImageView?>(R.id.profile_stroke)
        try {
            strokeIv?.visibility = View.VISIBLE
            val elevationPx = 8f * resources.displayMetrics.density
            strokeIv?.elevation = elevationPx
        } catch (_: Exception) {
        }

        // Clear error when user starts typing again and enable Next when non-empty
        etUsername?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (tvUsernameError?.visibility == View.VISIBLE) {
                    setUsernameError(null)
                }

                // Enable Next only when trimmed username has at least one character
                try {
                    val hasName = !s.isNullOrBlank() && s.toString().trim().isNotEmpty()
                    setNextEnabled(hasName)
                } catch (_: Exception) {
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // initialize Next button state based on any pre-filled text
        try {
            val initialHas = etUsername?.text?.toString()?.trim()?.isNotEmpty() == true
            setNextEnabled(initialHas)
        } catch (_: Exception) {
        }

        // Observe image path first and load using Glide with circleCrop for circular image
        sharedVm.selectedImagePath.observe(viewLifecycleOwner) { path ->
            if (!path.isNullOrEmpty()) {
                img.setImageDrawable(null)
                val f = File(path)
                Glide.with(this).load(f).signature(ObjectKey(f.absolutePath + "-" + f.lastModified())).circleCrop()
                    .listener(object : RequestListener<Drawable?> {
                        override fun onLoadFailed(
                            e: GlideException?, model: Any?, target: Target<Drawable?>, isFirstResource: Boolean
                        ): Boolean {
                            e?.printStackTrace()
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable?,
                            model: Any?,
                            target: Target<Drawable?>,
                            dataSource: DataSource?,
                            isFirstResource: Boolean
                        ): Boolean {
                            return false
                        }
                    }).into(img)
                return@observe
            }
        }

        // Fallback to drawable resource (if provided)
        sharedVm.selectedDrawableRes.observe(viewLifecycleOwner) { resId ->
            if (resId != null) {
                img.setImageDrawable(null)
                Glide.with(this).load(resId).circleCrop().into(img)
            }
        }

        // Next button navigates to CreateCommunityFragment after validating username via API
        nextBtn.setOnClickListener {
            try {
                val username = etUsername?.text?.toString()?.trim().orEmpty()
                // Local validation first
                when (InputValidator.validateUsername(username)) {
                    InputValidator.UsernameResult.EMPTY -> {
                        setUsernameError(getString(R.string.username_required))
                        return@setOnClickListener
                    }

                    InputValidator.UsernameResult.HAS_SPACE -> {
                        setUsernameError(getString(R.string.username_no_spaces))
                        return@setOnClickListener
                    }

                    InputValidator.UsernameResult.INVALID_CHAR -> {
                        setUsernameError(getString(R.string.username_invalid_chars))
                        return@setOnClickListener
                    }

                    else -> { /* valid locally */
                    }
                }

                // Proceed with server-side validation
                // We need the signup email passed earlier (if any); prefer nav-arg, fallback to SharedPreferences
                val emailArg = try {
                    val fromArgs = arguments?.getString("email")
                    if (!fromArgs.isNullOrBlank()) fromArgs
                    else try {
                        requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("email", "")
                            ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                } catch (_: Exception) {
                    ""
                }

                // show loader
                setLoaderVisible(true)
                lifecycleScope.launch {
                    val repo = DashboardRepository(requireContext())
                    val result = repo.validateUsername(emailArg, username)
                    setLoaderVisible(false)

                    when (result) {
                        is DashboardResult.Success -> {
                            // Save to UserDataManager for centralized data persistence
                            val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext())
                            userDataManager.updateProfile(username = result.username)

                            // Also save to SharedPreferences for backward compatibility
                            try {
                                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                prefs.edit { putString("username", result.username) }
                            } catch (_: Exception) {}

                            try { sharedVm.setDrawableRes(null) } catch (_: Exception) {}
                            try {
                                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                prefs.edit { putString("last_screen", "username") }
                            } catch (_: Exception) {}
                            try { findNavController().navigate(R.id.action_usernameFragment_to_dashboardFragment) } catch (_: Exception) {}
                        }

                        is DashboardResult.Error -> {
                            val errorMsg = when (result.status) {
                                409 -> getString(R.string.username_taken)
                                400 -> getString(R.string.username_invalid_server)
                                413 -> getString(R.string.username_too_long)
                                500, 502, 503, 504 -> getString(R.string.server_error)
                                else -> getString(R.string.username_error_generic)
                            }
                            setUsernameError(errorMsg)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}