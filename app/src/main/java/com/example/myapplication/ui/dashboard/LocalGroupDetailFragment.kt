package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.ui.common.BaseFragment
import kotlinx.coroutines.launch

class LocalGroupDetailFragment : BaseFragment(R.layout.fragment_local_group_overview) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val communityId = args.getString("communityId") ?: args.getString("id")
        val imageUrlArg = args.getString("imageUrl")

        val titleTv = view.findViewById<TextView>(R.id.title)
        val subtitleTv = view.findViewById<TextView>(R.id.subtitle)
        val img = view.findViewById<ImageView>(R.id.img)

        // Use passed args immediately for quick UI update
        titleTv?.text = args.getString("name") ?: "Local Group"
        subtitleTv?.text = communityId ?: ""
        if (!imageUrlArg.isNullOrBlank()) Glide.with(this).load(imageUrlArg).placeholder(R.drawable.default_profile).into(img)

        // Fetch remote details and update UI
        communityId?.let { idStr ->
            lifecycleScope.launch {
                val repo = LocalGroupRepository.getInstance(requireContext())
                val res = repo.getLocalGroupDetails(idStr)
                if (res.isSuccess) {
                    val data = res.getOrThrow()
                    titleTv?.text = data.name
                    subtitleTv?.text = "Members: ${data.totalMembers}"
                    val imgUrl = data.imageUrl as? String
                    if (!imgUrl.isNullOrBlank()) Glide.with(this@LocalGroupDetailFragment).load(imgUrl).placeholder(R.drawable.default_profile).into(img)
                } else {
                    // leave the passed-in values
                }
            }
        }
    }
}
