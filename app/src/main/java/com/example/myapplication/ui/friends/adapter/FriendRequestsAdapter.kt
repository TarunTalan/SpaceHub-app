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
import com.example.myapplication.R
import com.example.myapplication.data.friends.model.IncomingFriendRequestItem

class FriendRequestsAdapter(
    private val onAccept: (IncomingFriendRequestItem) -> Unit,
    private val onReject: (IncomingFriendRequestItem) -> Unit
) : ListAdapter<IncomingFriendRequestItem, FriendRequestsAdapter.VH>(Diff) {

    private val processingIds = mutableSetOf<String>()

    object Diff : DiffUtil.ItemCallback<IncomingFriendRequestItem>() {
        override fun areItemsTheSame(oldItem: IncomingFriendRequestItem, newItem: IncomingFriendRequestItem) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: IncomingFriendRequestItem, newItem: IncomingFriendRequestItem) =
            oldItem == newItem
    }

    fun setProcessing(requestId: String?, processing: Boolean) {
        if (requestId == null) return
        if (processing) processingIds.add(requestId) else processingIds.remove(requestId)
        val idx = currentList.indexOfFirst { it.id == requestId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_user_avatar)
        private val tvName: TextView = itemView.findViewById(R.id.tv_user_name)
        private val tvEmail: TextView = itemView.findViewById(R.id.tv_user_email)
        private val btnAccept: View = itemView.findViewById(R.id.btn_accept)
        private val btnReject: View = itemView.findViewById(R.id.btn_reject)
        private val progress: ProgressBar = itemView.findViewById(R.id.progress)

        fun bind(item: IncomingFriendRequestItem) {
            // sanitize names: API sometimes returns literal "null" string. Treat that as missing.
            val first = item.firstName?.trim()?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            val last = item.lastName?.trim()?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            val displayName = when {
                first != null && last != null -> "$first $last"
                first != null -> first
                last != null -> last
                else -> item.email ?: "Unknown user"
            }
             tvName.text = displayName
             tvEmail.text = item.email ?: "-"

            // Optional avatar: we don't have an avatarUrl in the payload; show placeholder
            ivAvatar.setImageResource(R.drawable.default_profile)

            val isProcessing = processingIds.contains(item.id ?: "")
            val hasRequired = !item.email.isNullOrBlank()

            progress.visibility = if (isProcessing) View.VISIBLE else View.GONE
            btnAccept.isEnabled = !isProcessing && hasRequired
            btnReject.isEnabled = !isProcessing && hasRequired
            btnAccept.alpha = if (btnAccept.isEnabled) 1f else 0.5f
            btnReject.alpha = if (btnReject.isEnabled) 1f else 0.5f
            btnAccept.visibility = if (isProcessing) View.GONE else View.VISIBLE
            btnReject.visibility = if (isProcessing) View.GONE else View.VISIBLE

            btnAccept.setOnClickListener { if (!isProcessing && hasRequired) onAccept(item) }
            btnReject.setOnClickListener { if (!isProcessing && hasRequired) onReject(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend_request, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
