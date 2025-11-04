package com.example.myapplication.ui.community

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.community.viewmodel.EditCommunityViewModel

class EditCommunityFragment : Fragment(R.layout.fragment_edit_community) {

    private val vm: EditCommunityViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val communityId = arguments?.getString("communityId")
        if (communityId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        val etName = view.findViewById<EditText>(R.id.etCommunityName)
        val etDesc = view.findViewById<EditText>(R.id.etCommunityDescription)
        val btnSave = view.findViewById<Button>(R.id.btnSave)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        btnSave.setOnClickListener {
            vm.update(communityId, etName.text?.toString().orEmpty(), etDesc.text?.toString().orEmpty())
        }

        vm.loading.observe(viewLifecycleOwner) { show -> progress.visibility = if (show) View.VISIBLE else View.GONE }
        vm.toast.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                if (msg.contains("updated", ignoreCase = true)) {
                    findNavController().popBackStack()
                }
            }
        }
    }
}

