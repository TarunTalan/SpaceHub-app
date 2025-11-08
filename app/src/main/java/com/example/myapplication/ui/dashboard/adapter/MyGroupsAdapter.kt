package com.example.myapplication.ui.dashboard.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R

/**
 * Simple adapter dedicated for showing "My Groups" cards. Uses `group_card_item.xml` layout.
 * Reuses CommunityUi for data shape so conversion is minimal.
 */
class MyGroupsAdapter(
    private val onClick: (CommunityUi) -> Unit
) : ListAdapter<CommunityUi, MyGroupsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<CommunityUi>() {
            override fun areItemsTheSame(oldItem: CommunityUi, newItem: CommunityUi): Boolean = oldItem.communityId == newItem.communityId
            override fun areContentsTheSame(oldItem: CommunityUi, newItem: CommunityUi): Boolean = oldItem == newItem
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView? = itemView.findViewById(R.id.ivCover)
        private val tvName: TextView? = itemView.findViewById(R.id.tvName)
        private val tvDesc: TextView? = itemView.findViewById(R.id.tvDesc)
        private val btnJoin: TextView? = itemView.findViewById(R.id.btnJoinRequest)
        private val progress: ProgressBar? = itemView.findViewById(R.id.btnJoinProgress)

        fun bind(item: CommunityUi) {
            // Bind data
            tvName?.text = item.name
            tvDesc?.text = item.subtitle ?: ""
            val url = item.imageUrl
            if (!url.isNullOrBlank()) {
                ivCover?.let { Glide.with(it).load(url).placeholder(R.drawable.default_profile).error(R.drawable.default_profile).circleCrop().into(it) }
            } else ivCover?.setImageResource(R.drawable.default_profile)

            // Ensure the item view is clickable; simple, no debug logs or toasts
            itemView.isClickable = true
            itemView.setOnClickListener {
                try { onClick(item) } catch (_: Exception) { }
            }

            // Hide join controls for group card
            try { btnJoin?.visibility = View.GONE } catch (_: Exception) {}
            try { progress?.visibility = View.GONE } catch (_: Exception) {}
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_local_group_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
