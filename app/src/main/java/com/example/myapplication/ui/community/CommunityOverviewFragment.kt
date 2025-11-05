package com.example.myapplication.ui.community

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.ui.community.viewmodel.CommunityOverviewViewModel

class CommunityOverviewFragment : Fragment(R.layout.fragment_community_overview) {

    private val vm: CommunityOverviewViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val communityId = args.getString("communityId").orEmpty()
        val name = args.getString("name").orEmpty()
        val imageUrl = args.getString("imageUrl").orEmpty()
        val description = args.getString("description").orEmpty()
        val isRequested = args.getBoolean("isRequested", false)

        val ivBack = view.findViewById<View>(R.id.imageView)
        val ivBanner = view.findViewById<ImageView>(R.id.community_banner)
        val ivImage = view.findViewById<ImageView>(R.id.community_image)
        val tvName = view.findViewById<TextView>(R.id.community_name)
        val tvDesc = view.findViewById<TextView>(R.id.Community_description)
        val tvMemberCount = view.findViewById<TextView>(R.id.member_count_tv)
        val tvAdminCount = view.findViewById<TextView>(R.id.admin_count_tv)
        val btnRequest = view.findViewById<TextView>(R.id.request_join_btn)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        ivBack?.setOnClickListener { findNavController().navigateUp() }

        // Load community data from API
        vm.loadCommunity(communityId, name, imageUrl, description)

        // Observe ViewModel data
        vm.communityName.observe(viewLifecycleOwner) { tvName.text = it }
        vm.description.observe(viewLifecycleOwner) { tvDesc.text = it }
        vm.memberCount.observe(viewLifecycleOwner) { tvMemberCount.text = it.toString() }
        vm.adminCount.observe(viewLifecycleOwner) { tvAdminCount.text = it.toString() }

        vm.imageUrl.observe(viewLifecycleOwner) { url ->
            if (!url.isNullOrBlank()) {
                Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.default_comm_icon)
                    .error(R.drawable.default_comm_icon)
                    .circleCrop()
                    .into(ivImage)
            }
        }

        vm.bannerUrl.observe(viewLifecycleOwner) { url ->
            if (!url.isNullOrBlank()) {
                Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.banner)
                    .error(R.drawable.banner)
                    .into(ivBanner)
            }
        }

        vm.loading.observe(viewLifecycleOwner) { loading ->
            progress.visibility = if (loading) View.VISIBLE else View.GONE
        }

        vm.requestInProgress.observe(viewLifecycleOwner) { inProgress ->
            btnRequest.isEnabled = !inProgress && vm.requestSent.value != true
            btnRequest.alpha = if (btnRequest.isEnabled) 1f else 0.6f
        }

        vm.requestSent.observe(viewLifecycleOwner) { sent ->
            if (sent) {
                btnRequest.text = getString(R.string.requested)
                btnRequest.isEnabled = false
                btnRequest.alpha = 0.6f
            }
        }

        vm.toast.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Set initial state if already requested
        if (isRequested) {
            btnRequest.isEnabled = false
            btnRequest.alpha = 0.6f
            btnRequest.text = getString(R.string.requested)
        }

        btnRequest.setOnClickListener {
            vm.requestToJoin(vm.communityName.value ?: name)
        }
    }
}