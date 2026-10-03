package com.shinigami.client.core.ui.components

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinigami.client.R
import com.shinigami.client.core.ui.theme.Hint
import com.shinigami.client.core.ui.theme.ImgPlaceholderTint
import com.shinigami.client.core.ui.theme.OnSurface
import com.shinigami.client.core.ui.theme.OnSurfaceVariant
import com.shinigami.client.core.ui.theme.SurfaceDark
import com.shinigami.client.core.util.Logger
import com.shinigami.client.core.webview.WebExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuBottomSheet(
    url: String,
    onDismissRequest: () -> Unit,
    onOpenInPopup: (String) -> Unit,
) {
    val context = LocalContext.current
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val cookie = CookieManager.getInstance().getCookie(url)
                val requestBuilder = Request.Builder().url(url)
                cookie?.let { requestBuilder.header("Cookie", it) }
                val request = requestBuilder.build()

                WebExtension.sharedHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bitmap = response.body?.byteStream()?.buffered()?.let {
                            BitmapFactory.decodeStream(it)
                        }
                        withContext(Dispatchers.Main) {
                            previewBitmap = bitmap
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("ContextMenuSheet", "Image preview load failed", e)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenInPopup(url)
                        onDismissRequest()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_photo_m3),
                            contentDescription = null,
                            tint = ImgPlaceholderTint,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = url,
                    color = Hint,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ContextMenuItem(
                iconRes = R.drawable.ic_open_m3,
                title = "Buka di browser",
                onClick = {
                    executeSafeAction(context) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    onDismissRequest()
                },
            )

            ContextMenuItem(
                iconRes = R.drawable.ic_copy_m3,
                title = "Salin tautan gambar",
                onClick = {
                    executeSafeAction(context) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                        Toast.makeText(context, "Tautan disalin", Toast.LENGTH_SHORT).show()
                    }
                    onDismissRequest()
                },
            )

            ContextMenuItem(
                iconRes = R.drawable.ic_download_m3,
                title = "Unduh gambar",
                onClick = {
                    executeSafeAction(context) {
                        val request = DownloadManager.Request(Uri.parse(url))
                            .setTitle("Mengunduh Gambar")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_PICTURES,
                                "Shinigami/IMG_${System.currentTimeMillis()}.jpg",
                            )

                        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        downloadManager.enqueue(request)
                        Toast.makeText(context, "Proses unduh dimulai...", Toast.LENGTH_SHORT).show()
                    }
                    onDismissRequest()
                },
            )

            ContextMenuItem(
                iconRes = R.drawable.ic_share_m3,
                title = "Bagikan tautan gambar",
                onClick = {
                    executeSafeAction(context) {
                        val shareIntent = Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            },
                            "Bagikan tautan",
                        )
                        context.startActivity(shareIntent)
                    }
                    onDismissRequest()
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ContextMenuItem(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = OnSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = OnSurface,
            fontSize = 14.sp,
        )
    }
}

private fun executeSafeAction(context: Context, action: () -> Unit) {
    try {
        action()
    } catch (e: Exception) {
        Logger.e("ContextMenuSheet", "Action execution failed", e)
        Toast.makeText(context, "Gagal memproses aksi tersebut", Toast.LENGTH_SHORT).show()
    }
}
