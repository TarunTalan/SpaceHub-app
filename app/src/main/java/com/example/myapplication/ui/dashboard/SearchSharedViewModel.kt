package com.example.myapplication.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.search.SearchRepository
import com.example.myapplication.ui.dashboard.adapter.CommunityUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchSharedViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SearchRepository.getInstance(app)

    private val _allCommunities = MutableStateFlow<List<CommunityUi>>(emptyList())
    val allCommunities: StateFlow<List<CommunityUi>> = _allCommunities.asStateFlow()

    private val _allLocalGroups = MutableStateFlow<List<CommunityUi>>(emptyList())
    val allLocalGroups: StateFlow<List<CommunityUi>> = _allLocalGroups.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    fun setCommunities(list: List<CommunityUi>) { _allCommunities.value = list }

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

    fun search(q: String, onResult: (List<CommunityUi>) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.searchCommunities(q)
            val list = res.getOrElse { emptyList() }
            _allCommunities.value = list
            onResult(list)
        }
    }
}
