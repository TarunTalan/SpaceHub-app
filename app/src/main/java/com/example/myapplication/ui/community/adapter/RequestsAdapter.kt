package com.example.myapplication.ui.community.adapter

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
import com.example.myapplication.data.community.model.PendingRequest

class RequestsAdapter(
    private val onAccept: (PendingRequest) -> Unit,
    private val onReject: (PendingRequest) -> Unit
) : ListAdapter<PendingRequest, RequestsAdapter.VH>(Diff) {

    private val processingIds = mutableSetOf<String>()

    object Diff : DiffUtil.ItemCallback<PendingRequest>() {
        override fun areItemsTheSame(oldItem: PendingRequest, newItem: PendingRequest) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PendingRequest, newItem: PendingRequest) = oldItem == newItem
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
        private val tvCommunity: TextView = itemView.findViewById(R.id.tv_community_name)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_request_time)
        private val btnAccept: View = itemView.findViewById(R.id.btn_accept)
        private val btnReject: View = itemView.findViewById(R.id.btn_reject)
        private val progress: ProgressBar = itemView.findViewById(R.id.progress)

        fun bind(request: PendingRequest) {
            val displayName = when {
                !request.userName.isNullOrBlank() -> request.userName
                !request.userEmail.isNullOrBlank() -> request.userEmail
                else -> "Unknown user"
            }
            tvName.text = displayName

            val comm = request.communityName?.takeIf { it.isNotBlank() } ?: "-"
            tvCommunity.text = "wants to join $comm"
            tvTime.text = formatTime(request.requestedAt)

            // Load avatar
            if (!request.userAvatarUrl.isNullOrBlank()) {
                Glide.with(itemView)
                    .load(request.userAvatarUrl)
                    .placeholder(R.drawable.default_profile)
                    .error(R.drawable.default_profile)
                    .circleCrop()
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.default_profile)
            }

            val isProcessing = processingIds.contains(request.id ?: "")
            val hasRequired = !request.userEmail.isNullOrBlank() && !request.communityName.isNullOrBlank()

            progress.visibility = if (isProcessing) View.VISIBLE else View.GONE
            btnAccept.isEnabled = !isProcessing && hasRequired
            btnReject.isEnabled = !isProcessing && hasRequired
            btnAccept.alpha = if (btnAccept.isEnabled) 1f else 0.5f
            btnReject.alpha = if (btnReject.isEnabled) 1f else 0.5f
            btnAccept.visibility = if (isProcessing) View.GONE else View.VISIBLE
            btnReject.visibility = if (isProcessing) View.GONE else View.VISIBLE

            btnAccept.setOnClickListener {
                if (!isProcessing && hasRequired) onAccept(request)
            }
            btnReject.setOnClickListener {
                if (!isProcessing && hasRequired) onReject(request)
            }
        }

        private fun formatTime(timestamp: String?): String {
            return "Recently"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
