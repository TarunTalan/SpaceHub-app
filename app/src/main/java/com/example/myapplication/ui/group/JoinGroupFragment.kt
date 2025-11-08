package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.ui.common.BaseFragment
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class JoinGroupFragment: BaseFragment(R.layout.fragment_join_group) {

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
            val (groupId, inviteCode) = parsed

            btn.isEnabled = false
            val prevText = btn.text
            // use a localizable string resource if available, fallback to literal
            val joiningText = try { getString(R.string.joining) } catch (_: Exception) { "Joining..." }
            btn.text = joiningText
            viewLifecycleOwner.lifecycleScope.launch {
                val result = LocalGroupRepository.getInstance(requireContext()).joinLocalGroupByLink(groupId, inviteCode)
                btn.isEnabled = true
                btn.text = prevText
                result.onSuccess {
                    Toast.makeText(requireContext(), "Joined group", Toast.LENGTH_SHORT).show()
                    val args = Bundle().apply { putString("id", groupId) }
                    navigateWithDelay(R.id.localGroupDetailFragment, args)
                }.onFailure { e ->
                    Toast.makeText(requireContext(), "Failed to join group: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseInvite(input: String): Pair<String, String>? {
        val s = input.trim()
        // 1) Try URI with query params: ?groupId=&inviteCode=
        runCatching {
            val uri = s.toUri()
            if (uri.scheme != null) {
                val cid = uri.getQueryParameter("groupId")
                    ?: uri.getQueryParameter("gid")
                    ?: uri.getQueryParameter("group")
                    ?: uri.getQueryParameter("id")
                val code = uri.getQueryParameter("inviteCode")
                    ?: uri.getQueryParameter("code")
                    ?: uri.getQueryParameter("token")
                if (!cid.isNullOrBlank() && !code.isNullOrBlank()) return Pair(cid, code)
                // Try path-based formats like /group/{gid}/invites/{code} or /invites/{gid}/{code}
                val segs = uri.pathSegments ?: emptyList()
                if (segs.isNotEmpty()) {
                    val idxGrp = segs.indexOfFirst { it.equals("group", true) }
                    val idxInv = segs.indexOfFirst { it.equals("invites", true) || it.equals("invite", true) }
                    if (idxGrp >= 0 && idxInv >= 0 && idxInv + 1 < segs.size && idxGrp + 1 < segs.size) {
                        val cid2 = segs.getOrNull(idxGrp + 1)
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
