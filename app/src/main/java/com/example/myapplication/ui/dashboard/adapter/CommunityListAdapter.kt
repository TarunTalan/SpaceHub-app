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

// Add communityId (string from server) so callers can navigate using the real id
data class CommunityUi(
    val communityId: String,
    val id: Int,
    val name: String,
    val imageUrl: String?,
    val subtitle: String? = null,
    val isLocal: Boolean = false,
    val isMember: Boolean = false,
    val isOwner: Boolean = false,
    val isAdmin: Boolean = false
)

class CommunityListAdapter(
    private val onItemClick: (CommunityUi) -> Unit,
    private val onJoinClick: ((CommunityUi) -> Unit)? = null
) : ListAdapter<CommunityUi, CommunityListAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    companion object {
        private const val VIEW_COMMUNITY = 0
        private const val VIEW_LOCAL = 1
    }

    // Track loading/requested state by item id to drive UI without mutating source list
    private val loadingMap = mutableMapOf<Int, Boolean>()
    private val requestedMap = mutableMapOf<Int, Boolean>()

    fun setLoading(id: Int, loading: Boolean) {
        loadingMap[id] = loading
        val idx = currentList.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun setRequested(id: Int, requested: Boolean) {
        requestedMap[id] = requested
        val idx = currentList.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    object Diff : DiffUtil.ItemCallback<CommunityUi>() {
        override fun areItemsTheSame(oldItem: CommunityUi, newItem: CommunityUi): Boolean = oldItem.id == newItem.id && oldItem.isLocal == newItem.isLocal
        override fun areContentsTheSame(oldItem: CommunityUi, newItem: CommunityUi): Boolean = oldItem == newItem
    }

    override fun getItemViewType(position: Int): Int = if (getItem(position).isLocal) VIEW_LOCAL else VIEW_COMMUNITY

    override fun getItemId(position: Int): Long {
        val base = getItem(position).id.toLong()
        return if (getItem(position).isLocal) base or (1L shl 32) else base
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val img: ImageView? = itemView.findViewById(R.id.ivCover) ?: itemView.findViewById(R.id.imgAvatar)
        private val tvName: TextView? = itemView.findViewById(R.id.tvName)
        private val tvSubtitle: TextView? = itemView.findViewById(R.id.tvDesc) ?: itemView.findViewById(R.id.tvSubtitle)
        private val btnJoinRequest: TextView? = itemView.findViewById(R.id.btnJoinRequest)
        private val btnProgress: ProgressBar? = itemView.findViewById(R.id.btnJoinProgress)
        private val tvRoleBadge: TextView? = itemView.findViewById(R.id.tvRoleBadge)

        fun bind(item: CommunityUi) {
            tvName?.text = item.name
            tvSubtitle?.text = item.subtitle ?: ""

            if (!item.imageUrl.isNullOrBlank()) {
                img?.let {
                    Glide.with(itemView)
                        .load(item.imageUrl)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(it)
                }
            } else {
                img?.setImageResource(R.drawable.default_profile)
            }

            itemView.setOnClickListener { onItemClick(item) }

            // Determine if join controls should be visible: hide when user is already member/owner/admin
            val alreadyMember = item.isMember || item.isOwner || item.isAdmin

            // Role badge: show Owner/Admin if applicable
            when {
                item.isOwner -> {
                    tvRoleBadge?.visibility = View.VISIBLE
                    tvRoleBadge?.text = itemView.context.getString(R.string.role_owner)
                }
                item.isAdmin -> {
                    tvRoleBadge?.visibility = View.VISIBLE
                    tvRoleBadge?.text = itemView.context.getString(R.string.role_admin)
                }
                else -> tvRoleBadge?.visibility = View.GONE
            }

            if (alreadyMember) {
                // hide join button and progress
                btnProgress?.visibility = View.GONE
                btnJoinRequest?.visibility = View.GONE
            } else {
                // show join controls
                btnJoinRequest?.visibility = View.VISIBLE
                val isLoading = loadingMap[item.id] == true
                val isRequested = requestedMap[item.id] == true

                btnProgress?.visibility = if (isLoading) View.VISIBLE else View.GONE
                btnJoinRequest?.isEnabled = !isLoading && !isRequested
                btnJoinRequest?.let { btn -> btn.alpha = if (btn.isEnabled) 1f else 0.6f }
                btnJoinRequest?.text = if (isRequested) itemView.context.getString(R.string.requested) else itemView.context.getString(R.string.join)

                btnJoinRequest?.setOnClickListener {
                    if (onJoinClick != null && !isLoading && !isRequested) {
                        onJoinClick.invoke(item)
                    } else if (!isRequested) {
                        onItemClick(item)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutRes = if (viewType == VIEW_LOCAL) R.layout.item_local_group_card else R.layout.item_community_card
        val v = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(getItem(position)) }
}
