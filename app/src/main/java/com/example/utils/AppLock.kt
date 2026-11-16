package com.example.utils

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Screen lock backed by whatever the phone already uses: fingerprint, face, PIN,
 * password or pattern. The app never sees or stores the secret; Android only
 * reports success or failure.
 */
object AppLock {

    /**
     * True once the user has unlocked in this process. It lives in memory only, so
     * killing the app (or the system killing it) locks it again, while rotating the
     * screen or opening the camera does not.
     */
    @Volatile
    var sessionUnlocked: Boolean = false

    /** Time away from the app after which it locks again. */
    const val GRACE_MS = 5 * 60 * 1000L

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** False when the phone has no lock set up at all — the app must not lock the user out then. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Asks Android to verify the user. [onResult] gets true only on success;
     * cancelling, a wrong fingerprint or an unusable sensor give false.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        onResult: (Boolean) -> Unit
    ) {
        if (!isAvailable(activity)) {
            onResult(true) // No device lock configured: nothing to check against.
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onResult(true)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onResult(false)

                // A single failed finger is not a refusal; the prompt stays open.
                override fun onAuthenticationFailed() = Unit
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
            .setAllowedAuthenticators(ALLOWED)
            .build()
        prompt.authenticate(info)
    }
}

/** The hosting activity, or null in a preview. */
@Composable
fun currentFragmentActivity(): FragmentActivity? {
    var context = LocalContext.current
    while (context is android.content.ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Wraps an action so it only runs after the phone's lock has been passed.
 * Returns a function to call in place of the action's own `onClick`.
 */
@Composable
fun rememberProtectedAction(enabled: Boolean): (String, () -> Unit) -> Unit {
    val activity = currentFragmentActivity()
    val context = LocalContext.current
    return { subtitle, action ->
        if (!enabled || activity == null || !AppLock.isAvailable(context)) {
            action()
        } else {
            AppLock.authenticate(
                activity = activity,
                title = context.getString(com.example.R.string.app_lock_confirm_title),
                subtitle = subtitle
            ) { success -> if (success) action() }
        }
    }
}
