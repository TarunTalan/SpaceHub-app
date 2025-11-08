package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.dashboard.adapter.CommunityListAdapter
import com.example.myapplication.ui.dashboard.adapter.CommunityUi
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.launch

class CommunityTabFragment : BaseFragment(R.layout.fragment_tab_community) {
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: CommunityListAdapter
    private var emptyView: TextView? = null
    private var emptyView2: TextView? = null
    private var loader: ProgressBar? = null
    private var emptyIllustration: ImageView? = null

    // Store a pending list if submitList is called before the recycler is initialized
    private var pendingList: List<CommunityUi>? = null

    // Observe adapter changes so we can update empty UI when submitList finishes (diff is async)
    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { updateEmptyState() }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateEmptyState() }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateEmptyState() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.rvCommunity)
        emptyView = view.findViewById(R.id.emptyView)
        emptyView2 = view.findViewById(R.id.emptyView2)
        loader = view.findViewById(R.id.progress_loader)
        emptyIllustration = view.findViewById(R.id.emptyIllustration)
        val repo = CommunityRepository.getInstance(requireContext())
        adapter = CommunityListAdapter({ item ->
            runCatching {
                val alreadyMember = item.isMember || item.isOwner || item.isAdmin
                if (alreadyMember) {
                    // Navigate to full community detail for members
                    navigateWithDelay(
                        R.id.action_searchFragment_to_communityDetailFragment,
                        Bundle().apply { putString("communityId", item.communityId) }
                    )
                } else {
                    // Navigate to overview for non-members
                    navigateWithDelay(
                        R.id.action_searchFragment_to_communityOverviewFragment,
                        Bundle().apply {
                            putString("communityId", item.communityId)
                            putString("name", item.name)
                            putString("imageUrl", item.imageUrl)
                            putString("description", item.subtitle ?: "")
                            putBoolean("isRequested", item.isRequested)
                        }
                    )
                }
            }
        }, { item ->
            // Join button -> call requestToJoin
            adapter.setLoading(item.id, true)
            lifecycleScope.launch {
                val res = repo.requestToJoinCommunity(item.name)
                if (res.isSuccess) {
                    adapter.setRequested(item.id, true)
                    Toast.makeText(requireContext(), getString(R.string.request_sent), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.request_failed), Toast.LENGTH_SHORT).show()
                }
                adapter.setLoading(item.id, false)
            }
        })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        // Add thin divider between items
        val divider = DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        ContextCompat.getDrawable(requireContext(), R.drawable.divider_thin)?.let { divider.setDrawable(it) }
        recycler.addItemDecoration(divider)

        // register observer after adapter is set
        adapter.registerAdapterDataObserver(adapterObserver)

        // If a list was submitted earlier, apply it now
        pendingList?.let {
            adapter.submitList(it)
            pendingList = null
            // updateEmptyState will run via adapterObserver when the diff completes
        }

        // also update immediately in case adapter already has items
        updateEmptyState()
    }

    private fun updateEmptyState() {
        // If recycler not initialized yet, nothing to do
        if (!::recycler.isInitialized) return
        val isLoading = loader?.visibility == View.VISIBLE
        val isEmpty = adapter.itemCount == 0
        val showEmpty = !isLoading && isEmpty
        // Toggle views
        emptyView?.visibility = if (showEmpty) View.VISIBLE else View.GONE
        emptyView2?.visibility = if (showEmpty) View.VISIBLE else View.GONE
        emptyIllustration?.visibility = if (showEmpty) View.VISIBLE else View.GONE
        recycler.visibility = if (showEmpty) View.GONE else View.VISIBLE
    }

    fun setLoading(loading: Boolean) {
        // Guard against calls before initialization
        if (!::recycler.isInitialized) return
        loader?.visibility = if (loading) View.VISIBLE else View.GONE
        recycler.visibility = if (loading) View.GONE else View.VISIBLE
        if (loading) {
            emptyView?.visibility = View.GONE
            emptyView2?.visibility = View.GONE
            emptyIllustration?.visibility = View.GONE
        }
    }

    fun submitList(items: List<CommunityUi>) {
        // If recycler not ready, save as pending and return
        if (!::recycler.isInitialized) {
            pendingList = items
            return
        }
        setLoading(false)
        adapter.submitList(items)
        // updateEmptyState() will be called by adapterObserver once the diff finishes
    }

    override fun onDestroyView() {
        if (::recycler.isInitialized) {
            recycler.adapter?.unregisterAdapterDataObserver(adapterObserver)
        }
        super.onDestroyView()
    }
}
