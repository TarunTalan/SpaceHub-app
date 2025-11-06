package com.example.myapplication.ui.friends

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.friends.adapter.UserSearchAdapter
import com.example.myapplication.ui.friends.viewmodel.SearchFriendsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFriendsFragment : Fragment(R.layout.fragment_search_friends) {

    private val viewModel: SearchFriendsViewModel by viewModels()
    private lateinit var adapter: UserSearchAdapter
    private var searchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etSearch = view.findViewById<EditText>(R.id.etSearchUsers)
        val ivClear = view.findViewById<ImageView>(R.id.ivClearSearch)
        val rvResults = view.findViewById<RecyclerView>(R.id.rvSearchResults)
        val progressLoading = view.findViewById<ProgressBar>(R.id.progressLoading)
        val emptyState = view.findViewById<View>(R.id.emptyState)
        val noResultsState = view.findViewById<View>(R.id.noResultsState)

        // Setup RecyclerView
        adapter = UserSearchAdapter { user ->
            viewModel.sendFriendRequest(user.email)
            adapter.setLoading(user.email, true)
        }

        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = adapter
        rvResults.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        // Setup search with debounce
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                ivClear.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE

                // Debounce search
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(500) // Wait 500ms before searching
                    viewModel.searchUsers(query)
                }
            }
        })

        // Clear search
        ivClear.setOnClickListener {
            etSearch.text.clear()
        }

        // Observe search results
        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            adapter.submitList(results)

            // Update UI states
            val hasQuery = etSearch.text.isNotBlank()
            val hasResults = results.isNotEmpty()

            rvResults.visibility = if (hasResults) View.VISIBLE else View.GONE
            emptyState.visibility = if (!hasQuery && !hasResults) View.VISIBLE else View.GONE
            noResultsState.visibility = if (hasQuery && !hasResults) View.VISIBLE else View.GONE
        }

        // Observe loading state
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        // Observe errors
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        // Observe friend request sent
        viewModel.requestSent.observe(viewLifecycleOwner) { email ->
            email?.let {
                adapter.setLoading(it, false)
                Toast.makeText(requireContext(), "Friend request sent!", Toast.LENGTH_SHORT).show()
                viewModel.clearRequestSent()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
    }
}

