package com.example.myapplication.ui.notifications

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.myapplication.R
import com.example.myapplication.ui.friends.adapter.FriendRequestsAdapter
import com.example.myapplication.ui.friends.viewmodel.FriendRequestsViewModel
import com.example.myapplication.ui.community.adapter.RequestsAdapter
import com.example.myapplication.ui.community.viewmodel.RequestsInboxViewModel
import kotlinx.coroutines.launch

class NotificationsFragment : Fragment(R.layout.fragment_notifications) {

    private val friendVm: FriendRequestsViewModel by viewModels()
    private val joinVm: RequestsInboxViewModel by viewModels()

    private lateinit var friendAdapter: FriendRequestsAdapter
    private lateinit var joinAdapter: RequestsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        val rvFriends = view.findViewById<RecyclerView>(R.id.rv_friend_requests)
        val rvJoins = view.findViewById<RecyclerView>(R.id.rv_join_requests)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val empty = view.findViewById<TextView>(R.id.tv_empty)

        friendAdapter = FriendRequestsAdapter(onAccept = { item ->
            friendAdapter.setProcessing(item.id, true)
            friendVm.accept(item)
        }, onReject = { item ->
            friendAdapter.setProcessing(item.id, true)
            friendVm.reject(item)
        })

        joinAdapter = RequestsAdapter(onAccept = { req ->
            joinAdapter.setProcessing(req.id, true)
            joinVm.acceptRequest(req)
        }, onReject = { req ->
            joinAdapter.setProcessing(req.id, true)
            joinVm.rejectRequest(req)
        })

        rvFriends.layoutManager = LinearLayoutManager(requireContext())
        rvFriends.adapter = friendAdapter

        rvJoins.layoutManager = LinearLayoutManager(requireContext())
        rvJoins.adapter = joinAdapter

        swipe.setOnRefreshListener { loadAll() }

        friendVm.requests.observe(viewLifecycleOwner) { list ->
            friendAdapter.submitList(list)
            updateEmptyVisibility()
            // ensure empty view updated immediately
            empty.visibility = if (friendAdapter.currentList.isEmpty() && joinAdapter.currentList.isEmpty()) View.VISIBLE else View.GONE
        }
        friendVm.loading.observe(viewLifecycleOwner) { loading ->
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            if (!loading) swipe.isRefreshing = false
        }
        friendVm.toast.observe(viewLifecycleOwner) { msg -> if (!msg.isNullOrBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
        friendVm.processingComplete.observe(viewLifecycleOwner) { id -> friendAdapter.setProcessing(id, false) }

        joinVm.requests.observe(viewLifecycleOwner) { list ->
            joinAdapter.submitList(list)
            updateEmptyVisibility()
            // ensure empty view updated immediately
            empty.visibility = if (friendAdapter.currentList.isEmpty() && joinAdapter.currentList.isEmpty()) View.VISIBLE else View.GONE
        }
        joinVm.loading.observe(viewLifecycleOwner) { loading ->
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            if (!loading) swipe.isRefreshing = false
        }
        joinVm.toast.observe(viewLifecycleOwner) { msg -> if (!msg.isNullOrBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
        joinVm.processingComplete.observe(viewLifecycleOwner) { id -> joinAdapter.setProcessing(id, false) }

        // load
        loadAll()

        // navigate on item clicks (friend adapter handles navigation inside its adapter if needed)
    }

    private fun updateEmptyVisibility() {
        val friendsEmpty = friendAdapter.currentList.isEmpty()
        val joinsEmpty = joinAdapter.currentList.isEmpty()
        val emptyView = view?.findViewById<TextView>(R.id.tv_empty)
        emptyView?.visibility = if (friendsEmpty && joinsEmpty) View.VISIBLE else View.GONE
    }

    private fun loadAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            friendVm.loadRequests()
            joinVm.loadRequests()
        }
    }
}
