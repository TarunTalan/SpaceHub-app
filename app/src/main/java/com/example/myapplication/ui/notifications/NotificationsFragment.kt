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
            // update per-section and combined empties
            updateEmptyVisibility()
        }
        friendVm.loading.observe(viewLifecycleOwner) { loading ->
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            if (!loading) swipe.isRefreshing = false
        }
        friendVm.toast.observe(viewLifecycleOwner) { msg -> if (!msg.isNullOrBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
        friendVm.processingComplete.observe(viewLifecycleOwner) { id -> friendAdapter.setProcessing(id, false) }

        joinVm.requests.observe(viewLifecycleOwner) { list ->
            joinAdapter.submitList(list)
            // update per-section and combined empties
            updateEmptyVisibility()
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
        val v = view ?: return
        val pkg = requireContext().packageName
        val idEf = v.resources.getIdentifier("tv_empty_friends", "id", pkg)
        val idEj = v.resources.getIdentifier("tv_empty_joins", "id", pkg)
        val idCombined = v.resources.getIdentifier("tv_empty", "id", pkg)
        val ef = if (idEf != 0) v.findViewById<TextView>(idEf) else null
        val ej = if (idEj != 0) v.findViewById<TextView>(idEj) else null
        val combined = if (idCombined != 0) v.findViewById<TextView>(idCombined) else null
        val friendsEmpty = friendAdapter.currentList.isEmpty()
        val joinsEmpty = joinAdapter.currentList.isEmpty()
        ef?.visibility = if (friendsEmpty) View.VISIBLE else View.GONE
        ej?.visibility = if (joinsEmpty) View.VISIBLE else View.GONE
        combined?.visibility = if (friendsEmpty && joinsEmpty) View.VISIBLE else View.GONE
    }

    private fun loadAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            friendVm.loadRequests()
            joinVm.loadRequests()
        }
    }
}
