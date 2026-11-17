package com.ktarxhun.logsu.ui.auth

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object AuthManager {

    /**
     * androidx.biometric 1.1.0 icinde BIOMETRIC_STRONG | DEVICE_CREDENTIAL kombinasyonu
     * yalnizca API 30 ve uzerinde desteklenir. API 26-29 cihazlarda bu kombinasyon
     * BIOMETRIC_ERROR_UNSUPPORTED dondurdugu icin desteklenen BIOMETRIC_WEAK | DEVICE_CREDENTIAL
     * kombinasyonuna dusulur. Boylece Android 9/10 cihazlarda kilit acilabilir.
     */
    private fun supportedAuthenticators(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val authenticators = supportedAuthenticators()

        val canAuth = try {
            BiometricManager.from(activity).canAuthenticate(authenticators)
        } catch (e: Exception) {
            onError(e.message ?: "Cihaz dogrulama durumu okunamadi.")
            return
        }
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            val reason = when (canAuth) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "Cihazda parmak izi veya kilit ekranı (PIN/şifre) tanımlı değil."
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Biyometrik donanım veya cihaz doğrulaması kullanılamıyor."
                else -> "Cihaz doğrulama mekanizması hazır değil ($canAuth)."
            }
            onError(reason)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }
        })

        try {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("LogSU")
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError(e.message ?: "Kimlik doğrulama başlatılamadı.")
        }
    }
}
