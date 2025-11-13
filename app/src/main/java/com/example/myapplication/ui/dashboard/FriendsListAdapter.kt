package com.example.myapplication.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.BuildConfig
import com.example.myapplication.R
import com.example.myapplication.data.friends.model.Data

class FriendsListAdapter(
    private val onClick: (Data) -> Unit
) : ListAdapter<Data, FriendsListAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Data>() {
        override fun areItemsTheSame(oldItem: Data, newItem: Data) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Data, newItem: Data) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar)
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvEmail: TextView = view.findViewById(R.id.tv_email)

        fun bind(item: Data) {
            // Prefer explicit username from API, fallback to first/last name, then email
            val sanitizedUsername = item.username?.trim()?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            val first = item.firstName?.trim()?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            val last = item.lastName?.trim()?.takeIf { it.isNotBlank() && it.lowercase() != "null" }

            val displayName = when {
                sanitizedUsername != null -> sanitizedUsername
                first != null && last != null -> "$first $last"
                first != null -> first
                last != null -> last
                else -> item.email
            } ?: "Unknown user"

            tvName.text = displayName
            tvEmail.text = item.email ?: ""

            // Normalize avatar URL - API returns full https URLs in many cases
            val avatarUrl = item.avatarUrl?.trim()?.let { raw ->
                when {
                    raw.isBlank() -> null
                    raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true) -> raw
                    // For relative paths, construct full API URL
                    else -> "${BuildConfig.BASE_URL.trimEnd('/')}/${raw.trimStart('/')}"
                }
            }

            // Load avatar with Glide (auth headers added by AuthenticatedGlideModule)
            Glide.with(ivAvatar.context)
                .load(avatarUrl ?: R.drawable.default_profile)
                .placeholder(R.drawable.default_profile)
                .error(R.drawable.default_profile)
                .circleCrop()
                .into(ivAvatar)

            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_friend_simple, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
