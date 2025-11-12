package com.example.myapplication.ui.dashboard

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.ui.chat.ChatMessagesAdapter
import com.example.myapplication.ui.chat.ChatRoomViewModel
import com.example.myapplication.ui.common.BaseFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class ChatRoomFragment: BaseFragment(R.layout.fragment_chat_room) {
    private val vm: ChatRoomViewModel by viewModels()
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var prevSoftInputMode: Int? = null
    private var prevImeBottom: Int = 0
    private var scrolledOnImeOpen: Boolean = false
    private lateinit var filePickerLauncher: ActivityResultLauncher<String>

    // Selection UI flag
    private var selectionActive: Boolean = false

    // Helper: update selection count text
    private fun updateSelectionTitle(adapter: ChatMessagesAdapter) {
        val count = adapter.getSelectedIds().size
        view?.findViewById<TextView>(R.id.selection_count)?.text = getString(R.string.selected_count, count)
    }

    private fun ensureSelectionMode() {
        selectionActive = true
    }

    private fun finishSelection(adapter: ChatMessagesAdapter) {
        selectionActive = false
        adapter.clearSelection()
        // hide selection toolbar, show normal title toolbar
        view?.findViewById<View>(R.id.selection_toolbar)?.visibility = View.GONE
        view?.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read nav args and show room details in header
        val roomCode = arguments?.getString("chatRoomCode")
        val roomName = arguments?.getString("chatRoomName")
        val communityImageUrl = arguments?.getString("communityImageUrl")
        val titleView = view.findViewById<TextView>(R.id.chatRoom)
        if (!roomName.isNullOrBlank()) {
            titleView?.text = roomName
        } else if (!roomCode.isNullOrBlank()) {
            titleView?.text = roomCode
        }

        // Load community / group image into header if provided
        try {
            val imgView = view.findViewById<ImageView>(R.id.chat_community_image)
            imgView?.let { iv ->
                val raw = communityImageUrl?.trim()
                val avatarUrl = raw?.takeIf { it.isNotBlank() }?.let { r ->
                    when {
                        r.startsWith("http://", true) || r.startsWith("https://", true) -> r
                        else -> "${'$'}{BuildConfig.BASE_URL.trimEnd('/')}/${'$'}{r.trimStart('/') }"
                    }
                }

                Glide.with(iv.context)
                    .load(avatarUrl ?: R.drawable.default_profile)
                    .placeholder(R.drawable.default_profile)
                    .error(R.drawable.default_profile)
                    .circleCrop()
                    .into(iv)
            }
        } catch (_: Exception) {}

        // Back arrow behavior: if selection active clear selection, else navigate up
        val backArrow = view.findViewById<ImageView>(R.id.back_arrow)
        backArrow?.setOnClickListener {
            if (selectionActive) {
                // if adapter exists, clear selection
                val rv = view.findViewById<RecyclerView>(R.id.rv_messages)
                val adapter = rv?.adapter as? ChatMessagesAdapter
                if (adapter != null) finishSelection(adapter)
            } else {
                try { vm.disconnect(); findNavController().navigateUp() } catch (_: Exception) {}
            }
        }

        // Also intercept system back to clear selection (mirror DirectChatFragment behavior)
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val rv = view.findViewById<RecyclerView>(R.id.rv_messages)
                val adapter = rv?.adapter as? ChatMessagesAdapter
                if (selectionActive || (adapter?.getSelectedIds()?.isNotEmpty() == true)) {
                    if (adapter != null) finishSelection(adapter)
                } else {
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        // Messages list (use RecyclerView from layout)
        val messagesRv = view.findViewById<RecyclerView>(R.id.rv_messages)!!
        val adapter = ChatMessagesAdapter()
        messagesRv.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        messagesRv.adapter = adapter

        // NEW: selection toolbar views
        val selectionToolbar = view.findViewById<View>(R.id.selection_toolbar)
        val selectionClose = view.findViewById<View>(R.id.selection_iv_close)
        val selectionDelete = view.findViewById<View>(R.id.selection_iv_delete)

        // Wire adapter callbacks for selection and click
        adapter.onItemLongClick = { _ ->
            ensureSelectionMode()
            updateSelectionTitle(adapter)
            // show selection toolbar and hide title toolbar
            selectionToolbar?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.GONE
        }
        adapter.onItemClick = { _ ->
            if (selectionActive) {
                updateSelectionTitle(adapter)
            }
        }

        // selection toolbar handlers
        selectionClose?.setOnClickListener {
            finishSelection(adapter)
        }
        selectionDelete?.setOnClickListener {
            val ids = adapter.getSelectedIds().toList()
            if (ids.isNotEmpty()) {
                vm.deleteMessages(ids)
                android.widget.Toast.makeText(requireContext(), "Deleted ${'$'}{ids.size} message(s)", android.widget.Toast.LENGTH_SHORT).show()
            }
            finishSelection(adapter)
        }

        // Ensure the window resizes when IME (keyboard) appears so RecyclerView is visible
        try {
            val window = activity?.window
            if (window != null && prevSoftInputMode == null) {
                prevSoftInputMode = window.attributes.softInputMode
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        } catch (_: Exception) {}

        // Use WindowInsets to react to IME reliably (same as DirectChatFragment)
        try {
            val root = view.findViewById<View>(R.id.root_layout)
            val chatBar = view.findViewById<View>(R.id.constraintLayoutChat)
            val originalRootPaddingBottom = root?.paddingBottom ?: 0
            val originalRvBottom = messagesRv.paddingBottom

            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                val imeBottom = imeInsets.bottom

                // Translate the chat bar up by IME height (visual move)
                chatBar?.translationY = -imeBottom.toFloat()

                // Add bottom padding to the messages RecyclerView so its content is visible above the keyboard
                messagesRv.setPadding(
                    messagesRv.paddingLeft,
                    messagesRv.paddingTop,
                    messagesRv.paddingRight,
                    originalRvBottom + imeBottom
                )

                // Keep root padding for system bars when IME is not shown, otherwise remove extra bottom padding
                v.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    if (imeBottom > 0) 0 else systemBarsInsets.bottom + originalRootPaddingBottom
                )

                // If IME just opened (transition from 0 to >0), scroll RecyclerView to last message
                if (this@ChatRoomFragment.prevImeBottom == 0 && imeBottom > 0) {
                    messagesRv.post {
                        try {
                            safeScrollToBottom(messagesRv)
                            scrolledOnImeOpen = true
                            // clear the flag shortly after to allow subsequent normal scrolling
                            messagesRv.postDelayed({ scrolledOnImeOpen = false }, 300)
                        } catch (_: Exception) {}
                    }
                }

                this@ChatRoomFragment.prevImeBottom = imeBottom

                insets
            }

            chatBar?.bringToFront()
            ViewCompat.requestApplyInsets(root)
        } catch (_: Exception) {}

        // Connect to websocket
        val roomCodeArg = arguments?.getString("chatRoomCode")
        if (!roomCodeArg.isNullOrBlank()) {
            vm.connectToRoom(roomCodeArg)
        }

        // Collect messages and submit to adapter
        viewLifecycleOwner.lifecycleScope.launch {
            vm.messages.collect { list ->
                try { android.util.Log.d("ChatRoomFragment", "ADAPTER RECEIVING listSize=${'$'}{list.size} last=${'$'}{list.lastOrNull()}") } catch (_: Exception) {}
                adapter.submitList(list) {
                    messagesRv.post {
                        try {
                            if (!scrolledOnImeOpen) {
                                safeScrollToBottom(messagesRv)
                            }
                        } catch (_: Exception) {}
                    }
                }
                // If after update there are no selected ids, ensure selection toolbar closed
                if (adapter.getSelectedIds().isEmpty()) {
                    finishSelection(adapter)
                }
            }
        }

        // Send button and input
        val sendBtn = view.findViewById<ImageView>(R.id.iv_send)
        val input = view.findViewById<EditText>(R.id.etChat)
        sendBtn?.setOnClickListener {
            val text = input?.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && !roomCodeArg.isNullOrBlank()) {
                vm.sendMessage(text, roomCodeArg)
                input?.setText("")
                // let adapter/appended optimistic update take effect, then smoothly scroll to bottom (unless IME just triggered a scroll)
                try { messagesRv.post {
                    if (!scrolledOnImeOpen) safeScrollToBottom(messagesRv)
                 } } catch (_: Exception) {}
              }
          }

        // Register file picker for chat room
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                uploadAndSendWithRetry(uri)
            }
        }

        // Hook add button
        view.findViewById<View>(R.id.iv_add)?.setOnClickListener {
            try { filePickerLauncher.launch("*/*") } catch (_: Exception) {}
        }
    }

    private fun createFilePartFromUri(uri: Uri): MultipartBody.Part? {
        return try {
            val cr = requireContext().contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val fileName = uri.lastPathSegment ?: "upload"

            // Copy to cache
            val tmp = File.createTempFile("upload", null, requireContext().cacheDir)
            tmp.deleteOnExit()
            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }

            val req = tmp.asRequestBody(mime.toMediaTypeOrNull())
            MultipartBody.Part.createFormData("file", fileName, req)
        } catch (_: Exception) {
            null
        }
    }

    // Upload helpers: size/type check, cancellable progress dialog, retry dialog
    private var uploadJob: Job? = null
    private val MAX_FILE_BYTES = 100L * 1024L * 1024L // 100 MB
    private val allowedMimePrefixes = listOf("image/", "video/", "audio/", "text/")
    private val allowedExtensions = listOf("pdf","zip","doc","docx","xls","xlsx","ppt","pptx")

    private suspend fun askRetryDialog(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val dlg = AlertDialog.Builder(requireContext())
                .setTitle("Upload failed")
                .setMessage("File upload failed. Retry?")
                .setPositiveButton("Retry") { d, _ ->
                    cont.resume(true)
                    try { d.dismiss() } catch (_: Exception) {}
                }
                .setNegativeButton("Cancel") { d, _ ->
                    cont.resume(false)
                    try { d.dismiss() } catch (_: Exception) {}
                }
                .setOnCancelListener { cont.resume(false) }
                .create()
            dlg.show()
            cont.invokeOnCancellation { try { dlg.dismiss() } catch (_: Exception) {} }
        } catch (e: Exception) { cont.resumeWithException(e) }
    }

    private fun showProgressCancelable(onCancel: () -> Unit): AlertDialog {
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Uploading")
            .setMessage("Uploading file...")
            .setNegativeButton("Cancel") { d, _ -> onCancel(); try { d.dismiss() } catch (_: Exception) {} }
            .setCancelable(false)
            .create()
        dlg.show()
        return dlg
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            val afd = requireContext().contentResolver.openFileDescriptor(uri, "r")
            afd?.use { it.statSize } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun isMimeAllowed(uri: Uri): Boolean {
        return try {
            val cr = requireContext().contentResolver
            val mime = cr.getType(uri) ?: ""
            if (mime.isBlank()) return false
            allowedMimePrefixes.any { mime.startsWith(it) } || run {
                val path = uri.lastPathSegment ?: ""
                val ext = path.substringAfterLast('.', "").lowercase()
                allowedExtensions.contains(ext)
            }
        } catch (_: Exception) { false }
    }

    private suspend fun uploadAndSendWithRetry(uri: Uri) {
        // Size/type checks
        val size = getFileSize(uri)
        if (size > 0 && size > MAX_FILE_BYTES) {
            android.widget.Toast.makeText(requireContext(), "File too large (>100 MB)", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!isMimeAllowed(uri)) {
            android.widget.Toast.makeText(requireContext(), "Unsupported file type", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        while (true) {
            val progressDlg = showProgressCancelable { uploadJob?.cancel() }
            var success: Boolean
            try {
                uploadJob = lifecycleScope.launch {
                    val api = NetworkModule.createApiService(requireContext())
                    val part = createFilePartFromUri(uri)
                    if (part == null) throw java.lang.Exception("Failed to prepare file")
                    val resp = withContext(Dispatchers.IO) { api.uploadFileAndGetUrl(part) }
                    if (!resp.isSuccessful) throw java.lang.Exception("Upload failed: ${resp.code()}")
                    val body = resp.body()
                    val data = body?.data
                    val fileUrl = data?.fileUrl
                    val fileKey = data?.fileKey
                    val fileNameResp = data?.fileName
                    val contentTypeResp = data?.contentType
                    if (fileUrl.isNullOrBlank() || fileKey.isNullOrBlank()) throw java.lang.Exception("Invalid upload response")
                    // Build FILE payload and send to room
                    val payload = mapOf(
                        "type" to "FILE",
                        "fileKey" to fileKey,
                        "fileName" to (fileNameResp ?: fileKey),
                        "fileUrl" to fileUrl,
                        "contentType" to (contentTypeResp ?: "application/octet-stream")
                    )
                    val jsonPayload = com.google.gson.Gson().toJson(payload)
                    val roomCodeArg = arguments?.getString("chatRoomCode")
                    if (!roomCodeArg.isNullOrBlank()) withContext(Dispatchers.Main) { vm.sendMessage(jsonPayload, roomCodeArg) }
                }
                uploadJob?.join()
                success = true
            } catch (_: java.util.concurrent.CancellationException) {
                android.widget.Toast.makeText(requireContext(), "Upload cancelled", android.widget.Toast.LENGTH_SHORT).show()
                success = false
            } catch (t: Throwable) {
                android.util.Log.w("ChatRoomFragment", "upload failed: ${t.message}")
                success = false
            } finally {
                try { progressDlg.dismiss() } catch (_: Exception) {}
                uploadJob = null
            }

            if (success) return
            val retry = askRetryDialog()
            if (!retry) return
            // else loop to retry
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { vm.disconnect() } catch (_: Exception) {}
        // Remove keyboard listener to avoid leaks
        try {
            val root = requireView().findViewById<View>(R.id.root_layout)
            keyboardListener?.let { l ->
                try {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(l)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        // restore window softInputMode
        try {
            val window = activity?.window
            if (window != null && prevSoftInputMode != null) {
                window.setSoftInputMode(prevSoftInputMode!!)
                prevSoftInputMode = null
            }
        } catch (_: Exception) {}
    }

    // Helper: safely scroll to the last adapter position if valid. Guards against invalid target positions.
    private fun safeScrollToBottom(rv: RecyclerView) {
        try {
            val count = rv.adapter?.itemCount ?: 0
            val target = count - 1
            if (target >= 0 && target < count) {
                // Only call smoothScroll if the RecyclerView is attached to window
                if (rv.isAttachedToWindow && isAdded) rv.smoothScrollToPosition(target)
            }
        } catch (_: Exception) {}
    }
}