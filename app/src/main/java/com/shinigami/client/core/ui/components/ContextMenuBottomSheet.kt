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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinigami.client.R
import com.shinigami.client.core.ui.theme.Hint
import com.shinigami.client.core.ui.theme.ImgPlaceholderTint
import com.shinigami.client.core.ui.theme.OnSurface
import com.shinigami.client.core.ui.theme.OnSurfaceVariant
import com.shinigami.client.core.ui.theme.SurfaceContainerDark
import com.shinigami.client.core.ui.theme.SurfaceDark
import com.shinigami.client.core.util.Logger
import com.shinigami.client.core.webview.WebExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private val ContextMenuAccent = Color.White

private enum class PreviewState {
    LOADING,
    SUCCESS,
    ERROR,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuBottomSheet(
    url: String,
    onDismissRequest: () -> Unit,
    onOpenInPopup: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewState by remember { mutableStateOf(PreviewState.LOADING) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(url) {
        previewState = PreviewState.LOADING
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
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                previewBitmap = bitmap
                                previewState = PreviewState.SUCCESS
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                previewState = PreviewState.ERROR
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            previewState = PreviewState.ERROR
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("ContextMenuSheet", "Image preview load failed", e)
                withContext(Dispatchers.Main) {
                    previewState = PreviewState.ERROR
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ContextMenuAccent),
            )
        },
    ) {
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: Preview Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenInPopup(url)
                        onDismissRequest()
                    },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (previewState) {
                        PreviewState.LOADING -> {
                            val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 0.2f,
                                targetValue = 0.6f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "shimmerAlpha",
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(SurfaceContainerDark.copy(alpha = alpha)),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = ContextMenuAccent,
                                    strokeWidth = 3.dp,
                                )
                            }
                        }
                        PreviewState.SUCCESS -> {
                            previewBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        PreviewState.ERROR -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(16.dp),
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_photo_m3),
                                    contentDescription = null,
                                    tint = ImgPlaceholderTint,
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Gagal memuat pratinjau gambar",
                                    color = Hint,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Image URL Chip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceContainerDark)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = url,
                    color = OnSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Row of 3 Primary Action Tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryActionTile(
                    iconRes = R.drawable.ic_download_m3,
                    label = "Unduh",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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

                PrimaryActionTile(
                    iconRes = R.drawable.ic_share_m3,
                    label = "Bagikan",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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

                PrimaryActionTile(
                    iconRes = R.drawable.ic_copy_m3,
                    label = "Salin tautan",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        executeSafeAction(context) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                            Toast.makeText(context, "Tautan disalin", Toast.LENGTH_SHORT).show()
                        }
                        onDismissRequest()
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Secondary Actions List
            SecondaryActionRow(
                iconRes = R.drawable.ic_photo_m3,
                title = "Buka di popup",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenInPopup(url)
                    onDismissRequest()
                },
            )

            SecondaryActionRow(
                iconRes = R.drawable.ic_open_m3,
                title = "Buka di browser",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    executeSafeAction(context) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    onDismissRequest()
                },
            )
        }
    }
}

@Composable
private fun PrimaryActionTile(
    iconRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainerDark)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = ContextMenuAccent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                color = OnSurface,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SecondaryActionRow(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = ContextMenuAccent,
            modifier = Modifier.size(22.dp),
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
