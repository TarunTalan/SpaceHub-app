package com.example.myapplication.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
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
            } catch (_: Exception) { }
        }

        // Inputs / feedback views
        val etUsername = view.findViewById<EditText>(R.id.etUsername)
        val tvUsernameError = view.findViewById<TextView>(R.id.tvUsernameError)
        val tvUsernameInstr = view.findViewById<TextView>(R.id.tvUsernameInstr)

        // Next button (start disabled) - will be enabled when a non-empty name is entered
        val nextBtn = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btn_next)

        // Helper to enable/disable Next button and swap background (dull when disabled)
        fun setNextEnabled(enabled: Boolean) {
            try {
                nextBtn.isEnabled = enabled
                val bgRes = if (enabled) R.drawable.rounded_button_bg else R.drawable.rounded_button_bg_dull_blue
                nextBtn.background = AppCompatResources.getDrawable(requireContext(), bgRes)
            } catch (_: Exception) { }
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

        // Prevent whitespace (spaces/newlines) from being entered or pasted into the username.
        // If the input contains whitespace, filter it out and show a brief error.
        try {
            etUsername?.filters = arrayOf(android.text.InputFilter { source, start, end, _, _, _ ->
                val segment = source.subSequence(start, end).toString()
                if (segment.isEmpty()) return@InputFilter null
                val filtered = segment.filterNot { it.isWhitespace() }
                if (filtered.length == segment.length) return@InputFilter null
                // whitespace was present; silently return the filtered content (without spaces)
                filtered.ifEmpty { "" }
            })
        } catch (_: Exception) { }

        // Prepare stroke ImageView reference once to avoid repeated lookups and accidental view state changes
        val strokeIv = view.findViewById<ImageView?>(R.id.profile_stroke)
        try {
            strokeIv?.visibility = View.VISIBLE
            // Give the stroke a stable elevation so it stays on top even when layout/IME changes occur
            val elevationPx = 8f * resources.displayMetrics.density
            strokeIv?.elevation = elevationPx
        } catch (_: Exception) { }

        // Clear error when user starts typing again and enable Next when non-empty
        etUsername?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (tvUsernameError?.visibility == View.VISIBLE) {
                    setUsernameError(null)
                }
                // Keep the outline unchanged (always white) — do not change stroke drawable here.

                // Enable Next only when trimmed username has at least one character
                try {
                    val hasName = !s.isNullOrBlank() && s.toString().trim().isNotEmpty()
                    setNextEnabled(hasName)
                } catch (_: Exception) { }
            }

             override fun afterTextChanged(s: Editable?) {}
        })

        // initialize Next button state based on any pre-filled text
        try {
            val initialHas = etUsername?.text?.toString()?.trim()?.isNotEmpty() == true
            setNextEnabled(initialHas)
        } catch (_: Exception) { }

        // Observe image path first and load using Glide with circleCrop for circular image
        sharedVm.selectedImagePath.observe(viewLifecycleOwner) { path ->
            if (!path.isNullOrEmpty()) {
                img.setImageDrawable(null)
                val f = File(path)
                Glide.with(this)
                    .load(f)
                    .signature(ObjectKey(f.absolutePath + "-" + f.lastModified()))
                    .circleCrop()
                    .listener(object : RequestListener<Drawable?> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<Drawable?>,
                            isFirstResource: Boolean
                        ): Boolean {
                            // keep it simple: log/toast
                            e?.printStackTrace()
                            // Return false so Glide can handle setting any error placeholder.
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<Drawable?>,
                            dataSource: com.bumptech.glide.load.DataSource?,
                            isFirstResource: Boolean
                        ): Boolean {
                            // The resource is ready. Return false so Glide can handle displaying it.
                            return false
                        }
                    })
                    .into(img)
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

        // Next button navigates to CreateCommunityFragment
        nextBtn.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_usernameFragment_to_createCommunityFragment)
            } catch (_: Exception) { }
        }
    }
}
