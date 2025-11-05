package com.example.myapplication.ui.dashboard

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.ui.common.BaseFragment
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class JoinCommunityFragment: BaseFragment(R.layout.fragment_join_community) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val back = view.findViewById<ImageView>(R.id.back_arrow)
        val et = view.findViewById<EditText>(R.id.etCommLink)
        val btn = view.findViewById<Button>(R.id.btn_create_comm)

        back?.setOnClickListener { findNavController().navigateUp() }

        btn?.setOnClickListener {
            val raw = et?.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                et?.error = "Invite link required"
                return@setOnClickListener
            }
            val parsed = parseInvite(raw)
            if (parsed == null) {
                et?.error = "Invalid invite link"
                return@setOnClickListener
            }
            val (communityId, inviteCode) = parsed

            btn.isEnabled = false
            val prevText = btn.text
            btn.text = "Joining..."
            viewLifecycleOwner.lifecycleScope.launch {
                val result = CommunityRepository.getInstance(requireContext()).joinCommunityByLink(communityId, inviteCode)
                btn.isEnabled = true
                btn.text = prevText
                result.onSuccess {
                    Toast.makeText(requireContext(), "Joined community", Toast.LENGTH_SHORT).show()
                    val args = Bundle().apply { putString("communityId", communityId) }
                    findNavController().navigate(R.id.communityDetailFragment, args)
                }.onFailure { e ->
                    Toast.makeText(requireContext(), "Failed to join: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseInvite(input: String): Pair<String, String>? {
        val s = input.trim()
        // 1) Try URI with query params: ?communityId=&inviteCode=
        runCatching {
            val uri = s.toUri()
            if (uri.scheme != null) {
                val cid = uri.getQueryParameter("communityId")
                    ?: uri.getQueryParameter("cid")
                    ?: uri.getQueryParameter("community")
                    ?: uri.getQueryParameter("id")
                val code = uri.getQueryParameter("inviteCode")
                    ?: uri.getQueryParameter("code")
                    ?: uri.getQueryParameter("token")
                if (!cid.isNullOrBlank() && !code.isNullOrBlank()) return Pair(cid, code)
                // Try path-based formats like /community/{cid}/invites/{code} or /invites/{cid}/{code}
                val segs = uri.pathSegments ?: emptyList()
                if (segs.isNotEmpty()) {
                    val idxComm = segs.indexOfFirst { it.equals("community", true) }
                    val idxInv = segs.indexOfFirst { it.equals("invites", true) || it.equals("invite", true) }
                    if (idxComm >= 0 && idxInv >= 0 && idxInv + 1 < segs.size && idxComm + 1 < segs.size) {
                        val cid2 = segs.getOrNull(idxComm + 1)
                        val code2 = segs.getOrNull(idxInv + 1)
                        if (!cid2.isNullOrBlank() && !code2.isNullOrBlank()) return Pair(cid2, code2)
                    }
                    if (idxInv >= 0 && idxInv + 2 < segs.size) {
                        val cid2 = segs.getOrNull(idxInv + 1)
                        val code2 = segs.getOrNull(idxInv + 2)
                        if (!cid2.isNullOrBlank() && !code2.isNullOrBlank()) return Pair(cid2, code2)
                    }
                    if (segs.size >= 2) {
                        val cid2 = segs[segs.size - 2]
                        val code2 = segs.last()
                        if (!cid2.isNullOrBlank() && !code2.isNullOrBlank()) return Pair(cid2, code2)
                    }
                }
            }
        }
        val delimiters = charArrayOf(':', ',', ';', '|', '/')
        delimiters.forEach { d ->
            val parts = s.split(d)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return Pair(parts[0], parts[1])
            }
        }
        return null
    }
}