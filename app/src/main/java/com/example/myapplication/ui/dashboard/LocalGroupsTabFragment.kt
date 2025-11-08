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
                val nav = findNavController()
                // Always navigate directly to the destination id to avoid 'action not found' when current destination differs
                // The nav graph defines localGroupDetailFragment as a top-level destination; use its id directly.
                val args = Bundle().apply {
                    putString("communityId", item.communityId)
                    putString("name", item.name)
                    putString("imageUrl", item.imageUrl)
                }

                nav.navigate(R.id.localGroupDetailFragment, args)
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
                            val imageUrl = g.imageUrl as? String
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

            // Also observe the more detailed created item bundle so this tab can prepend the new group without a full reload
            entry.savedStateHandle.getLiveData<Bundle>("local_group_created_item").observe(viewLifecycleOwner) { bundle ->
                if (bundle != null) {
                    try {
                        val id = bundle.getString("id") ?: return@observe
                        val already = adapter.currentList.any { it.communityId == id }
                        if (already) {
                            entry.savedStateHandle.remove<Bundle>("local_group_created_item")
                            return@observe
                        }
                        val name = bundle.getString("name") ?: "Unnamed"
                        val imageUrl = bundle.getString("imageUrl") ?: bundle.getString("previewUri")
                        val totalMembers = bundle.getInt("totalMembers", 0)
                        val idInt = try { id.toInt() } catch (_: Exception) { id.hashCode() }
                        val newUi = CommunityUi(
                            communityId = id,
                            id = idInt,
                            name = name,
                            imageUrl = imageUrl,
                            subtitle = "${totalMembers} members",
                            isLocal = true,
                            isRequested = false,
                            isOwner = true,
                            isAdmin = false,
                            isMember = true
                        )
                        val updated = listOf(newUi) + adapter.currentList
                        adapter.submitList(updated)
                        try { recycler.scrollToPosition(0) } catch (_: Exception) {}
                        entry.savedStateHandle.remove<Bundle>("local_group_created_item")
                        emptyView?.visibility = View.GONE
                        emptyIllustration?.visibility = View.GONE
                    } catch (_: Exception) {}
                }
            }

            // Also support the simple refresh flag set by Group creation flow
            entry.savedStateHandle.getLiveData<Boolean>("refresh_local_groups").observe(viewLifecycleOwner) { shouldRefresh ->
                if (shouldRefresh == true) {
                    vm.loadGroups()
                    entry.savedStateHandle["refresh_local_groups"] = false
                }
            }

            // Observe deletion events from GroupDetailFragment and remove the item immediately
            entry.savedStateHandle.getLiveData<String>("local_group_deleted_id").observe(viewLifecycleOwner) { deletedId ->
                if (!deletedId.isNullOrBlank()) {
                    try {
                        val updated = adapter.currentList.filterNot { it.communityId == deletedId }
                        adapter.submitList(updated)
                        try { recycler.scrollToPosition(0) } catch (_: Exception) {}
                        emptyView?.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                        emptyIllustration?.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                    } catch (_: Exception) {}
                    entry.savedStateHandle.remove<String>("local_group_deleted_id")
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
