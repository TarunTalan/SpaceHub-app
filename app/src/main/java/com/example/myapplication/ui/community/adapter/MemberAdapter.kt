package com.example.myapplication.ui.community.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.community.model.Member

class MemberAdapter(
    private val onChangeRole: (Member, String) -> Unit,
    private val onRemove: (Member) -> Unit,
) : ListAdapter<Member, MemberAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Member>() {
        override fun areItemsTheSame(oldItem: Member, newItem: Member) = oldItem.memberId == newItem.memberId
        override fun areContentsTheSame(oldItem: Member, newItem: Member) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvMemberName)
        private val tvRole: TextView = view.findViewById(R.id.tvMemberRole)
        private val btnPromote: Button = view.findViewById(R.id.btnPromote)
        private val btnRemove: Button = view.findViewById(R.id.btnRemove)

        fun bind(item: Member) {
            tvName.text = item.username
            tvRole.text = item.role
            btnPromote.setOnClickListener { onChangeRole(item, if (item.role.equals("ADMIN", true)) "MEMBER" else "ADMIN") }
            btnRemove.setOnClickListener { onRemove(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}

