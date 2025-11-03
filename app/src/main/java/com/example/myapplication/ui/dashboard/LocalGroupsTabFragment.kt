package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.rvLocalGroups)
        emptyView = view.findViewById(R.id.emptyView)
        loader = view.findViewById(R.id.progress_loader)
        adapter = CommunityListAdapter { item ->
            runCatching {
                findNavController().navigate(
                    R.id.action_searchFragment_to_localGroupDetailFragment,
                    Bundle().apply {
                        putInt("id", item.id)
                        putString("name", item.name)
                        putString("imageUrl", item.imageUrl)
                    }
                )
            }
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
    }

    fun setLoading(loading: Boolean) {
        loader?.visibility = if (loading) View.VISIBLE else View.GONE
        recycler.visibility = if (loading) View.GONE else View.VISIBLE
        if (loading) emptyView?.visibility = View.GONE
    }

    fun submitList(items: List<CommunityUi>) {
        setLoading(false)
        adapter.submitList(items)
        emptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
