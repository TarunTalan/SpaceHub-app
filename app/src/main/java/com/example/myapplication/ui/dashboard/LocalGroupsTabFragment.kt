package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.dashboard.adapter.CommunityListAdapter
import com.example.myapplication.ui.dashboard.adapter.CommunityUi
import com.example.myapplication.ui.group.LocalGroupsViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LocalGroupsTabFragment : BaseFragment(R.layout.fragment_tab_local_groups) {
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: CommunityListAdapter
    private var emptyView: TextView? = null
    private var loader: ProgressBar? = null
    private var emptyIllustration: ImageView? = null

    private val vm: LocalGroupsViewModel by viewModels()

    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { updateEmptyState() }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateEmptyState() }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateEmptyState() }
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

        // Observe ViewModel flows
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.loading.collectLatest { isLoading -> setLoading(isLoading) }
                }
                launch {
                    vm.groups.collectLatest { list ->
                        val ui = list.map { g ->
                            val communityId = g.id
                            val idInt = try { communityId.toInt() } catch (_: Exception) { communityId.hashCode() }
                            val imageUrl = if (g.imageUrl is String) g.imageUrl as String else null
                            CommunityUi(
                                communityId = communityId,
                                id = idInt,
                                name = g.name,
                                imageUrl = imageUrl,
                                subtitle = "${g.totalMembers} members",
                                isLocal = true,
                                isRequested = false,
                                isOwner = false,
                                isAdmin = false,
                                isMember = true
                            )
                        }
                        adapter.submitList(ui)
                    }
                }
                // trigger load once when started
                vm.loadGroups()
            }
        }

        // Observe navigation savedState for local_group_created flag to refresh list
        try {
            val nav = findNavController()
            val entry = nav.getBackStackEntry(R.id.dashboardFragment)
            entry.savedStateHandle.getLiveData<Boolean>("local_group_created").observe(viewLifecycleOwner) { created ->
                if (created == true) {
                    vm.loadGroups()
                    // clear the flag
                    entry.savedStateHandle["local_group_created"] = false
                }
            }
        } catch (_: Exception) {}

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

    override fun onDestroyView() {
        if (::recycler.isInitialized) {
            recycler.adapter?.unregisterAdapterDataObserver(adapterObserver)
        }
        super.onDestroyView()
    }
}
