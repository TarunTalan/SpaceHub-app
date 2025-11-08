package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileImageHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalGroupDetailFragment : BaseFragment(R.layout.fragment_local_group_overview) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val communityId = args.getString("communityId") ?: args.getString("id")
        val imageUrlArg = args.getString("imageUrl")

        val headerNameTv = view.findViewById<TextView>(R.id.local_group_name)
        val titleTv = view.findViewById<TextView>(R.id.title)
        val subtitleTv = view.findViewById<TextView>(R.id.subtitle)
        val img = view.findViewById<ImageView>(R.id.img)
        val btnRequest = view.findViewById<AppCompatButton>(R.id.btn_request)
        if (btnRequest == null) return

        // Fast UI from args
        val passedName = args.getString("name")
        headerNameTv?.text = passedName ?: "Local Group"
        titleTv?.text = passedName ?: "Local Group"
        subtitleTv?.text = communityId ?: ""
        // Load preview image or placeholder
        if (!imageUrlArg.isNullOrBlank()) {
            Glide.with(this).load(imageUrlArg).placeholder(R.drawable.default_profile).into(img)
        } else {
            img?.setImageResource(R.drawable.default_comm_icon)
        }

        // Default button state until we fetch details
        btnRequest.isEnabled = false

        communityId?.let { idStr ->
            lifecycleScope.launch {
                val repo = LocalGroupRepository.getInstance(requireContext())
                val res = repo.getLocalGroupDetails(idStr)
                if (res.isSuccess) {
                    val data = res.getOrThrow()
                    // Update UI with authoritative values
                    headerNameTv?.text = data.name
                    titleTv?.text = data.name
                    subtitleTv?.text = "Members: ${data.totalMembers}"


                    // Manage request/join button
                    val myEmail = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext()).getEmail()
                    val isMember = data.memberEmails.any { it.equals(myEmail, true) }
                    // Use existing `join` string for the button label when not a member
                    btnRequest.text = if (isMember) getString(R.string.requested) else getString(R.string.join)
                    btnRequest.isEnabled = !isMember

                    btnRequest.setOnClickListener {
                        if (isMember) return@setOnClickListener
                        lifecycleScope.launch {
                            btnRequest.isEnabled = false
                            val joinRes = withContext(Dispatchers.IO) { repo.requestToJoinLocalGroup(idStr) }
                            if (joinRes.isSuccess) {
                                Snackbar.make(view, "Request sent", Snackbar.LENGTH_SHORT).show()
                                btnRequest.text = getString(R.string.requested)
                            } else {
                                Snackbar.make(view, "Failed to send request", Snackbar.LENGTH_SHORT).show()
                                btnRequest.isEnabled = true
                            }
                        }
                    }
                } else {
                    // Keep passed-in quick values and allow request action
                    btnRequest.isEnabled = true
                }
            }
        } ?: run {
            // no id — enable request to be safe
            btnRequest.isEnabled = true
        }
    }
}
