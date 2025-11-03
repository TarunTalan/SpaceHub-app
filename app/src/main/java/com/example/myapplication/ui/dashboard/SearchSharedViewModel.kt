package com.example.myapplication.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.myapplication.ui.dashboard.adapter.CommunityUi

class SearchSharedViewModel : ViewModel() {
    private val _allCommunities = MutableStateFlow<List<CommunityUi>>(emptyList())
    val allCommunities: StateFlow<List<CommunityUi>> = _allCommunities.asStateFlow()

    private val _allLocalGroups = MutableStateFlow<List<CommunityUi>>(emptyList())
    val allLocalGroups: StateFlow<List<CommunityUi>> = _allLocalGroups.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setSource(communities: List<CommunityUi>, locals: List<CommunityUi>) {
        _allCommunities.value = communities
        _allLocalGroups.value = locals
    }

    fun setQuery(q: String) { _query.value = q }

    fun filterCommunities(): List<CommunityUi> {
        val q = _query.value.trim().lowercase()
        if (q.isEmpty()) return _allCommunities.value
        return _allCommunities.value.filter { it.name.lowercase().contains(q) }
    }

    fun filterLocalGroups(): List<CommunityUi> {
        val q = _query.value.trim().lowercase()
        if (q.isEmpty()) return _allLocalGroups.value
        return _allLocalGroups.value.filter { it.name.lowercase().contains(q) }
    }
}

