package com.claudemobile.ssh

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.claudemobile.model.SshConfig

class BiometricHelper(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encPrefs = EncryptedSharedPreferences.create(
        context,
        "claude_secure_creds",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val canUseBiometric: Boolean
        get() {
            val mgr = BiometricManager.from(context)
            return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }

    val hasStoredCredentials: Boolean
        get() = encPrefs.getString("host", null) != null

    fun saveCredentials(config: SshConfig) {
        encPrefs.edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("keyPath", config.keyPath)
            .apply()
    }

    fun loadCredentials(): SshConfig {
        return SshConfig(
            host = encPrefs.getString("host", "") ?: "",
            port = encPrefs.getInt("port", 22),
            username = encPrefs.getString("username", "") ?: "",
            password = encPrefs.getString("password", "") ?: "",
            keyPath = encPrefs.getString("keyPath", "") ?: ""
        )
    }

    fun clearCredentials() {
        encPrefs.edit().clear().apply()
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: (SshConfig) -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess(loadCredentials())
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                // Fingerprint not recognized — prompt stays open for retry
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Claude Mobile")
            .setSubtitle("Authenticate to connect to your server")
            .setNegativeButtonText("Use password")
            .build()

        prompt.authenticate(info)
    }
}
