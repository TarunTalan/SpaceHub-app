package com.example.myapplication.ui.friends.adapter

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
import com.example.myapplication.data.friends.model.UserSearchResult

class UserSearchAdapter(
    private val onAddFriend: (UserSearchResult) -> Unit
) : ListAdapter<UserSearchResult, UserSearchAdapter.VH>(Diff) {

    private val loadingMap = mutableMapOf<String, Boolean>()

    fun setLoading(userEmail: String, loading: Boolean) {
        loadingMap[userEmail] = loading
        val idx = currentList.indexOfFirst { it.email == userEmail }
        if (idx >= 0) notifyItemChanged(idx)
    }

    object Diff : DiffUtil.ItemCallback<UserSearchResult>() {
        override fun areItemsTheSame(oldItem: UserSearchResult, newItem: UserSearchResult) =
            oldItem.email == newItem.email
        override fun areContentsTheSame(oldItem: UserSearchResult, newItem: UserSearchResult) =
            oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivAvatar: ImageView = view.findViewById(R.id.ivUserAvatar)
        private val tvUsername: TextView = view.findViewById(R.id.tvUsername)
        private val tvName: TextView = view.findViewById(R.id.tvUserName)
        private val tvBio: TextView = view.findViewById(R.id.tvUserBio)
        private val btnAddFriend: TextView = view.findViewById(R.id.btnAddFriend)
        private val progressBar: ProgressBar = view.findViewById(R.id.progressAddFriend)

        fun bind(user: UserSearchResult) {
            tvUsername.text = "@${user.username}"

            val fullName = listOfNotNull(user.firstName, user.lastName).joinToString(" ")
            tvName.text = fullName.ifBlank { user.username }

            tvBio.text = user.bio ?: ""
            tvBio.visibility = if (user.bio.isNullOrBlank()) View.GONE else View.VISIBLE

            // Load avatar using a context-bound RequestManager
            if (!user.avatarUrl.isNullOrBlank()) {
                Glide.with(ivAvatar.context)
                    .load(user.avatarUrl)
                    .placeholder(R.drawable.default_profile)
                    .error(R.drawable.default_profile)
                    .circleCrop()
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.default_profile)
            }

            // Handle button state
            val isLoading = loadingMap[user.email] == true

            when {
                user.isFriend -> {
                    btnAddFriend.text = "Friend"
                    btnAddFriend.isEnabled = false
                    btnAddFriend.alpha = 0.6f
                    progressBar.visibility = View.GONE
                }
                user.isPending -> {
                    btnAddFriend.text = "Requested"
                    btnAddFriend.isEnabled = false
                    btnAddFriend.alpha = 0.6f
                    progressBar.visibility = View.GONE
                }
                isLoading -> {
                    btnAddFriend.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                }
                else -> {
                    btnAddFriend.text = "Add Friend"
                    btnAddFriend.isEnabled = true
                    btnAddFriend.alpha = 1f
                    btnAddFriend.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    btnAddFriend.setOnClickListener { onAddFriend(user) }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_search, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
