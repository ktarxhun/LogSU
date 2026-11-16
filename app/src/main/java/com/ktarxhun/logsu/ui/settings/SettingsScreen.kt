package com.ktarxhun.logsu.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktarxhun.logsu.LogSuApp
import com.ktarxhun.logsu.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(
    isRootGranted: Boolean,
    onRequestRoot: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var purgeStatus by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LogSU Ayarları & Durum", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Root Erişim İzni:", color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = if (isRootGranted) "Yetkilendirildi (Magisk/KSU)" else "Erişim Yok",
                        color = if (isRootGranted) AccentGreen else AccentRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                if (!isRootGranted) {
                    Button(
                        onClick = onRequestRoot,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Root İzni İste", fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = BorderColor)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Otomatik Temizlik Politikası:", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("• Rehberde kayıtlı aramalar: 3 gün sonra silinir", color = TextSecondary, fontSize = 12.sp)
                    Text("• Yabancı / tanınmayan numaralar: 7 gün sonra silinir", color = TextSecondary, fontSize = 12.sp)
                    Text("• Delil kilidi olan aramalar & Ortam ses kayıtları: Otomatik silinmez", color = AccentOrange, fontSize = 12.sp)
                }

                HorizontalDivider(color = BorderColor)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Emanet Modu Kamera Güvenliği:", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Kullanıcının kendi yüzünü çekmemek için ilk 20 saniye veya ilk ekran açılışına kadar bekleme payı (grace period) aktiftir.", color = TextSecondary, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val count = LogSuApp.instance.repository.purgeOldRecords()
                                purgeStatus = "$count adet süresi dolan arama temizlendi."
                            } catch (e: Exception) {
                                purgeStatus = "Temizleme başarısız: ${e.message ?: "Bilinmeyen hata"}"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Eski Aramaları Şimdi Temizle", fontSize = 12.sp, color = TextPrimary)
                }

                purgeStatus?.let {
                    Text(text = it, color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Kapat") }
        },
        containerColor = DarkSurface
    )
}
