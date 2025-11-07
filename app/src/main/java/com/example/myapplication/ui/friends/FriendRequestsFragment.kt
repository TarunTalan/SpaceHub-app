package com.example.myapplication.ui.friends

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.myapplication.R
import com.example.myapplication.ui.friends.adapter.FriendRequestsAdapter
import com.example.myapplication.ui.friends.viewmodel.FriendRequestsViewModel

class FriendRequestsFragment : Fragment(R.layout.fragment_friend_requests) {

    private val vm: FriendRequestsViewModel by viewModels()
    private lateinit var adapter: FriendRequestsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rv_requests)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val empty = view.findViewById<TextView>(R.id.empty_view)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)

        adapter = FriendRequestsAdapter(
            onAccept = { item ->
                adapter.setProcessing(item.id, true)
                vm.accept(item)
            },
            onReject = { item ->
                adapter.setProcessing(item.id, true)
                vm.reject(item)
            }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        swipe.setOnRefreshListener { vm.loadRequests() }

        vm.requests.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            swipe.isRefreshing = false
        }

        vm.loading.observe(viewLifecycleOwner) { isLoading ->
            progress.visibility = if (isLoading) View.VISIBLE else View.GONE
            if (!isLoading) swipe.isRefreshing = false
        }

        vm.toast.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        vm.processingComplete.observe(viewLifecycleOwner) { id ->
            adapter.setProcessing(id, false)
        }

        vm.loadRequests()
    }
}

