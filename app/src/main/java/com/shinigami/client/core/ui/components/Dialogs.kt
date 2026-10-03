package com.shinigami.client.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinigami.client.core.ui.theme.OnSurface
import com.shinigami.client.core.ui.theme.OnSurfaceVariant
import com.shinigami.client.core.ui.theme.PrimaryAccent
import com.shinigami.client.core.ui.theme.SurfaceDark

@Composable
fun ShinigamiInfoDialog(
    title: String,
    message: String,
    buttonText: String = "OK",
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = title,
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = message,
                color = OnSurfaceVariant,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = buttonText, color = PrimaryAccent)
            }
        },
    )
}

@Composable
fun ShinigamiConfirmDialog(
    title: String,
    message: String,
    yesText: String = "OK",
    noText: String = "Batal",
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNo,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = title,
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = message,
                color = OnSurfaceVariant,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onYes) {
                Text(text = yesText, color = PrimaryAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onNo) {
                Text(text = noText, color = OnSurfaceVariant)
            }
        },
    )
}

@Composable
fun ShinigamiPromptDialog(
    title: String,
    message: String,
    defaultInput: String = "",
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var inputText by remember { mutableStateOf(defaultInput) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = title,
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        color = OnSurfaceVariant,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryAccent,
                        unfocusedBorderColor = OnSurfaceVariant,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                    ),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(inputText) }) {
                Text(text = "OK", color = PrimaryAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "Batal", color = OnSurfaceVariant)
            }
        },
    )
}
