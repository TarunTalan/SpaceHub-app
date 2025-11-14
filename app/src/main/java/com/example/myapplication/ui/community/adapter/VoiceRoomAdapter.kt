package com.example.myapplication.ui.community.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.voice.model.VoiceRoomX

class VoiceRoomAdapter(
    private val onClick: (VoiceRoomX) -> Unit,
    private val onLongClick: (VoiceRoomX) -> Unit = {}
) : ListAdapter<VoiceRoomX, VoiceRoomAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<VoiceRoomX>() {
        override fun areItemsTheSame(oldItem: VoiceRoomX, newItem: VoiceRoomX) = oldItem.roomCode == newItem.roomCode
        override fun areContentsTheSame(oldItem: VoiceRoomX, newItem: VoiceRoomX) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvRoomName)
        fun bind(item: VoiceRoomX) {
            val raw = item.name.ifBlank { item.roomCode }
            val displayName = if (raw.startsWith("#")) raw else "#${raw}"
            tvName.text = displayName
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener { onLongClick(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_room, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}

