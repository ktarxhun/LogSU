package com.ktarxhun.logsu.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ktarxhun.logsu.ui.auth.AppSessionLock
import com.ktarxhun.logsu.ui.auth.AuthManager
import com.ktarxhun.logsu.ui.dashboard.DashboardScreen
import com.ktarxhun.logsu.ui.theme.*

class MainActivity : FragmentActivity() {

    private var isPromptInFlight = false
    private var permissionsFlowDone by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsFlowDone = true
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        if (!recordAudioGranted || !phoneStateGranted) {
            Toast.makeText(this, "Temel izinler verilmediğinde arama kaydı kısıtlanabilir.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        permissionsFlowDone = missingRequiredPermissions().isEmpty()

        if (!AppSessionLock.isUnlocked) {
            triggerBiometricAuth()
        }

        setContent {
            LogSuTheme {
                val isUnlocked = AppSessionLock.isUnlocked

                LaunchedEffect(isUnlocked) {
                    if (isUnlocked) {
                        requestRequiredPermissions()
                    } else {
                        permissionsFlowDone = missingRequiredPermissions().isEmpty()
                    }
                }

                if (isUnlocked) {
                    DashboardScreen(context = this, permissionsReady = permissionsFlowDone)
                } else {
                    LockScreen(
                        onUnlockClicked = { triggerBiometricAuth() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!AppSessionLock.isUnlocked && !isPromptInFlight) {
            triggerBiometricAuth()
        }
    }

    private fun triggerBiometricAuth() {
        if (isPromptInFlight || AppSessionLock.isUnlocked) return
        isPromptInFlight = true
        AuthManager.authenticate(
            activity = this,
            onSuccess = {
                isPromptInFlight = false
                AppSessionLock.unlock()
            },
            onError = { error ->
                isPromptInFlight = false
                if (error.isNotBlank()) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun missingRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val missing = missingRequiredPermissions()
        if (missing.isEmpty()) {
            permissionsFlowDone = true
        } else {
            permissionsFlowDone = false
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun LockScreen(onUnlockClicked: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(DarkSurfaceVariant, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "LogSU",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onUnlockClicked,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Parmak İzi ile Giriş", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
