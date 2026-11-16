package com.example.data.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Telegram abstraction used by the storage layer. No TDLib type crosses this
 * boundary, so the implementation can be replaced or upgraded independently and
 * unit tests can substitute a fake.
 *
 * The surface is TeleDrive's, narrowed to the operations Ortho Tips actually
 * performs: authenticate, find or create the drive channel, push and pull
 * documents, and rewrite or delete their metadata.
 */
interface TelegramClient {

    val authState: StateFlow<TelegramAuthState>
    val connectionState: StateFlow<TelegramConnectionState>

    /** Starts TDLib with the given credentials. Safe to call repeatedly. */
    suspend fun start(credentials: TelegramCredentials)

    suspend fun submitPhoneNumber(phoneNumber: String)

    suspend fun submitCode(code: String)

    suspend fun submitPassword(password: String)

    suspend fun submitEmailAddress(email: String)

    suspend fun submitEmailCode(code: String)

    suspend fun resendCode()

    /** Switches login to a QR code that an authorized Telegram app scans. */
    suspend fun requestQrCodeAuthentication()

    /**
     * Leaves QR login and returns to the phone form. TDLib refuses a phone number
     * while it waits for another device and offers no way back, so the client is
     * closed and started again.
     */
    suspend fun restartAuthentication()

    suspend fun logout()

    suspend fun getCurrentUser(): TelegramUser

    suspend fun getLimits(): TelegramLimits

    /** True when the chat is reachable, false when gone, null when unclear. */
    suspend fun chatExists(chatId: Long): Boolean?

    /**
     * Returns the chat id of the private drive channel, validating [knownChatId]
     * first, then searching existing chats by marker, and finally creating a new
     * channel. Survives a local data wipe, which is what makes Telegram usable
     * as a recovery target rather than just a mirror.
     */
    suspend fun ensureStorageChat(knownChatId: Long?): Long

    /** Channels on this account that already look like an Ortho Tips drive. */
    suspend fun listStorageChannels(): List<StorageChannel>

    /**
     * Uploads a local file as a document. Collecting the returned flow drives the
     * upload; cancelling the collection aborts it and deletes the pending message.
     */
    fun uploadDocument(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        caption: String,
        thumbnailPath: String? = null
    ): Flow<TelegramUploadEvent>

    /**
     * Downloads a full remote file into TDLib's managed storage. Cancelling the
     * collection cancels the download.
     */
    fun downloadDocument(remoteFileId: String): Flow<TelegramDownloadEvent>

    suspend fun fetchDocuments(chatId: Long, fromMessageId: Long, limit: Int): RemoteDocumentPage

    suspend fun getDocument(chatId: Long, messageId: Long): RemoteDocument?

    suspend fun editCaption(chatId: Long, messageId: Long, caption: String)

    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>)

    /** Frees TDLib's cached copy of a downloaded file without touching Telegram. */
    suspend fun discardLocalCopy(remoteFileId: String)
}
