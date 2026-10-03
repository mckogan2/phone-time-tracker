package com.mckogan.playtime

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Parent check by fingerprint (or face), offered wherever the parent PIN is asked.
 * "Use PIN" closes the prompt and leaves the PIN pad on screen as the backup.
 */
object Fingerprint {

    /** True if the parent turned it on and the phone has a fingerprint/face set up. */
    fun available(activity: Activity): Boolean {
        if (!Store(activity).useFingerprint || Build.VERSION.SDK_INT < 29) return false
        val manager = activity.getSystemService(BiometricManager::class.java) ?: return false
        val result = if (Build.VERSION.SDK_INT >= 30) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Shows the fingerprint prompt; [onSuccess] runs only if a registered finger/face matched. */
    fun ask(activity: Activity, title: String, onSuccess: () -> Unit) {
        if (!available(activity)) return
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setNegativeButton(activity.getString(R.string.use_pin), activity.mainExecutor) { _, _ -> }
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        runCatching {
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!activity.isFinishing) onSuccess()
                    }
                },
            )
        }
    }
}
