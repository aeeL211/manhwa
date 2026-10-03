package com.shinigami.client.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.shinigami.client.databinding.FragmentContextMenuBinding
import com.shinigami.client.extension.WebExtension
import com.shinigami.client.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class ContextMenuSheet : BottomSheetDialogFragment() {

    private var targetUrl: String? = null
    private var _binding: FragmentContextMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = arguments?.getString(ARG_URL)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContextMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.txtUrl.text = targetUrl

        setupClickListeners()

        targetUrl?.let {
            loadImagePreview(it)
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.behavior?.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    targetUrl?.let {
                        (activity as? PopupHost)?.openPopupWebView(it)
                        dismiss()
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // Not needed
            }
        })
    }

    private fun setupClickListeners() {
        binding.btnOpen.setOnClickListener {
            executeSafeAction {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                dismiss()
            }
        }

        binding.btnCopy.setOnClickListener {
            executeSafeAction {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", targetUrl))
                showToast("Tautan disalin")
                dismiss()
            }
        }

        binding.btnDownload.setOnClickListener {
            executeSafeAction {
                val request = DownloadManager.Request(Uri.parse(targetUrl))
                    .setTitle("Mengunduh Gambar")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_PICTURES,
                        "Shinigami/IMG_${System.currentTimeMillis()}.jpg"
                    )

                val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                showToast("Proses unduh dimulai...")
                dismiss()
            }
        }

        binding.btnShare.setOnClickListener {
            executeSafeAction {
                val shareIntent = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, targetUrl)
                }, "Bagikan tautan")
                startActivity(shareIntent)
                dismiss()
            }
        }
    }

    private fun loadImagePreview(imageUrl: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cookie = CookieManager.getInstance().getCookie(imageUrl)
                val requestBuilder = Request.Builder().url(imageUrl)
                cookie?.let { requestBuilder.header("Cookie", it) }

                val request = requestBuilder.build()

                WebExtension.sharedHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bitmap = response.body?.byteStream()?.buffered()?.let {
                            BitmapFactory.decodeStream(it)
                        }

                        withContext(Dispatchers.Main) {
                            if (_binding != null && bitmap != null) {
                                binding.imgPreview.setImageBitmap(bitmap)
                                binding.imgPreview.scaleType = ImageView.ScaleType.CENTER_CROP
                                binding.imgPreview.imageTintList = null
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Image preview load failed", e)
            }
        }
    }

    private fun executeSafeAction(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Logger.e(TAG, "Action execution failed", e)
            showToast("Gagal memproses aksi tersebut")
        }
    }

    private fun showToast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ContextMenuSheet"
        private const val ARG_URL = "arg_target_url"

        fun newInstance(url: String): ContextMenuSheet {
            return ContextMenuSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
        }
    }
}