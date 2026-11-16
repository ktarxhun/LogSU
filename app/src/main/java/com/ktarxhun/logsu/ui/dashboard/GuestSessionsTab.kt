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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktarxhun.logsu.data.model.GuestSession
import com.ktarxhun.logsu.service.GuestGuardService
import com.ktarxhun.logsu.ui.FormatUtils
import com.ktarxhun.logsu.ui.components.ConfirmDeleteDialog
import com.ktarxhun.logsu.ui.player.LogSuPlayerActivity
import com.ktarxhun.logsu.ui.theme.*
import com.ktarxhun.logsu.util.GalleryExporter
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun GuestSessionsTab(
    sessions: List<GuestSession>,
    listState: LazyListState = rememberLazyListState(),
    context: Context,
    onDeleteSession: (GuestSession) -> Unit
) {
    val isGuardActive by GuestGuardService.isSessionActiveFlow.collectAsState()
    var sessionToDelete by remember { mutableStateOf<GuestSession?>(null) }
    var exportingSessionId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isGuardActive) AccentGreen.copy(alpha = 0.15f) else DarkSurface
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Emanet Modu",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isGuardActive) "Aktif: Ekran, ses ve kamera devrede" else "Kapalı",
                        color = if (isGuardActive) AccentGreen else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isGuardActive) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                Button(
                    onClick = { GuestGuardService.toggleService(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGuardActive) AccentRed else AccentPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (isGuardActive) "Durdur" else "Başlat", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Kayıtlı Oturumlar (${sessions.size})",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz kaydedilmiş bir emanet oturumu yok.", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sessions, key = { it.id }) { session ->
                    GuestSessionCard(
                        session = session,
                        isExporting = exportingSessionId == session.id,
                        onClick = {
                            val intent = Intent(context, LogSuPlayerActivity::class.java).apply {
                                putExtra(LogSuPlayerActivity.EXTRA_GUEST_SESSION_ID, session.id)
                            }
                            context.startActivity(intent)
                        },
                        onExportToGallery = {
                            if (exportingSessionId == null && !session.isActive) {
                                session.videoFilePath?.let { vPath ->
                                    exportingSessionId = session.id
                                    scope.launch {
                                        try {
                                            val aFile = session.audioFilePath?.let { File(it) }
                                            GalleryExporter.exportVideoToGallery(
                                                context = context,
                                                videoFile = File(vPath),
                                                audioFile = aFile,
                                                customName = "LogSU_Emanet_Oturum_${session.id}"
                                            )
                                        } finally {
                                            exportingSessionId = null
                                        }
                                    }
                                }
                            }
                        },
                        onDelete = {
                            if (!session.isActive) {
                                sessionToDelete = session
                            }
                        }
                    )
                }
            }
        }
    }

    sessionToDelete?.let { target ->
        ConfirmDeleteDialog(
            title = "Emanet Oturumunu Sil",
            message = "\"Oturum #${target.id}\" ve tüm ekran/ses/fotoğraf kayıtları silinecek. Onaylıyor musunuz?",
            onConfirm = {
                onDeleteSession(target)
                sessionToDelete = null
            },
            onDismiss = { sessionToDelete = null }
        )
    }
}

@Composable
fun GuestSessionCard(
    session: GuestSession,
    isExporting: Boolean = false,
    onClick: () -> Unit,
    onExportToGallery: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(session.startTime) { FormatUtils.formatCardDate(session.startTime) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (session.isActive) AccentGreen.copy(alpha = 0.2f) else AccentPrimary.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (session.isActive) Icons.Default.Videocam else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (session.isActive) AccentGreen else AccentPrimary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Oturum #${session.id}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (session.isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(AccentGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Kayıtta", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(text = dateStr, color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row {
                        Text(text = "${session.totalAppSwitches} geçiş", color = AccentSecondary, fontSize = 11.sp)
                        Text(text = " • ", color = TextSecondary, fontSize = 11.sp)
                        Text(text = "${session.totalClipboardCopies} pano", color = AccentOrange, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (session.videoFilePath != null) {
                    IconButton(onClick = onExportToGallery, enabled = !isExporting && !session.isActive) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentGreen)
                        } else {
                            Icon(
                                Icons.Default.Movie,
                                contentDescription = "Galeriye Kaydet",
                                tint = if (session.isActive) TextSecondary.copy(alpha = 0.4f) else AccentPrimary
                            )
                        }
                    }
                }

                IconButton(onClick = onDelete, enabled = !session.isActive) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Sil",
                        tint = if (session.isActive) TextSecondary.copy(alpha = 0.4f) else AccentRed.copy(alpha = 0.85f)
                    )
                }

                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("İzle", fontSize = 13.sp)
                }
            }
        }
    }
}
