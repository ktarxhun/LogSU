package com.ktarxhun.logsu.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.CallType
import com.ktarxhun.logsu.ui.FormatUtils
import com.ktarxhun.logsu.ui.components.ConfirmDeleteDialog
import com.ktarxhun.logsu.ui.player.LogSuPlayerActivity
import com.ktarxhun.logsu.ui.theme.*
import com.ktarxhun.logsu.util.GalleryExporter
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CallRecordsTab(
    records: List<CallRecord>,
    listState: LazyListState = rememberLazyListState(),
    onToggleLock: (CallRecord) -> Unit,
    onDelete: (CallRecord) -> Unit,
    context: Context
) {
    var searchQuery by remember { mutableStateOf("") }
    var recordToDelete by remember { mutableStateOf<CallRecord?>(null) }
    var exportingRecordId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    val filteredRecords = remember(records, searchQuery) {
        if (searchQuery.isBlank()) records
        else {
            records.filter {
                (it.contactName?.contains(searchQuery, ignoreCase = true) == true) ||
                it.phoneNumber.contains(searchQuery) ||
                (it.transcript?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Kişi, numara veya metin ara...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = BorderColor,
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface
            )
        )

        if (filteredRecords.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (searchQuery.isBlank()) "Henüz kayıtlı bir arama yok." else "Aramaya uygun kayıt bulunamadı.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredRecords, key = { it.id }) { record ->
                    CallRecordCard(
                        record = record,
                        isExporting = exportingRecordId == record.id,
                        onToggleLock = { onToggleLock(record) },
                        onDelete = { recordToDelete = record },
                        onExportToGallery = {
                            if (exportingRecordId == null) {
                                record.audioFilePath?.let { audioPath ->
                                    exportingRecordId = record.id
                                    scope.launch {
                                        val title = record.contactName ?: record.phoneNumber
                                        val subtitle = FormatUtils.formatShortDate(record.startTime)
                                        GalleryExporter.exportAudioAsVideoToGallery(
                                            context = context,
                                            audioFile = File(audioPath),
                                            title = title,
                                            subtitle = subtitle
                                        ) { _, _ ->
                                            exportingRecordId = null
                                        }
                                    }
                                }
                            }
                        },
                        onPlay = {
                            val intent = Intent(context, LogSuPlayerActivity::class.java).apply {
                                putExtra(LogSuPlayerActivity.EXTRA_RECORD_ID, record.id)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    recordToDelete?.let { target ->
        ConfirmDeleteDialog(
            title = "Arama Kaydını Sil",
            message = "\"${target.contactName ?: target.phoneNumber}\" kaydı kalıcı olarak silinecek. Onaylıyor musunuz?",
            onConfirm = {
                onDelete(target)
                recordToDelete = null
            },
            onDismiss = { recordToDelete = null }
        )
    }
}

@Composable
fun CallRecordCard(
    record: CallRecord,
    isExporting: Boolean = false,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
    onExportToGallery: () -> Unit,
    onPlay: () -> Unit
) {
    val dateStr = remember(record.startTime) { FormatUtils.formatCardDate(record.startTime) }
    val durationStr = remember(record.durationSeconds) { FormatUtils.formatDurationSeconds(record.durationSeconds) }

    val typeColor = when (record.callType) {
        CallType.INCOMING_GSM -> AccentGreen
        CallType.OUTGOING_GSM -> AccentPrimary
        CallType.MISSED_GSM -> AccentRed
        CallType.WHATSAPP_CALL -> Color(0xFF25D366)
        CallType.AMBIENT_RECORDING -> AccentOrange
    }

    val typeText = when (record.callType) {
        CallType.INCOMING_GSM -> "Gelen"
        CallType.OUTGOING_GSM -> "Giden"
        CallType.MISSED_GSM -> "Cevapsız"
        CallType.WHATSAPP_CALL -> "WhatsApp"
        CallType.AMBIENT_RECORDING -> "Ses Kaydı"
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(typeColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = typeText, color = typeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    if (record.isLocked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(AccentOrange.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = "Kilitli", color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(text = dateStr, color = TextSecondary, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.contactName ?: record.phoneNumber,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (record.contactName != null) {
                        Text(text = record.phoneNumber, color = TextSecondary, fontSize = 12.sp)
                    }
                }

                Text(
                    text = durationStr,
                    color = AccentSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!record.transcript.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = record.transcript.take(100) + if (record.transcript.length > 100) "..." else "",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onToggleLock) {
                    Icon(
                        imageVector = if (record.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Kilit",
                        tint = if (record.isLocked) AccentOrange else TextSecondary
                    )
                }

                IconButton(onClick = onExportToGallery, enabled = !isExporting) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentPrimary)
                    } else {
                        Icon(Icons.Default.Movie, contentDescription = "Galeriye Kaydet", tint = AccentPrimary)
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Sil", tint = AccentRed.copy(alpha = 0.85f))
                }

                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dinle", fontSize = 13.sp)
                }
            }
        }
    }
}
