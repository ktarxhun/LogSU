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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.service.CallMonitorService
import com.ktarxhun.logsu.ui.FormatUtils
import com.ktarxhun.logsu.ui.components.ConfirmDeleteDialog
import com.ktarxhun.logsu.ui.player.LogSuPlayerActivity
import com.ktarxhun.logsu.ui.theme.*
import com.ktarxhun.logsu.util.GalleryExporter
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AmbientRecordsTab(
    records: List<CallRecord>,
    listState: LazyListState = rememberLazyListState(),
    onDelete: (CallRecord) -> Unit,
    context: Context
) {
    val isAmbientRecording by CallMonitorService.isAmbientRecordingFlow.collectAsState()
    var recordToDelete by remember { mutableStateOf<CallRecord?>(null) }
    var exportingRecordId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isAmbientRecording) AccentRed.copy(alpha = 0.15f) else DarkSurface
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isAmbientRecording) AccentRed else AccentPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isAmbientRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isAmbientRecording) "Kayıt Yapılıyor..." else "Ortam & Toplantı Kaydı",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isAmbientRecording) "Durdurmak için butona basınız" else "Yeni bir ses kaydı başlatın",
                            color = if (isAmbientRecording) AccentRed else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { CallMonitorService.toggleAmbientRecording(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAmbientRecording) AccentRed else AccentPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (isAmbientRecording) "Durdur" else "Başlat", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Kayıtlar (${records.size})",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz kaydedilmiş ses kaydı yok.", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(records, key = { it.id }) { record ->
                    AmbientRecordCard(
                        record = record,
                        isExporting = exportingRecordId == record.id,
                        onDelete = { recordToDelete = record },
                        onExportToGallery = {
                            if (exportingRecordId == null) {
                                record.audioFilePath?.let { audioPath ->
                                    exportingRecordId = record.id
                                    scope.launch {
                                        val dateStr = FormatUtils.formatShortDate(record.startTime)
                                        GalleryExporter.exportAudioAsVideoToGallery(
                                            context = context,
                                            audioFile = File(audioPath),
                                            title = "Ses Kaydı #${record.id}",
                                            subtitle = dateStr
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
            title = "Ses Kaydını Sil",
            message = "\"Ses Kaydı #${target.id}\" kalıcı olarak silinecek. Onaylıyor musunuz?",
            onConfirm = {
                onDelete(target)
                recordToDelete = null
            },
            onDismiss = { recordToDelete = null }
        )
    }
}

@Composable
fun AmbientRecordCard(
    record: CallRecord,
    isExporting: Boolean = false,
    onDelete: () -> Unit,
    onExportToGallery: () -> Unit,
    onPlay: () -> Unit
) {
    val dateStr = remember(record.startTime) { FormatUtils.formatCardDate(record.startTime) }
    val durationStr = remember(record.durationSeconds) { FormatUtils.formatDurationSeconds(record.durationSeconds) }

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
                Box(
                    modifier = Modifier
                        .background(AccentOrange.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "Ses Kaydı", color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Text(text = dateStr, color = TextSecondary, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Ses Kaydı #${record.id}",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

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
                IconButton(onClick = onExportToGallery, enabled = !isExporting) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentPrimary)
                    } else {
                        Icon(Icons.Default.Movie, contentDescription = "Videoya Çevir", tint = AccentPrimary)
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
