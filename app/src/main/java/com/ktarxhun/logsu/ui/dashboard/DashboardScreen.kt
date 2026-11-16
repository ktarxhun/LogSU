package com.ktarxhun.logsu.ui.dashboard

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import com.ktarxhun.logsu.LogSuApp
import com.ktarxhun.logsu.data.model.CallType
import com.ktarxhun.logsu.root.RootShellManager
import com.ktarxhun.logsu.service.CallMonitorService
import com.ktarxhun.logsu.ui.settings.SettingsDialog
import com.ktarxhun.logsu.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(context: Context, permissionsReady: Boolean = true) {
    var showSettings by remember { mutableStateOf(false) }
    var isRootGranted by remember { mutableStateOf(RootShellManager.isRootCached()) }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })

    val callsListState = rememberLazyListState()
    val ambientListState = rememberLazyListState()
    val guestListState = rememberLazyListState()

    val allCallRecords by LogSuApp.instance.repository.allCallRecords.collectAsState(initial = emptyList())
    val guestSessions by LogSuApp.instance.repository.allGuestSessions.collectAsState(initial = emptyList())
    val isAmbientRecording by CallMonitorService.isAmbientRecordingFlow.collectAsState()

    val callsOnly = remember(allCallRecords) {
        allCallRecords.filter { it.callType != CallType.AMBIENT_RECORDING }
    }
    val ambientOnly = remember(allCallRecords) {
        allCallRecords.filter { it.callType == CallType.AMBIENT_RECORDING }
    }

    // Ön planda arka plan thread'inde etkileşimli root kontrolü (Magisk/KSU penceresini tetikler)
    LaunchedEffect(permissionsReady) {
        if (permissionsReady) {
            isRootGranted = RootShellManager.ensureRootInteractive()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LogSU",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isRootGranted) AccentGreen.copy(alpha = 0.2f) else AccentRed.copy(alpha = 0.2f)
                                )
                                .clickable(enabled = !isRootGranted) {
                                    scope.launch {
                                        isRootGranted = RootShellManager.ensureRootInteractive()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isRootGranted) "Root Aktif" else "Root İste",
                                color = if (isRootGranted) AccentGreen else AccentRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ayarlar", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = isAmbientRecording) {
                Card(
                    shape = RoundedCornerShape(0.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(AccentRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Ortam Ses Kaydı Devam Ediyor",
                                color = AccentRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        TextButton(
                            onClick = { CallMonitorService.stopAmbientRecording(context) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Durdur & Kaydet", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = DarkBackground,
                contentColor = AccentPrimary,
                divider = { HorizontalDivider(color = BorderColor) }
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("Aramalar (${callsOnly.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("Ses Kaydı (${ambientOnly.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = { Text("Emanet (${guestSessions.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                beyondBoundsPageCount = 2,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> CallRecordsTab(
                        records = callsOnly,
                        listState = callsListState,
                        onToggleLock = { record ->
                            scope.launch {
                                LogSuApp.instance.repository.setLockStatus(record.id, !record.isLocked)
                            }
                        },
                        onDelete = { record ->
                            scope.launch {
                                try {
                                    LogSuApp.instance.repository.deleteCallRecord(record)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Dosyalar silinemedi. Kayıt korundu, tekrar deneyin.", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        context = context
                    )
                    1 -> AmbientRecordsTab(
                        records = ambientOnly,
                        listState = ambientListState,
                        onDelete = { record ->
                            scope.launch {
                                try {
                                    LogSuApp.instance.repository.deleteCallRecord(record)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Dosyalar silinemedi. Kayıt korundu, tekrar deneyin.", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        context = context
                    )
                    2 -> GuestSessionsTab(
                        sessions = guestSessions,
                        listState = guestListState,
                        context = context,
                        onDeleteSession = { session ->
                            scope.launch {
                                try {
                                    LogSuApp.instance.repository.deleteGuestSession(session)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Dosyalar silinemedi. Kayıt korundu, tekrar deneyin.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            isRootGranted = isRootGranted,
            onRequestRoot = {
                scope.launch {
                    isRootGranted = RootShellManager.ensureRootInteractive()
                }
            },
            onDismiss = { showSettings = false }
        )
    }
}
