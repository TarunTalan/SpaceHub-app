package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.dashboard.adapter.CommunityListAdapter
import com.example.myapplication.ui.dashboard.adapter.CommunityUi

class LocalGroupsTabFragment : BaseFragment(R.layout.fragment_tab_local_groups) {
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: CommunityListAdapter
    private var emptyView: TextView? = null
    private var loader: ProgressBar? = null
    private var emptyIllustration: ImageView? = null

    private var pendingList: List<CommunityUi>? = null

    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            updateEmptyState()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            updateEmptyState()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            updateEmptyState()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.rvLocalGroups)
        emptyView = view.findViewById(R.id.emptyView)
        loader = view.findViewById(R.id.progress_loader)
        emptyIllustration = view.findViewById(R.id.emptyIllustration)
        adapter = CommunityListAdapter({ item ->
            runCatching {
                findNavController().navigate(
                    R.id.action_searchFragment_to_localGroupDetailFragment,
                    Bundle().apply {
                        putString("communityId", item.communityId)
                        putString("name", item.name)
                        putString("imageUrl", item.imageUrl)
                    }
                )
            }
        }, { _ ->
            // default join action for local groups
        })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        adapter.registerAdapterDataObserver(adapterObserver)

        pendingList?.let {
            adapter.submitList(it)
            pendingList = null
        }

        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (!::recycler.isInitialized) return
        val isLoading = loader?.visibility == View.VISIBLE
        val isEmpty = adapter.itemCount == 0
        val showEmpty = !isLoading && isEmpty
        emptyView?.visibility = if (showEmpty) View.VISIBLE else View.GONE
        emptyIllustration?.visibility = if (showEmpty) View.VISIBLE else View.GONE
    }

    fun setLoading(loading: Boolean) {
        if (!::recycler.isInitialized) return
        loader?.visibility = if (loading) View.VISIBLE else View.GONE
        recycler.visibility = if (loading) View.GONE else View.VISIBLE
        if (loading) {
            emptyView?.visibility = View.GONE
            emptyIllustration?.visibility = View.GONE
        }
    }

    fun submitList(items: List<CommunityUi>) {
        if (!::recycler.isInitialized) {
            pendingList = items
            return
        }
        setLoading(false)
        adapter.submitList(items)
    }

    override fun onDestroyView() {
        if (::recycler.isInitialized) {
            recycler.adapter?.unregisterAdapterDataObserver(adapterObserver)
        }
        super.onDestroyView()
    }
}
