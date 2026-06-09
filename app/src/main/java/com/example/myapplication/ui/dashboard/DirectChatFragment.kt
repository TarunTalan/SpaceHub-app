package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.chat.ChatMessagesAdapter
import com.example.myapplication.ui.chat.ChatViewModel
import com.example.myapplication.ui.common.ProfileImageHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import com.example.myapplication.data.network.NetworkModule
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DirectChatFragment: Fragment(R.layout.fragment_direct_chat) {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var messagesAdapter: ChatMessagesAdapter
    private var prevSoftInputMode: Int? = null
    private var prevImeBottom: Int = 0
    private var typingJob: Job? = null

    // Use an internal flag to manage selection UI instead of the system ActionMode
    private var selectionActive: Boolean = false

    // Helper to update selection toolbar title/count
    private fun updateSelectionTitle() {
        val count = messagesAdapter.getSelectedIds().size
        view?.findViewById<TextView>(R.id.selection_count)?.text = getString(R.string.selected_count, count)
    }

    private lateinit var filePickerLauncher: ActivityResultLauncher<String>

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back handler: if selection active, clear selection instead of navigating back
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionActive || messagesAdapter.getSelectedIds().isNotEmpty()) {
                    finishSelection()
                } else {
                    // allow normal back
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        val window = activity?.window
        if (window != null && prevSoftInputMode == null) {
            prevSoftInputMode = window.attributes.softInputMode
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val tvTitle = view.findViewById<TextView>(R.id.chatRoom)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_peer_avatar)
        val ivBack = view.findViewById<ImageView>(R.id.back_arrow)
        val chatBar = view.findViewById<View>(R.id.constraintLayoutChat)
        val rvMessages = view.findViewById<RecyclerView>(R.id.rv_messages)
        val etMessage = view.findViewById<EditText>(R.id.etChat)
        val ivSend = view.findViewById<ImageView>(R.id.iv_send)
        val rootLayout = view.findViewById<View>(R.id.root_layout)

        // NEW: selection toolbar views
        val selectionToolbar = view.findViewById<View>(R.id.selection_toolbar)
        val selectionClose = view.findViewById<View>(R.id.selection_iv_close)
        val selectionDelete = view.findViewById<View>(R.id.selection_iv_delete)

        // Setup RecyclerView for chat messages
        // Pass true to hide avatar and sender name in direct chat
        messagesAdapter = ChatMessagesAdapter(hidePeerInfoInDirectChat = true)
        rvMessages?.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true // Start from bottom like WhatsApp
        }
        rvMessages?.adapter = messagesAdapter

        // Wire adapter callbacks for selection and click
        messagesAdapter.onItemLongClick = { _ ->
            // toggle selection already handled in adapter; ensure our selection UI is active
            ensureSelectionMode()
            updateSelectionTitle()
            // show custom selection toolbar and hide default title
            selectionToolbar?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.GONE
        }
        messagesAdapter.onItemClick = { _ ->
            // If selection active, update selection count UI
            if (selectionActive) {
                updateSelectionTitle()
            }
        }

        // selection toolbar button handlers
        selectionClose?.setOnClickListener {
            // clear selection and restore title toolbar
            finishSelection()
        }
        selectionDelete?.setOnClickListener {
            val ids = messagesAdapter.getSelectedIds().toList()
            if (ids.isNotEmpty()) {
                chatViewModel.deleteMessages(ids)
                Toast.makeText(requireContext(), "Deleted ${ids.size} message(s)", Toast.LENGTH_SHORT).show()
            }
            finishSelection()
        }

        // Register file picker launcher
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                uploadAndSendWithRetry(uri)
            }
        }

        // Hook add button to picker
        view.findViewById<View>(R.id.iv_add)?.setOnClickListener {
            try {
                filePickerLauncher.launch("*/*")
            } catch (_: Exception) {
            }
        }

        // Use WindowInsets to move the chat bar up with keyboard and ensure the message list is padded
        val originalRootPaddingBottom = view.paddingBottom
        val originalRvBottom = rvMessages?.paddingBottom ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val imeBottom = imeInsets.bottom

            // Translate the chat bar up by the keyboard height (visual move)
            chatBar?.translationY = -imeBottom.toFloat()

            // Add bottom padding to the messages RecyclerView so its content is visible above the keyboard
            rvMessages?.setPadding(
                rvMessages.paddingLeft,
                rvMessages.paddingTop,
                rvMessages.paddingRight,
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
            if (this@DirectChatFragment.prevImeBottom == 0 && imeBottom > 0) {
                rvMessages?.post {
                    try {
                        val lastIndex = messagesAdapter.itemCount - 1
                        if (lastIndex >= 0) rvMessages.smoothScrollToPosition(lastIndex)
                    } catch (_: Exception) {
                    }
                }
            }

            this@DirectChatFragment.prevImeBottom = imeBottom

            insets
        }

        chatBar?.bringToFront()
        ivBack?.setOnClickListener { runCatching { findNavController().navigateUp() } }

        val args = arguments
        // Sanitize peerName: server or prior screens sometimes pass literal "null" or "null null" strings.
        fun sanitizeName(raw: String?): String {
            val s = raw?.trim().orEmpty()
            if (s.isBlank()) return ""
            // If the name consists only of the token "null" (any count), treat as blank
            val tokens = s.split(Regex("\\s+"))
            if (tokens.isNotEmpty() && tokens.all { it.equals("null", ignoreCase = true) }) return ""
            return s
        }

        val rawPeerName = args?.getString("peerName")
        var peerName = sanitizeName(rawPeerName)
        val peerEmail = args?.getString("peerEmail") ?: ""
        if (peerName.isBlank()) peerName = if (peerEmail.isNotBlank()) peerEmail else "Unknown user"

        val peerAvatar = args?.getString("peerAvatarUrl")

        tvTitle?.text = peerName
        ProfileImageHelper.loadProfileImageIntoView(requireContext(), ivAvatar, peerAvatar)

        // Load conversation and messages. If a server-provided history payload was passed via
        // navigation arguments under key "historyJson" (stringified JSON array), persist it first
        // and then load the conversation so UI observes DB.
        if (peerEmail.isNotBlank()) {
            val historyJson = args?.getString("historyJson")
            if (!historyJson.isNullOrBlank()) {
                try {
                    val gson = com.google.gson.Gson()
                    val type = object : com.google.gson.reflect.TypeToken<List<com.example.myapplication.data.chat.websocket.DirectChatMessage>>() {}.type
                    val historyList: List<com.example.myapplication.data.chat.websocket.DirectChatMessage> = gson.fromJson(historyJson, type)
                    chatViewModel.loadConversationWithHistory(peerEmail, peerName, peerAvatar, historyList)
                } catch (e: Exception) {
                    // If parsing fails, fallback to normal load
                    chatViewModel.loadConversation(peerEmail, peerName, peerAvatar)
                }
            } else {
                chatViewModel.loadConversation(peerEmail, peerName, peerAvatar)
            }
        }

        // Observe connection state
        chatViewModel.connectionState.observe(viewLifecycleOwner) { _ ->
            // connection state observed by UI if needed; kept to allow future handling
        }

        // Observe messages - auto-scroll to bottom on new message
        chatViewModel.messages.observe(viewLifecycleOwner) { messages ->
            messagesAdapter.submitList(messages) {
                rvMessages?.post {
                    try {
                        val lastIndex = messagesAdapter.itemCount - 1
                        if (lastIndex >= 0) rvMessages.smoothScrollToPosition(lastIndex)
                    } catch (_: Exception) {
                    }
                }
            }
            // If after update there are no selected ids, ensure action mode closed and restore title
            if (messagesAdapter.getSelectedIds().isEmpty()) {
                finishSelection()
            }
        }

        // Send message on send button click
        ivSend?.setOnClickListener {
            val text = etMessage?.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                chatViewModel.sendMessage(text)
                etMessage.text?.clear()
            }
        }

        // Typing indicator (debounced)
        etMessage?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                typingJob?.cancel()
                if (!s.isNullOrBlank()) {
                    typingJob = lifecycleScope.launch {
                        delay(500)
                        chatViewModel.sendTypingIndicator()
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Keep keyboard open when tapping the background: restore focus to the input and show IME.
        try {
            rootLayout?.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        try {
                            etMessage?.requestFocus()
                            val imm =
                                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            // Show keyboard for the EditText; use SHOW_IMPLICIT to avoid toggling
                            imm.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT)
                        } catch (_: Exception) {
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        try {
                            v?.performClick()
                        } catch (_: Exception) {
                        }
                    }
                }
                // Return false so child views (buttons etc.) still receive touch events
                false
            }
        } catch (_: Exception) {
        }

        // Mark messages as read when visible
        chatViewModel.markAsRead()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        chatViewModel.markAsRead()
    }

    @Suppress("DEPRECATION")
    override fun onDestroyView() {
        super.onDestroyView()
        typingJob?.cancel()
        val window = activity?.window
        if (window != null && prevSoftInputMode != null) {
            window.setSoftInputMode(prevSoftInputMode!!)
            prevSoftInputMode = null
        }
    }

    // Start selection mode using the custom selection toolbar (no system ActionMode)
    private fun ensureSelectionMode() {
        if (selectionActive) return
        selectionActive = true
        view?.findViewById<View>(R.id.selection_toolbar)?.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.GONE
        updateSelectionTitle()
    }

    private fun finishSelection() {
        messagesAdapter.clearSelection()
        selectionActive = false
        view?.findViewById<View>(R.id.selection_toolbar)?.visibility = View.GONE
        view?.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.VISIBLE
        updateSelectionTitle()
    }

    private var uploadJob: Job? = null
    private val MAX_FILE_BYTES = 100L * 1024L * 1024L // 100 MB
    private val allowedMimePrefixes = listOf("image/", "video/", "audio/", "text/")
    private val allowedExtensions = listOf("pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx")

    private suspend fun askRetryDialog(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val dlg = AlertDialog.Builder(requireContext())
                .setTitle("Upload failed")
                .setMessage("File upload failed. Retry?")
                .setPositiveButton("Retry") { d, _ ->
                    cont.resume(true)
                    try {
                        d.dismiss()
                    } catch (_: Exception) {
                    }
                }
                .setNegativeButton("Cancel") { d, _ ->
                    cont.resume(false)
                    try {
                        d.dismiss()
                    } catch (_: Exception) {
                    }
                }
                .setOnCancelListener { cont.resume(false) }
                .create()
            dlg.show()
            cont.invokeOnCancellation {
                try {
                    dlg.dismiss()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    private fun showProgressCancelable(onCancel: () -> Unit): AlertDialog {
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Uploading")
            .setMessage("Uploading file...")
            .setNegativeButton("Cancel") { d, _ ->
                onCancel(); try {
                d.dismiss()
            } catch (_: Exception) {
            }
            }
            .setCancelable(false)
            .create()
        dlg.show()
        return dlg
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            val afd = requireContext().contentResolver.openFileDescriptor(uri, "r")
            afd?.use { it.statSize } ?: -1L
        } catch (_: Exception) {
            -1L
        }
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
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun uploadAndSendWithRetry(uri: Uri) {
        // Size/type checks
        val size = getFileSize(uri)
        if (size > 0 && size > MAX_FILE_BYTES) {
            Toast.makeText(requireContext(), "File too large (>100 MB)", Toast.LENGTH_LONG).show()
            return
        }
        if (!isMimeAllowed(uri)) {
            Toast.makeText(requireContext(), "Unsupported file type", Toast.LENGTH_LONG).show()
            return
        }

        while (true) {
            val progressDlg = showProgressCancelable {
                uploadJob?.cancel()
            }
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
                    // Build FILE payload using fileKey, fileName and contentType from upload response
                    val payload = mapOf(
                        "type" to "FILE",
                        "fileKey" to fileKey,
                        "fileName" to (fileNameResp ?: fileKey),
                        "fileUrl" to fileUrl,
                        "contentType" to (contentTypeResp ?: "application/octet-stream")
                    )
                    val jsonPayload = com.google.gson.Gson().toJson(payload)
                    withContext(Dispatchers.Main) { chatViewModel.sendMessage(jsonPayload) }
                }
                uploadJob?.join()
                success = true
            } catch (_: java.util.concurrent.CancellationException) {
                // cancelled by user
                Toast.makeText(requireContext(), "Upload cancelled", Toast.LENGTH_SHORT).show()
                success = false
            } catch (t: Throwable) {
                android.util.Log.w("DirectChatFragment", "upload failed: ${t.message}")
                success = false
            } finally {
                try {
                    progressDlg.dismiss()
                } catch (_: Exception) {
                }
                uploadJob = null
            }

            if (success) return
            // ask retry
            val retry = askRetryDialog()
            if (!retry) return
            // else loop to retry
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
        } catch (e: Exception) {
            null
        }
    }
}
