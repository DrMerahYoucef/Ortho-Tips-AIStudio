package com.example.data.telegram

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * TDLib-backed [TelegramClient], ported from TeleDrive's `TdLibTelegramClient`.
 *
 * Differences from the original, all deliberate:
 *  - Dagger/Hilt annotations removed; the single instance is owned by `AppModule`,
 *    matching the hand-rolled DI already used throughout Ortho Tips.
 *  - Streaming (`ReadFilePart`/range downloads), link previews, country lists and
 *    channel avatars are dropped: Ortho Tips transfers whole files and never
 *    renders Telegram chat UI, so those paths would be dead code.
 *  - The drive channel is titled for this app and carries its own marker, so an
 *    account can hold a TeleDrive channel and an Ortho Tips channel side by side
 *    without either app adopting the other's storage.
 *
 * Everything else — the update pump, the authorization state machine, flood-wait
 * handling, the "a completed download whose bytes are gone is not complete" rule —
 * is TeleDrive's logic, because it is already correct and hard-won.
 */
class TdLibTelegramClient(
    private val context: Context,
    private val databaseKeyProvider: TdlibDatabaseKeyProvider
) : TelegramClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clientMutex = Mutex()
    private val storageChatMutex = Mutex()

    @Volatile
    private var client: Client? = null

    @Volatile
    private var credentials: TelegramCredentials? = null
    private var clientGeneration = 0

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Uninitialized)
    override val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _connectionState = MutableStateFlow(TelegramConnectionState.CONNECTING)
    override val connectionState: StateFlow<TelegramConnectionState> =
        _connectionState.asStateFlow()

    private val updates = MutableSharedFlow<TdApi.Object>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ---- Lifecycle -----------------------------------------------------------

    override suspend fun start(credentials: TelegramCredentials) {
        clientMutex.withLock {
            this.credentials = credentials
            if (client == null || _authState.value == TelegramAuthState.Closed) {
                _authState.value = TelegramAuthState.Initializing
                client = createClient()
            }
        }
    }

    private fun createClient(): Client {
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        val generation = ++clientGeneration
        return Client.create(
            { update -> handleUpdate(generation, update) },
            { throwable -> DriveLog.e(TAG, "Update handler error", throwable) },
            { throwable -> DriveLog.e(TAG, "TDLib exception", throwable) }
        )
    }

    private fun handleUpdate(generation: Int, update: TdApi.Object) {
        updates.tryEmit(update)
        when (update) {
            is TdApi.UpdateAuthorizationState ->
                handleAuthorizationState(generation, update.authorizationState)

            is TdApi.UpdateConnectionState -> _connectionState.value = when (update.state) {
                is TdApi.ConnectionStateWaitingForNetwork -> TelegramConnectionState.WAITING_FOR_NETWORK
                is TdApi.ConnectionStateConnecting,
                is TdApi.ConnectionStateConnectingToProxy -> TelegramConnectionState.CONNECTING

                is TdApi.ConnectionStateUpdating -> TelegramConnectionState.UPDATING
                else -> TelegramConnectionState.READY
            }
        }
    }

    private fun handleAuthorizationState(generation: Int, state: TdApi.AuthorizationState) {
        DriveLog.d(TAG, "Auth state ${state::class.simpleName} gen=$generation/$clientGeneration")
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                scope.launch { sendTdlibParameters() }

            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _authState.value = TelegramAuthState.WaitingForPhoneNumber

            is TdApi.AuthorizationStateWaitCode -> {
                val info = state.codeInfo
                _authState.value = TelegramAuthState.WaitingForCode(
                    phoneNumber = info.phoneNumber,
                    channel = info.type.toChannel(),
                    codeLength = info.type.codeLength(),
                    resendTimeoutSeconds = info.timeout
                )
            }

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _authState.value = TelegramAuthState.WaitingForQrScan(state.link)

            is TdApi.AuthorizationStateWaitEmailAddress ->
                _authState.value = TelegramAuthState.WaitingForEmailAddress

            is TdApi.AuthorizationStateWaitEmailCode -> {
                val info = state.codeInfo
                _authState.value = TelegramAuthState.WaitingForEmailCode(
                    emailPattern = info.emailAddressPattern,
                    codeLength = info.length.takeIf { it > 0 }
                )
            }

            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = TelegramAuthState.WaitingForPassword(
                    state.passwordHint?.takeIf { it.isNotEmpty() }
                )

            is TdApi.AuthorizationStateWaitRegistration ->
                _authState.value = TelegramAuthState.RegistrationRequired

            is TdApi.AuthorizationStateReady ->
                _authState.value = TelegramAuthState.Ready

            is TdApi.AuthorizationStateLoggingOut ->
                _authState.value = TelegramAuthState.LoggingOut

            is TdApi.AuthorizationStateClosing ->
                _authState.value = TelegramAuthState.Initializing

            is TdApi.AuthorizationStateClosed -> {
                if (generation == clientGeneration) {
                    client = null
                    _authState.value = TelegramAuthState.Closed
                }
            }

            else -> DriveLog.w(TAG, "Unhandled authorization state: ${state::class.simpleName}")
        }
    }

    private suspend fun sendTdlibParameters() {
        val creds = credentials ?: return
        var activeClient = client
        var waitedMs = 0
        while (activeClient == null && waitedMs < CLIENT_WAIT_LIMIT_MS) {
            delay(CLIENT_WAIT_STEP_MS.milliseconds)
            waitedMs += CLIENT_WAIT_STEP_MS.toInt()
            activeClient = client
        }
        if (activeClient == null) {
            _authState.value = TelegramAuthState.Failed("Telegram client did not start")
            return
        }
        val base = tdlibRoot()
        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(base, "db").absolutePath
            filesDirectory = File(base, "files").absolutePath
            databaseEncryptionKey = databaseKeyProvider.databaseKey()
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = creds.apiId
            apiHash = creds.apiHash
            systemLanguageCode = Locale.getDefault().toLanguageTag()
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        activeClient.send(parameters) { result ->
            if (result is TdApi.Error) {
                DriveLog.e(TAG, "SetTdlibParameters failed: ${result.code}")
                _authState.value = TelegramAuthState.Failed(result.message)
            }
        }
    }

    private fun tdlibRoot(): File = File(context.filesDir, "tdlib")

    private suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val activeClient = client ?: throw TelegramException(500, "Telegram client not started")
        return suspendCancellableCoroutine { continuation ->
            activeClient.send(function) { result ->
                if (result is TdApi.Error) {
                    continuation.resumeWithException(
                        TelegramException.from(result.code, result.message)
                    )
                } else {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as T)
                }
            }
        }
    }

    private suspend fun awaitAuthorized() {
        if (_authState.value == TelegramAuthState.Ready) return
        withTimeoutOrNull(AUTH_WAIT_MS.milliseconds) {
            authState.first { it == TelegramAuthState.Ready }
        }
    }

    // ---- Authentication ------------------------------------------------------

    override suspend fun submitPhoneNumber(phoneNumber: String) {
        send(
            TdApi.SetAuthenticationPhoneNumber(
                phoneNumber,
                TdApi.PhoneNumberAuthenticationSettings()
            )
        )
    }

    override suspend fun submitCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    override suspend fun submitPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun submitEmailAddress(email: String) {
        send(TdApi.SetAuthenticationEmailAddress(email))
    }

    override suspend fun submitEmailCode(code: String) {
        send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code)))
    }

    override suspend fun resendCode() {
        send(TdApi.ResendAuthenticationCode(null))
    }

    override suspend fun requestQrCodeAuthentication() {
        send(TdApi.RequestQrCodeAuthentication(LongArray(0)))
    }

    override suspend fun restartAuthentication() {
        val current = credentials ?: return
        runCatching { send(TdApi.Close()) }
        withTimeoutOrNull(CLOSE_WAIT_LIMIT_MS.milliseconds) {
            _authState.first { it == TelegramAuthState.Closed }
        }
        clientMutex.withLock { client = null }
        tdlibRoot().deleteRecursively()
        start(current)
    }

    override suspend fun logout() {
        send(TdApi.LogOut())
    }

    override suspend fun getCurrentUser(): TelegramUser {
        awaitAuthorized()
        val user = send(TdApi.GetMe())
        return TelegramUser(
            id = user.id,
            firstName = user.firstName,
            lastName = user.lastName,
            username = user.usernames?.editableUsername?.takeIf { it.isNotEmpty() }
                ?: user.usernames?.activeUsernames?.firstOrNull(),
            phoneNumber = user.phoneNumber,
            isPremium = user.isPremium
        )
    }

    override suspend fun getLimits(): TelegramLimits =
        TelegramLimits.forPremium(getCurrentUser().isPremium)

    // ---- Drive channel -------------------------------------------------------

    override suspend fun chatExists(chatId: Long): Boolean? {
        awaitAuthorized()
        return runCatching { send(TdApi.GetChat(chatId)); true }
            .getOrElse { error ->
                val telegram = error as? TelegramException ?: return null
                if (telegram.code == 400) false else null
            }
    }

    override suspend fun ensureStorageChat(knownChatId: Long?): Long =
        storageChatMutex.withLock {
            awaitAuthorized()
            knownChatId?.let { candidate ->
                if (isStorageChat(candidate)) {
                    DriveLog.d(TAG, "Reusing known drive channel")
                    return@withLock candidate
                }
            }
            // Cheap pass first: title search, server-side then local. Retried
            // because right after sign-in the chat list is often still syncing
            // and the drive simply is not visible yet.
            repeat(DISCOVERY_ATTEMPTS) { attempt ->
                searchStorageChat()?.let { return@withLock it }
                if (attempt < DISCOVERY_ATTEMPTS - 1) delay(DISCOVERY_RETRY_MS.milliseconds)
            }

            // Expensive pass, exactly once: walking the chat lists costs one
            // GetChat per chat. Running it inside the retry loop above turned a
            // few seconds of work into thousands of requests and looked like a
            // hang on any account with a normal number of chats.
            scanChatListsForStorageChat()?.let { return@withLock it }

            DriveLog.i(TAG, "No existing drive found; creating one")
            val chat = send(
                TdApi.CreateNewSupergroupChat(
                    STORAGE_CHAT_TITLE,
                    false,
                    true,
                    STORAGE_CHAT_MARKER,
                    null,
                    0,
                    false
                )
            )
            DriveLog.i(TAG, "Created a new drive channel")
            chat.id
        }

    override suspend fun listStorageChannels(): List<StorageChannel> {
        awaitAuthorized()
        val candidates = LinkedHashSet<Long>()
        runCatching { send(TdApi.SearchChatsOnServer(STORAGE_CHAT_TITLE, CHAT_SEARCH_LIMIT)) }
            .getOrNull()?.chatIds?.forEach { candidates.add(it) }
        runCatching { send(TdApi.SearchChats(STORAGE_CHAT_TITLE, CHAT_SEARCH_LIMIT)) }
            .getOrNull()?.chatIds?.forEach { candidates.add(it) }

        return candidates.mapNotNull { chatId ->
            runCatching {
                val chat = send(TdApi.GetChat(chatId))
                val type = chat.type as? TdApi.ChatTypeSupergroup ?: return@runCatching null
                if (!type.isChannel) return@runCatching null
                if (!hasStorageMarker(type.supergroupId) && chat.title != STORAGE_CHAT_TITLE) {
                    return@runCatching null
                }
                val count = runCatching {
                    send(TdApi.GetSupergroupFullInfo(type.supergroupId)).memberCount
                }.getOrDefault(0)
                StorageChannel(chatId = chatId, title = chat.title, documentCount = count)
            }.getOrNull()
        }
    }

    /**
     * A channel counts as the drive when it carries the marker, or when it is an
     * own private channel with the drive title whose description never took. The
     * marker is written back so the next check is unambiguous.
     */
    private suspend fun isStorageChat(chatId: Long): Boolean = runCatching {
        val chat = send(TdApi.GetChat(chatId))
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return@runCatching false
        if (!type.isChannel) return@runCatching false
        val fullInfo = send(TdApi.GetSupergroupFullInfo(type.supergroupId))
        if (fullInfo.description.contains(STORAGE_CHAT_MARKER)) return@runCatching true
        if (chat.title != STORAGE_CHAT_TITLE) return@runCatching false
        if (!isOwnPrivateChannel(type.supergroupId)) return@runCatching false
        writeMarker(chatId, fullInfo.description)
        true
    }.onFailure { DriveLog.d(TAG, "Stored drive chat rejected: ${it.message}") }
        .getOrDefault(false)

    private suspend fun hasStorageMarker(supergroupId: Long): Boolean = runCatching {
        send(TdApi.GetSupergroupFullInfo(supergroupId)).description.contains(STORAGE_CHAT_MARKER)
    }.getOrDefault(false)

    private suspend fun isOwnPrivateChannel(supergroupId: Long): Boolean = runCatching {
        val supergroup = send(TdApi.GetSupergroup(supergroupId))
        supergroup.status is TdApi.ChatMemberStatusCreator &&
                supergroup.usernames?.activeUsernames.isNullOrEmpty()
    }.getOrDefault(false)

    private suspend fun writeMarker(chatId: Long, description: String) {
        if (description.contains(STORAGE_CHAT_MARKER)) return
        val updated = if (description.isBlank()) {
            STORAGE_CHAT_MARKER
        } else {
            description + MARKER_SEPARATOR + STORAGE_CHAT_MARKER
        }
        runCatching { send(TdApi.SetChatDescription(chatId, updated)) }
            .onSuccess { DriveLog.d(TAG, "Restored drive marker on existing channel") }
            .onFailure { DriveLog.w(TAG, "Could not restore drive marker", it) }
    }

    /**
     * Title search, server-side then local. Cheap enough to retry in a loop:
     * two requests plus a handful of lookups for whatever matched.
     */
    private suspend fun searchStorageChat(): Long? {
        val candidates = LinkedHashSet<Long>()

        runCatching { send(TdApi.SearchChatsOnServer(STORAGE_CHAT_TITLE, CHAT_SEARCH_LIMIT)) }
            .onFailure { DriveLog.d(TAG, "Server chat search failed: ${it.message}") }
            .getOrNull()?.chatIds?.forEach { candidates.add(it) }
        runCatching { send(TdApi.SearchChats(STORAGE_CHAT_TITLE, CHAT_SEARCH_LIMIT)) }
            .onFailure { DriveLog.d(TAG, "Local chat search failed: ${it.message}") }
            .getOrNull()?.chatIds?.forEach { candidates.add(it) }

        if (candidates.isEmpty()) return null
        return matchStorageChat(candidates)
    }

    /**
     * Last resort for an account where the drive channel was renamed, so title
     * search cannot find it. Costs one GetChat per chat, so it runs once and only
     * after the cheap pass has been given several tries.
     */
    private suspend fun scanChatListsForStorageChat(): Long? {
        val candidates = LinkedHashSet<Long>()
        for (chatList in listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
            runCatching { send(TdApi.GetChats(chatList, CHAT_LIST_LIMIT)) }
                .onFailure { DriveLog.d(TAG, "Chat list read failed: ${it.message}") }
                .getOrNull()?.chatIds?.forEach { candidates.add(it) }
        }
        if (candidates.isEmpty()) return null
        DriveLog.d(TAG, "Scanning ${candidates.size} chats for a renamed drive")
        return matchStorageChat(candidates)
    }

    /**
     * Titles come from the local chat cache, but a description needs a full-info
     * request per chat. Filtering by title first keeps that to the handful of
     * channels that can actually be the drive, instead of hundreds of requests
     * that would trip Telegram's flood limits and fail the real match.
     */
    private suspend fun matchStorageChat(chatIds: Collection<Long>): Long? {
        val titled = chatIds.mapNotNull { chatId ->
            runCatching {
                val chat = send(TdApi.GetChat(chatId))
                val type = chat.type as? TdApi.ChatTypeSupergroup ?: return@runCatching null
                if (!type.isChannel || chat.title != STORAGE_CHAT_TITLE) {
                    null
                } else {
                    chatId to type.supergroupId
                }
            }.getOrNull()
        }
        if (titled.isEmpty()) return null

        val marked = titled.filter { (_, supergroupId) -> hasStorageMarker(supergroupId) }
        val usable = marked.ifEmpty {
            titled.filter { (_, supergroupId) -> isOwnPrivateChannel(supergroupId) }
        }
        val chosen = usable.minByOrNull { it.second } ?: return null
        if (marked.isEmpty()) {
            val description = runCatching {
                send(TdApi.GetSupergroupFullInfo(chosen.second)).description
            }.getOrDefault("")
            writeMarker(chosen.first, description)
        }
        return chosen.first
    }

    // ---- Transfers -----------------------------------------------------------

    override fun uploadDocument(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        caption: String,
        thumbnailPath: String?
    ): Flow<TelegramUploadEvent> = callbackFlow<TelegramUploadEvent> {
        val thumbnail = thumbnailPath?.takeIf { File(it).exists() }?.let {
            TdApi.InputThumbnail(TdApi.InputFileLocal(it), THUMBNAIL_EDGE, THUMBNAIL_EDGE)
        }
        val content = TdApi.InputMessageDocument(
            TdApi.InputFileLocal(localPath),
            thumbnail,
            true,
            TdApi.FormattedText(caption, emptyArray())
        )
        val finished = AtomicBoolean(false)
        val pendingId = AtomicReference<Long?>(null)
        val pendingFileId = AtomicReference<Int?>(null)
        val sentMessageId = AtomicReference<Long?>(null)
        val earlyLock = Any()
        val earlyUpdates = mutableListOf<TdApi.Object>()

        fun processUpdate(update: TdApi.Object, expectedId: Long, expectedFileId: Int?) {
            when (update) {
                is TdApi.UpdateFile -> {
                    if (expectedFileId != null && update.file.id == expectedFileId) {
                        val total = update.file.size.takeIf { it > 0 }
                            ?: update.file.expectedSize
                        trySend(
                            TelegramUploadEvent.Progress(
                                update.file.remote?.uploadedSize ?: 0,
                                total
                            )
                        )
                    }
                }

                is TdApi.UpdateMessageSendSucceeded -> {
                    if (update.oldMessageId == expectedId) {
                        sentMessageId.set(update.message.id)
                        val document = update.message.toRemoteDocument()
                        finished.set(true)
                        if (document != null) {
                            trySend(TelegramUploadEvent.Completed(document))
                            close()
                        } else {
                            close(TelegramException(500, "Sent message is not a document"))
                        }
                    }
                }

                is TdApi.UpdateMessageSendFailed -> {
                    if (update.oldMessageId == expectedId) {
                        finished.set(true)
                        close(TelegramException.from(update.error.code, update.error.message))
                    }
                }
            }
        }

        // Subscribe before SendMessage so fast TDLib updates are buffered instead of missed.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            updates.collect { update ->
                val activeId = pendingId.get()
                if (activeId == null) {
                    synchronized(earlyLock) {
                        val lockedId = pendingId.get()
                        if (lockedId == null) {
                            earlyUpdates.add(update)
                        } else {
                            processUpdate(update, lockedId, pendingFileId.get())
                        }
                    }
                } else {
                    processUpdate(update, activeId, pendingFileId.get())
                }
            }
        }

        val pending: TdApi.Message =
            send(TdApi.SendMessage(chatId, null, null, null, null, content))
        val resolvedFileId = (pending.content as? TdApi.MessageDocument)?.document?.document?.id
        sentMessageId.set(pending.id)
        trySend(TelegramUploadEvent.Started(pending.id))

        val buffered = synchronized(earlyLock) {
            pendingFileId.set(resolvedFileId)
            pendingId.set(pending.id)
            earlyUpdates.toList().also { earlyUpdates.clear() }
        }
        buffered.forEach { processUpdate(it, pending.id, resolvedFileId) }

        awaitClose {
            job.cancel()
            val messageToDelete = sentMessageId.get()
            if (!finished.get() && messageToDelete != null) {
                client?.send(TdApi.DeleteMessages(chatId, longArrayOf(messageToDelete), true)) { }
            }
        }
    }.buffer(capacity = 64)

    override fun downloadDocument(remoteFileId: String): Flow<TelegramDownloadEvent> =
        callbackFlow<TelegramDownloadEvent> {
            val file: TdApi.File =
                send(TdApi.GetRemoteFile(remoteFileId, TdApi.FileTypeDocument()))
            file.readyPath()?.let { path ->
                trySend(TelegramDownloadEvent.Completed(path, file.size))
                close()
                awaitClose { }
                return@callbackFlow
            }

            val fileId = file.id
            // TDLib can report a completed local copy whose bytes were already
            // evicted. Dropping the stale record forces a real re-download.
            if (file.local?.isDownloadingCompleted == true) {
                runCatching { send<TdApi.Ok>(TdApi.DeleteFile(fileId)) }
            }

            val sawActive = AtomicBoolean(false)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                updates.filterIsInstance<TdApi.UpdateFile>()
                    .filter { it.file.id == fileId }
                    .collect { update ->
                        if (update.file.local?.isDownloadingActive == true) {
                            sawActive.set(true)
                        }
                        val readyPath = update.file.readyPath()
                        if (readyPath != null) {
                            trySend(TelegramDownloadEvent.Completed(readyPath, update.file.size))
                            close()
                        } else if (sawActive.get() && update.file.local?.isDownloadingActive == false) {
                            close(TelegramException(500, "Download stopped before completion"))
                        } else {
                            trySend(
                                TelegramDownloadEvent.Progress(
                                    update.file.local?.downloadedSize ?: 0,
                                    update.file.size.takeIf { it > 0 } ?: update.file.expectedSize
                                )
                            )
                        }
                    }
            }

            val started: TdApi.File =
                send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, false))
            if (started.local?.isDownloadingActive == true) {
                sawActive.set(true)
            }
            started.readyPath()?.let { path ->
                trySend(TelegramDownloadEvent.Completed(path, started.size))
                close()
            }

            awaitClose {
                job.cancel()
                client?.send(TdApi.CancelDownloadFile(fileId, true)) { }
            }
        }.buffer(capacity = 64)

    override suspend fun discardLocalCopy(remoteFileId: String) {
        runCatching {
            val file = send(TdApi.GetRemoteFile(remoteFileId, TdApi.FileTypeDocument()))
            send<TdApi.Ok>(TdApi.DeleteFile(file.id))
        }
    }

    // ---- Message metadata ----------------------------------------------------

    override suspend fun fetchDocuments(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): RemoteDocumentPage {
        awaitAuthorized()
        if (fromMessageId == 0L) {
            runCatching { send<TdApi.Ok>(TdApi.OpenChat(chatId)) }
        }

        var found = searchMessages(chatId, fromMessageId, limit)
        var attempts = 0
        while (fromMessageId == 0L && found.messages.isEmpty() && attempts++ < HISTORY_RETRIES) {
            delay(HISTORY_RETRY_MS.milliseconds)
            found = searchMessages(chatId, fromMessageId, limit)
        }

        val documents = found.messages.mapNotNull { it.toRemoteDocument() }
        val oldest = found.messages.lastOrNull()?.id ?: 0L
        DriveLog.d(
            TAG,
            "Page from=$fromMessageId messages=${found.messages.size} " +
                    "mapped=${documents.size} next=$oldest"
        )
        return RemoteDocumentPage(
            documents = documents,
            nextFromMessageId = if (found.messages.isEmpty()) 0L else oldest
        )
    }

    private suspend fun searchMessages(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): TdApi.FoundChatMessages = send(
        TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, 0, limit, null)
    )

    override suspend fun getDocument(chatId: Long, messageId: Long): RemoteDocument? =
        runCatching { send(TdApi.GetMessage(chatId, messageId)).toRemoteDocument() }.getOrNull()

    override suspend fun editCaption(chatId: Long, messageId: Long, caption: String) {
        withRateLimitRetry {
            send(
                TdApi.EditMessageCaption(
                    chatId,
                    messageId,
                    null,
                    TdApi.FormattedText(caption, emptyArray()),
                    false
                )
            )
        }
    }

    override suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        messageIds.chunked(DELETE_BATCH).forEach { batch ->
            withRateLimitRetry {
                send(TdApi.DeleteMessages(chatId, batch.toLongArray(), true))
            }
        }
    }

    private suspend fun <T> withRateLimitRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: TelegramException) {
                if (!e.isRateLimit || attempt++ >= RATE_LIMIT_RETRIES) throw e
                delay((((e.retryAfterSeconds ?: RATE_LIMIT_FALLBACK_SECONDS) + 1) * 1000L).milliseconds)
            }
        }
    }

    // ---- TdApi mapping -------------------------------------------------------

    private fun TdApi.Message.toRemoteDocument(): RemoteDocument? = when (val body = content) {
        is TdApi.MessageDocument -> remoteDocument(
            file = body.document.document,
            fileName = body.document.fileName,
            mimeType = body.document.mimeType,
            caption = body.caption?.text.orEmpty()
        )

        is TdApi.MessageAudio -> remoteDocument(
            file = body.audio.audio,
            fileName = body.audio.fileName,
            mimeType = body.audio.mimeType,
            caption = body.caption?.text.orEmpty()
        )

        is TdApi.MessageVideo -> remoteDocument(
            file = body.video.video,
            fileName = body.video.fileName,
            mimeType = body.video.mimeType,
            caption = body.caption?.text.orEmpty()
        )

        is TdApi.MessagePhoto -> body.photo.sizes.maxByOrNull { it.photo.size }?.let { size ->
            remoteDocument(
                file = size.photo,
                fileName = "photo_$id.jpg",
                mimeType = "image/jpeg",
                caption = body.caption?.text.orEmpty()
            )
        }

        else -> null
    }

    private fun TdApi.Message.remoteDocument(
        file: TdApi.File,
        fileName: String,
        mimeType: String,
        caption: String
    ): RemoteDocument? {
        val remote = file.remote ?: return null
        if (remote.id.isNullOrEmpty()) return null
        return RemoteDocument(
            chatId = chatId,
            messageId = id,
            remoteFileId = remote.id,
            uniqueFileId = remote.uniqueId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = file.size,
            caption = caption,
            dateSeconds = date
        )
    }

    private fun TdApi.AuthenticationCodeType.toChannel(): CodeDeliveryChannel = when (this) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> CodeDeliveryChannel.TELEGRAM_APP
        is TdApi.AuthenticationCodeTypeSms -> CodeDeliveryChannel.SMS
        is TdApi.AuthenticationCodeTypeSmsWord -> CodeDeliveryChannel.SMS_WORD
        is TdApi.AuthenticationCodeTypeSmsPhrase -> CodeDeliveryChannel.SMS_PHRASE
        is TdApi.AuthenticationCodeTypeCall -> CodeDeliveryChannel.CALL
        is TdApi.AuthenticationCodeTypeFlashCall -> CodeDeliveryChannel.FLASH_CALL
        is TdApi.AuthenticationCodeTypeMissedCall -> CodeDeliveryChannel.MISSED_CALL
        is TdApi.AuthenticationCodeTypeFragment -> CodeDeliveryChannel.FRAGMENT
        is TdApi.AuthenticationCodeTypeFirebaseAndroid -> CodeDeliveryChannel.FIREBASE
        else -> CodeDeliveryChannel.OTHER
    }

    private fun TdApi.AuthenticationCodeType.codeLength(): Int? = when (this) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> length
        is TdApi.AuthenticationCodeTypeSms -> length
        is TdApi.AuthenticationCodeTypeCall -> length
        is TdApi.AuthenticationCodeTypeMissedCall -> length
        is TdApi.AuthenticationCodeTypeFragment -> length
        is TdApi.AuthenticationCodeTypeFirebaseAndroid -> length
        else -> null
    }

    companion object {
        private const val TAG = "TdLibTelegramClient"
        private const val CLIENT_WAIT_LIMIT_MS = 5_000
        private const val CLIENT_WAIT_STEP_MS = 10L
        private const val DOWNLOAD_PRIORITY = 16
        private const val THUMBNAIL_EDGE = 320
        private const val DELETE_BATCH = 100
        private const val RATE_LIMIT_RETRIES = 5
        private const val RATE_LIMIT_FALLBACK_SECONDS = 5
        private val MARKER_SEPARATOR = System.lineSeparator()
        private const val DISCOVERY_ATTEMPTS = 4
        private const val DISCOVERY_RETRY_MS = 1_000L
        private const val AUTH_WAIT_MS = 30_000L
        private const val CHAT_SEARCH_LIMIT = 50
        private const val CHAT_LIST_LIMIT = 500
        private const val HISTORY_RETRIES = 3
        private const val HISTORY_RETRY_MS = 400L
        private const val CLOSE_WAIT_LIMIT_MS = 5_000L

        /** Distinct from TeleDrive's, so both apps can coexist on one account. */
        const val STORAGE_CHAT_TITLE = "Ortho Tips Drive"
        const val STORAGE_CHAT_MARKER = "#orthotips-drive"
    }
}

/**
 * A download only counts as ready when the bytes are still on disk. TDLib keeps
 * reporting a completed local copy after the file has been evicted from its cache
 * directory, which otherwise surfaces as a download that finishes instantly and
 * then cannot be found.
 */
private fun TdApi.File.readyPath(): String? {
    val local = this.local ?: return null
    if (!local.isDownloadingCompleted) return null
    val path = local.path
    if (path.isNullOrEmpty()) return null
    return path.takeIf { File(it).exists() }
}
