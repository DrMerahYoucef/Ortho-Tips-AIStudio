package com.example.data.telegram

/**
 * Value types crossing the Telegram boundary. Ported verbatim in behaviour from
 * TeleDrive's `core/telegram` package and collapsed into one file to match the
 * grouping style already used in Ortho Tips (see `data/local/entity/Entities.kt`).
 *
 * No TDLib type appears in any signature here, so the TDLib version can be
 * upgraded without touching anything above [TelegramClient].
 */

/** User-supplied Telegram API credentials from my.telegram.org. Never logged. */
data class TelegramCredentials(
    val apiId: Int,
    val apiHash: String
) {
    override fun toString(): String = "TelegramCredentials(apiId=***, apiHash=***)"
}

data class TelegramUser(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val username: String?,
    val phoneNumber: String,
    val isPremium: Boolean
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { username ?: phoneNumber }
}

/** A channel on the account that can act as the Ortho Tips drive. */
data class StorageChannel(
    val chatId: Long,
    val title: String,
    val documentCount: Int
)

/**
 * A document message in the drive channel. [remoteFileId] can change between
 * sessions; [uniqueFileId] is stable and is the durable remote identity.
 */
data class RemoteDocument(
    val chatId: Long,
    val messageId: Long,
    val remoteFileId: String,
    val uniqueFileId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val caption: String,
    val dateSeconds: Int
) {
    override fun equals(other: Any?): Boolean =
        other is RemoteDocument && other.chatId == chatId && other.messageId == messageId

    override fun hashCode(): Int = 31 * chatId.hashCode() + messageId.hashCode()
}

data class RemoteDocumentPage(
    val documents: List<RemoteDocument>,
    /** Pass as `fromMessageId` for the next (older) page; 0 when exhausted. */
    val nextFromMessageId: Long
)

sealed interface TelegramUploadEvent {
    data class Started(val temporaryMessageId: Long) : TelegramUploadEvent
    data class Progress(val transferredBytes: Long, val totalBytes: Long) : TelegramUploadEvent
    data class Completed(val document: RemoteDocument) : TelegramUploadEvent
}

sealed interface TelegramDownloadEvent {
    data class Progress(val transferredBytes: Long, val totalBytes: Long) : TelegramDownloadEvent

    /** [localPath] points into TDLib's managed file directory. */
    data class Completed(val localPath: String, val sizeBytes: Long) : TelegramDownloadEvent
}

enum class TelegramConnectionState {
    WAITING_FOR_NETWORK,
    CONNECTING,
    UPDATING,
    READY
}

/** Where Telegram says it is sending the login code. */
enum class CodeDeliveryChannel {
    TELEGRAM_APP,
    SMS,
    SMS_WORD,
    SMS_PHRASE,
    CALL,
    FLASH_CALL,
    MISSED_CALL,
    FRAGMENT,
    FIREBASE,
    EMAIL,
    OTHER
}

/**
 * Account-dependent Telegram limits: 2 GiB per file for regular accounts,
 * 4 GiB for Premium. Kept here so a future Telegram change only touches
 * this one declaration.
 */
data class TelegramLimits(
    val maxFileBytes: Long,
    val maxCaptionLength: Int
) {
    companion object {
        private const val GIB = 1024L * 1024L * 1024L

        val REGULAR = TelegramLimits(maxFileBytes = 2 * GIB, maxCaptionLength = 1024)
        val PREMIUM = TelegramLimits(maxFileBytes = 4 * GIB, maxCaptionLength = 2048)

        fun forPremium(isPremium: Boolean): TelegramLimits = if (isPremium) PREMIUM else REGULAR
    }
}

/**
 * Supplies the key that encrypts TDLib's own local database. Implemented by the
 * crypto layer with a random key wrapped by an Android Keystore key.
 */
interface TdlibDatabaseKeyProvider {
    fun databaseKey(): ByteArray
}
