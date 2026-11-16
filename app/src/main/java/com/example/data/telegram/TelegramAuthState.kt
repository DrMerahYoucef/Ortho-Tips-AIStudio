package com.example.data.telegram

sealed interface TelegramAuthState {

    /** TDLib has not been started yet (no credentials configured). */
    data object Uninitialized : TelegramAuthState

    /** TDLib is starting up or transitioning between states. */
    data object Initializing : TelegramAuthState

    data object WaitingForPhoneNumber : TelegramAuthState

    data class WaitingForCode(
        val phoneNumber: String,
        val channel: CodeDeliveryChannel,
        val codeLength: Int?,
        val resendTimeoutSeconds: Int
    ) : TelegramAuthState

    /** A QR code is on screen for an authorized Telegram app to scan. */
    data class WaitingForQrScan(val link: String) : TelegramAuthState

    data object WaitingForEmailAddress : TelegramAuthState

    data class WaitingForEmailCode(
        val emailPattern: String,
        val codeLength: Int?
    ) : TelegramAuthState

    data class WaitingForPassword(val passwordHint: String?) : TelegramAuthState

    /**
     * The phone number has no Telegram account. Account creation is out of scope
     * for a medical records app; the surgeon must register with an official client.
     */
    data object RegistrationRequired : TelegramAuthState

    data class Failed(val message: String) : TelegramAuthState

    data object Ready : TelegramAuthState

    data object LoggingOut : TelegramAuthState

    data object Closed : TelegramAuthState
}

/**
 * Wraps a TDLib error. [retryAfterSeconds] is set for FLOOD_WAIT (429) errors so
 * the transfer queue can back off for exactly as long as Telegram asked.
 */
class TelegramException(
    val code: Int,
    override val message: String,
    val retryAfterSeconds: Int? = null
) : Exception(message) {

    val isRateLimit: Boolean get() = code == 429

    val isChannelLimit: Boolean
        get() = message.contains("CHANNELS_TOO_MUCH", ignoreCase = true) ||
                message.contains("too many chats", ignoreCase = true) ||
                message.contains("Too much chats", ignoreCase = true)

    val isNetworkFailure: Boolean
        get() = code == 500 && message.contains("network", ignoreCase = true)

    companion object {
        private val floodWaitRegex = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)

        fun from(code: Int, message: String): TelegramException {
            val retryAfter = if (code == 429) {
                floodWaitRegex.find(message)?.groupValues?.get(1)?.toIntOrNull()
            } else null
            return TelegramException(code, message, retryAfter)
        }
    }
}
