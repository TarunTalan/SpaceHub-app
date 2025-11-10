package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.group.adapter.GroupMemberAdapter
import com.example.myapplication.ui.group.viewmodel.GroupDetailViewModel
import com.example.myapplication.ui.common.ProfileImageHelper
import com.example.myapplication.data.user.UserDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupMembersFragment : Fragment(R.layout.fragment_group_members) {
    // Share the same activity-scoped ViewModel so members loaded in detail are reflected here
    private val vm: GroupDetailViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rv_group_members)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        // obtain current user's email to show "You" in the list for own entry
        val udm = UserDataManager.getInstance(requireContext())
        var currentEmail: String? = null
        viewLifecycleOwner.lifecycleScope.launch {
            try { currentEmail = udm.getEmail() } catch (_: Exception) { currentEmail = null }
            val adapter = GroupMemberAdapter(currentEmail, onClick = {})
            rv.adapter = adapter

            // observe members and submit to adapter
            vm.members.observe(viewLifecycleOwner) { list ->
                android.util.Log.d("GroupMembersFragment", "members observer: received list size=${list.size}")
                val submit = list.toList()
                adapter.submitList(submit) {
                    android.util.Log.d("GroupMembersFragment", "submitList commitCallback: adapter.itemCount=${adapter.itemCount}")
                }
                android.util.Log.d("GroupMembersFragment", "members observer: adapter.submitList requested")
            }
        }


    }
}
