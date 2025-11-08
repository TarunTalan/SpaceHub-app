package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.group.adapter.GroupMemberAdapter
import com.example.myapplication.ui.group.viewmodel.GroupDetailViewModel
import kotlinx.coroutines.launch

class GroupMembersFragment : Fragment(R.layout.fragment_group_members) {
    // Share the same activity-scoped ViewModel so members loaded in detail are reflected here
    private val vm: GroupDetailViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rv_group_members)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        val adapter = GroupMemberAdapter(onClick = {})
        rv.adapter = adapter

        android.util.Log.d("GroupMembersFragment", "onViewCreated: vm.hash=${vm.hashCode()} initial adapter.count=${adapter.itemCount}")

        // Log when adapter content changes
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                android.util.Log.d("GroupMembersFragment", "adapter observer: onChanged itemCount=${adapter.itemCount}")
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                android.util.Log.d("GroupMembersFragment", "adapter observer: inserted pos=$positionStart count=$itemCount total=${adapter.itemCount}")
            }
        })

        vm.members.observe(viewLifecycleOwner) { list ->
            android.util.Log.d("GroupMembersFragment", "members observer: received list size=${list.size}")
            // create a fresh list to ensure DiffUtil sees changes
            val submit = list.toList()
            adapter.submitList(submit) {
                android.util.Log.d("GroupMembersFragment", "submitList commitCallback: adapter.itemCount=${adapter.itemCount}")
            }
            android.util.Log.d("GroupMembersFragment", "members observer: adapter.submitList requested")
        }

        // read group id from args passed during navigation and ensure VM is loaded
        val groupId = arguments?.getString("communityId")
        groupId?.let { vm.setGroupId(it) }

        // load fresh
        lifecycleScope.launch { vm.loadMembers() }
    }
}
