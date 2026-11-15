package com.ktarxhun.logsu.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktarxhun.logsu.ui.theme.AccentRed
import com.ktarxhun.logsu.ui.theme.DarkSurface
import com.ktarxhun.logsu.ui.theme.TextPrimary
import com.ktarxhun.logsu.ui.theme.TextSecondary

@Composable
fun ConfirmDeleteDialog(
    title: String = "Kaydı Sil",
    message: String = "Bu kayıt ve ilişkili medya dosyaları kalıcı olarak silinecek. Emin misiniz?",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = DarkSurface,
        title = {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
            ) {
                Text("Sil", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("İptal")
            }
        }
    )
}
