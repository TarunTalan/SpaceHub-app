package com.example.myapplication.ui.community

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.community.adapter.RequestsAdapter
import com.example.myapplication.ui.community.viewmodel.RequestsInboxViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class RequestsInboxFragment : Fragment(R.layout.fragment_requests_inbox) {

    private val vm: RequestsInboxViewModel by viewModels()
    private lateinit var adapter: RequestsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ivBack = view.findViewById<View>(R.id.iv_back)
        val rvRequests = view.findViewById<RecyclerView>(R.id.rv_requests)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val emptyView = view.findViewById<View>(R.id.empty_view)
        val swipeRefresh = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)

        ivBack.setOnClickListener { findNavController().navigateUp() }

        adapter = RequestsAdapter(
            onAccept = { request ->
                adapter.setProcessing(request.id, true)
                vm.acceptRequest(request)
            },
            onReject = { request ->
                adapter.setProcessing(request.id, true)
                vm.rejectRequest(request)
            }
        )

        rvRequests.layoutManager = LinearLayoutManager(requireContext())
        rvRequests.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            vm.loadRequests()
        }

        // Observe data
        vm.requests.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            rvRequests.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            swipeRefresh.isRefreshing = false
        }

        vm.loading.observe(viewLifecycleOwner) { loading ->
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            if (!loading) swipeRefresh.isRefreshing = false
        }

        vm.toast.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                try {
                    if (msg.contains("accepted", ignoreCase = true) || msg.contains("accepted successfully", ignoreCase = true)) {
                        val nav = findNavController()
                        val entry = nav.getBackStackEntry(R.id.dashboardFragment)
                        entry.savedStateHandle["refresh_communities"] = true
                    }
                } catch (_: Exception) {}
            }
        }

        vm.processingComplete.observe(viewLifecycleOwner) { requestId ->
            adapter.setProcessing(requestId, false)
        }

        // Initial load
        vm.loadRequests()
    }

    override fun onResume() {
        super.onResume()
        // Auto-refresh when returning to inbox to show any new requests
        vm.loadRequests()
    }
}
