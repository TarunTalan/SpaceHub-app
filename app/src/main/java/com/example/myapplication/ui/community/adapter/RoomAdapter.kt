package com.example.myapplication.ui.community.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.community.model.DataRoom

class RoomAdapter(
    private val onClick: (DataRoom) -> Unit,
    private val onLongClick: (DataRoom) -> Unit = { }
) : ListAdapter<DataRoom, RoomAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long {
        return try { getItem(position).id.hashCode().toLong() } catch (_: Exception) { super.getItemId(position) }
    }

    object Diff : DiffUtil.ItemCallback<DataRoom>() {
        override fun areItemsTheSame(oldItem: DataRoom, newItem: DataRoom) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DataRoom, newItem: DataRoom) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvRoomName)
        private val tvType: TextView = view.findViewById(R.id.tvRoomType)
        fun bind(item: DataRoom) {
            // Prefer name if present; fallback to id
            tvName.text = item.name.ifBlank { item.id }
            tvType.text = item.roomCode
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
