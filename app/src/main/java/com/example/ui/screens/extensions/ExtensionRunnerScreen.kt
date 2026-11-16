package com.example.ui.screens.extensions

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.provider.Settings
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.BuildConfig
import com.example.R
import android.Manifest
import android.os.Build
import com.example.data.sms.BankSmsInbox
import com.example.data.extensions.ExtensionDatabaseAccess
import com.example.data.extensions.ExtensionFiles
import com.example.data.extensions.ExtensionPermission
import com.example.data.extensions.ExtensionRepository
import com.example.data.extensions.ExtensionStorage
import com.example.data.extensions.OrthoExtension
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "OrthoExtension"
private const val MAX_CONSOLE_LINES = 200
private const val BACK_JS =
    "(function(){try{return !!(window.OrthoTips&&OrthoTips.__handleBack())}catch(e){return false}})()"

private sealed interface RunnerContent {
    data object Loading : RunnerContent
    data object Missing : RunnerContent
    data class Ready(val extension: OrthoExtension, val page: String) : RunnerContent
}

internal data class ConsoleLine(val isError: Boolean, val text: String)

/** State shared between Compose and the WebView callbacks, which outlive recompositions. */
private class RunnerHost {
    @Volatile var theme: String = "light"
    @Volatile var language: String = "fr"
    var webView: WebView? = null
    var bridge: ExtensionBridge? = null
    var fileCallback: ValueCallback<Array<Uri>>? = null
    var pendingSave: PendingSave? = null
    var pendingNotificationRequest: String? = null
    var notificationAskedAt = 0L
    var awaitingNotificationSettings = false
    var pendingSmsRequest: String? = null
    val console = mutableStateListOf<ConsoleLine>()

    /** Answers the page's file input exactly once; later answers are ignored. */
    fun answerFile(value: Array<Uri>?) {
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(value)
    }

    fun resolve(requestId: String, value: Boolean) {
        webView?.evaluateJavascript(
            "window.OrthoTips&&OrthoTips.__resolve(${JSONObject.quote(requestId)},$value)",
            null
        )
    }

    /** Settles a promise from `OrthoTipsNative.call`; [value] is a JSON literal when [ok]. */
    fun settle(requestId: String, ok: Boolean, value: String) {
        val payload = if (ok) value else JSONObject.quote(value)
        webView?.evaluateJavascript(
            "window.OrthoTips&&OrthoTips.__settle(${JSONObject.quote(requestId)},$ok,$payload)",
            null
        )
    }

    fun log(line: ConsoleLine) {
        console.add(line)
        while (console.size > MAX_CONSOLE_LINES) console.removeAt(0)
    }
}

private class PendingSave(val requestId: String, val bytes: ByteArray)

/**
 * Runs one HTML extension full screen. The page is the extension's own HTML
 * with the host bridge injected in front; it runs in its own origin and only
 * reaches the app through `window.OrthoTips`, limited to the permissions the
 * extension was granted (extension guide, section 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionRunnerScreen(
    repository: ExtensionRepository,
    databaseAccess: ExtensionDatabaseAccess,
    extensionId: String,
    onNavigateBack: () -> Unit,
    onOpenGeminiSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val background = MaterialTheme.colorScheme.background
    val theme = if (background.luminance() < 0.5f) "dark" else "light"
    val language = LocalConfiguration.current.locales[0]?.language ?: "fr"
    val currentOnBack by rememberUpdatedState(onNavigateBack)

    val host = remember {
        RunnerHost().also {
            it.theme = theme
            it.language = language
        }
    }
    SideEffect {
        host.theme = theme
        host.language = language
    }

    val content by produceState<RunnerContent>(RunnerContent.Loading, extensionId) {
        val extension = repository.get(extensionId)
        val page = extension?.let { repository.loadPage(extensionId) }
        value = if (extension != null && page != null) RunnerContent.Ready(extension, page) else RunnerContent.Missing
    }

    // <input type="file"> in the page.
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        host.answerFile(uri?.let { arrayOf(it) })
    }

    // <input type="file" accept="image/*" capture> in the page: the camera. The photo's
    // path survives the activity being recreated; a result for a page that is gone
    // (its input was already answered) is simply dropped.
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFile?.let(::File)
        cameraFile = null
        if (saved && file != null && file.length() > 0) {
            host.answerFile(arrayOf(ExtensionCamera.uriFor(context, file)))
        } else {
            file?.delete()
            host.answerFile(null)
        }
    }
    val launchCamera: () -> Unit = {
        try {
            val file = ExtensionCamera.newCaptureFile(context)
            cameraFile = file.path
            takePicture.launch(ExtensionCamera.uriFor(context, file))
        } catch (e: ActivityNotFoundException) {
            cameraFile?.let { File(it).delete() }
            cameraFile = null
            // No camera app: the picker, images only.
            if (runCatching { openFile.launch(arrayOf("image/*")) }.isFailure) host.answerFile(null)
        } catch (e: Exception) {
            Log.w(TAG, "Camera could not start", e)
            cameraFile = null
            host.answerFile(null)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The page was rebuilt meanwhile (e.g. rotation): its input was already answered.
        if (host.fileCallback == null) return@rememberLauncherForActivityResult
        if (granted) {
            launchCamera()
        } else {
            host.answerFile(null)
            Toast.makeText(context, R.string.ext_camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    /** Always answers the input itself (now or later), so it reports handled. */
    val startCamera: () -> Boolean = {
        if (ExtensionCamera.needsCameraPermission(context)) {
            if (runCatching { cameraPermission.launch(Manifest.permission.CAMERA) }.isFailure) host.answerFile(null)
        } else {
            launchCamera()
        }
        true
    }

    // OrthoTips.saveFile and <a download> in the page.
    val createFile = rememberLauncherForActivityResult(CreateDocumentWithType()) { uri ->
        val pending = host.pendingSave ?: return@rememberLauncherForActivityResult
        host.pendingSave = null
        if (uri == null) {
            host.resolve(pending.requestId, false)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(pending.bytes) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                if (ok) R.string.ext_file_saved else R.string.ext_file_save_failed,
                Toast.LENGTH_SHORT
            ).show()
            host.resolve(pending.requestId, ok)
        }
    }

    // Bank SMS (`sms` permission): Android's permission dialog, and live delivery.
    val smsInbox = remember { BankSmsInbox(context) }
    val smsPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val id = host.pendingSmsRequest ?: return@rememberLauncherForActivityResult
        host.pendingSmsRequest = null
        host.settle(id, true, smsInbox.status().toString())
    }
    LaunchedEffect(smsInbox) {
        BankSmsInbox.arrivals.collect {
            host.webView?.evaluateJavascript("window.dispatchEvent(new Event('orthotips:sms'))", null)
        }
    }

    // Notifications (`notifications` permission): Android's dialog, or its settings
    // screen once it will not ask again; answered when the user comes back.
    val reminders = remember { (context.applicationContext as com.example.OrthoApplication).appModule.extensionReminders }
    val answerNotificationRequest = {
        host.pendingNotificationRequest?.let { requestId ->
            host.pendingNotificationRequest = null
            host.awaitingNotificationSettings = false
            host.settle(requestId, true, reminders.status().toString())
        }
    }
    val openNotificationSettings = {
        host.awaitingNotificationSettings = true
        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(settings) }.isFailure) answerNotificationRequest()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        // Answered in a blink without a dialog: Android will not ask any more.
        val noDialog = System.currentTimeMillis() - host.notificationAskedAt < 400
        if (!granted && noDialog && Build.VERSION.SDK_INT >= 33 && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) openNotificationSettings() else answerNotificationRequest()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && host.awaitingNotificationSettings) answerNotificationRequest()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A tapped notification of this extension while it is open → onOpen(…).
    DisposableEffect(extensionId) {
        val handler: (ExtensionNotificationRouter.Tap) -> Unit = { tap ->
            host.webView?.evaluateJavascript(
                "window.OrthoTips&&OrthoTips.__notificationOpened(${JSONObject.quote(tap.id)},${tap.data?.let(JSONObject::quote) ?: "null"})",
                null
            )
        }
        ExtensionNotificationRouter.register(extensionId, handler)
        onDispose { ExtensionNotificationRouter.unregister(extensionId, handler) }
    }
    // Set when this opening comes from tapping one of its notifications; given to info() once.
    val launch = remember(extensionId) { ExtensionNotificationRouter.takeLaunch(extensionId) }

    // The app's theme changed while the extension is open.
    LaunchedEffect(theme) {
        host.webView?.evaluateJavascript("window.OrthoTips&&OrthoTips.__setTheme('$theme')", null)
    }

    // Back goes to the extension first (it may have its own views), then closes it.
    BackHandler {
        val view = host.webView
        if (view == null) {
            currentOnBack()
        } else {
            view.evaluateJavascript(BACK_JS) { result -> if (result != "true") currentOnBack() }
        }
    }

    var consoleOpen by remember { mutableStateOf(false) }
    val ready = content as? RunnerContent.Ready

    // Full screen: no app bar, only a small floating back button over the page
    // (long press: Reload, Console). The page starts below the status bar.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            ExtensionBackButton(
                errorCount = host.console.count { it.isError },
                menuEnabled = ready != null,
                onBack = onNavigateBack,
                onReload = {
                    ready?.let { r ->
                        host.console.clear()
                        host.webView?.loadDataWithBaseURL(ExtensionRepository.baseUrl(r.extension.id), r.page, "text/html", "UTF-8", null)
                    }
                },
                onConsole = { consoleOpen = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .zIndex(1f)
            )
            when (val state = content) {
                RunnerContent.Loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                RunnerContent.Missing ->
                    Text(
                        text = stringResource(R.string.ext_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )

                is RunnerContent.Ready -> {
                    val backgroundArgb = background.toArgb()
                    AndroidView(
                        factory = { viewContext ->
                            createExtensionWebView(
                                context = viewContext,
                                extension = state.extension,
                                page = state.page,
                                host = host,
                                backgroundArgb = backgroundArgb,
                                bridge = ExtensionBridge(
                                    storage = ExtensionStorage(viewContext, state.extension.id),
                                    granted = state.extension.granted,
                                    files = repository.privateFilesDir(state.extension.id)?.let(::ExtensionFiles),
                                    contentDir = repository.contentDir(state.extension.id),
                                    database = databaseAccess,
                                    infoJson = {
                                        JSONObject()
                                            .put("bridgeVersion", ExtensionBridge.BRIDGE_VERSION)
                                            .put("extensionId", state.extension.id)
                                            .put("extensionVersion", state.extension.version)
                                            .put("appVersion", BuildConfig.VERSION_NAME)
                                            .put("language", host.language)
                                            .put("theme", host.theme)
                                            .put(
                                                "permissions",
                                                JSONArray(ExtensionPermission.keys(state.extension.granted.filter { it.implemented }))
                                            )
                                            .put("launch", launch ?: JSONObject.NULL)
                                            .toString()
                                    },
                                    onToast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                                    onClose = { currentOnBack() },
                                    onSaveFile = { requestId, fileName, mimeType, bytes ->
                                        host.pendingSave?.let { host.resolve(it.requestId, false) }
                                        if (bytes == null) {
                                            host.pendingSave = null
                                            host.resolve(requestId, false)
                                        } else {
                                            host.pendingSave = PendingSave(requestId, bytes)
                                            runCatching {
                                                createFile.launch(CreateDocumentWithType.Input(fileName, mimeType))
                                            }.onFailure {
                                                host.pendingSave = null
                                                host.resolve(requestId, false)
                                            }
                                        }
                                    },
                                    onSettle = host::settle,
                                    onDenied = { host.log(ConsoleLine(isError = true, text = it)) },
                                    gemini = if (state.extension.has(ExtensionPermission.GEMINI)) {
                                        val service = (context.applicationContext as com.example.OrthoApplication).appModule.geminiService
                                        object : ExtensionGeminiAccess {
                                            override fun status(): JSONObject = JSONObject()
                                                .put("available", true)
                                                .put("configured", service.hasKey())
                                                .put("model", if (service.hasKey()) service.currentModel() else JSONObject.NULL)
                                            override fun generate(args: JSONObject): JSONObject = kotlinx.coroutines.runBlocking {
                                                val result = service.generateRaw(
                                                    args.optJSONObject("body") ?: JSONObject(),
                                                    args.optString("model").takeIf { it.isNotBlank() }
                                                )
                                                JSONObject().put("model", result.model).put("response", JSONObject(result.text))
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    onOpenGeminiSettings = onOpenGeminiSettings,
                                    media = if (state.extension.has(ExtensionPermission.BACKGROUND)) {
                                        ExtensionMedia(context, state.extension.id) { json ->
                                            val view = host.webView
                                            view?.post {
                                                view.evaluateJavascript("window.OrthoTips&&OrthoTips.__mediaChanged(" + JSONObject.quote(json) + ")", null)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    patients = if (state.extension.has(ExtensionPermission.PATIENTS)) {
                                        val records = (context.applicationContext as com.example.OrthoApplication).appModule.extensionPatients
                                        object : ExtensionPatientAccess {
                                            override fun search(query: String, limit: Int) = records.search(query, limit)
                                            override fun get(id: String) = records.get(id)
                                            override fun getByCode(code: String) = records.getByCode(code)
                                            override fun create(fields: JSONObject) = records.create(fields)
                                            override fun update(id: String, fields: JSONObject) = records.update(id, fields)
                                            override fun addMedia(patientId: String, args: JSONObject) = records.addMedia(patientId, args)
                                        }
                                    } else {
                                        null
                                    },
                                    notifications = if (state.extension.has(ExtensionPermission.NOTIFICATIONS)) {
                                        val id = state.extension.id
                                        object : ExtensionNotificationAccess {
                                            override fun status() = reminders.status()
                                            override fun schedule(args: JSONObject) = reminders.schedule(id, args)
                                            override fun cancel(id2: String) = reminders.cancel(id, id2)
                                            override fun cancelAll() = reminders.cancelAll(id)
                                            override fun list() = reminders.list(id)
                                        }
                                    } else {
                                        null
                                    },
                                    onRequestNotificationAccess = { requestId ->
                                        host.pendingNotificationRequest = requestId
                                        when {
                                            reminders.notificationsEnabled() -> answerNotificationRequest()
                                            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                                context, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED -> {
                                                host.notificationAskedAt = System.currentTimeMillis()
                                                runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                                    .onFailure { answerNotificationRequest() }
                                            }
                                            // Allowed but switched off (or the channel blocked): only settings can fix it.
                                            Build.VERSION.SDK_INT >= 26 -> openNotificationSettings()
                                            else -> answerNotificationRequest()
                                        }
                                    },
                                    sms = if (state.extension.has(ExtensionPermission.SMS)) {
                                        val id = state.extension.id
                                        object : ExtensionSmsAccess {
                                            override fun status() = smsInbox.status()
                                            override fun pending() = smsInbox.pending(id)
                                            override fun ack(upTo: Long) = smsInbox.ack(id, upTo)
                                            override fun history() = smsInbox.history()
                                        }
                                    } else {
                                        null
                                    },
                                    onRequestSmsAccess = { requestId ->
                                        host.pendingSmsRequest = requestId
                                        val wanted = buildList {
                                            add(Manifest.permission.RECEIVE_SMS)
                                            add(Manifest.permission.READ_SMS)
                                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        runCatching { smsPermissions.launch(wanted.toTypedArray()) }
                                            .onFailure {
                                                host.pendingSmsRequest = null
                                                host.settle(requestId, true, smsInbox.status().toString())
                                            }
                                    }
                                ),
                                onChooseFile = { request ->
                                    // <input type="file"> is part of import_export (guide, section 4.1).
                                    if (state.extension.has(ExtensionPermission.IMPORT_EXPORT)) {
                                        if (request.camera) startCamera()
                                        else runCatching { openFile.launch(request.mimeTypes.toTypedArray()) }.isSuccess
                                    } else {
                                        host.log(ConsoleLine(isError = true, text = "PermissionError: 'import_export' not granted"))
                                        false
                                    }
                                }
                            )
                        },
                        onRelease = { view ->
                            // A page that goes away never leaves its file input waiting.
                            host.answerFile(null)
                            if (host.webView === view) host.webView = null
                            host.bridge?.dispose()
                            host.bridge = null
                            view.stopLoading()
                            view.destroy()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (consoleOpen) {
        ConsoleDialog(
            lines = host.console,
            onClear = { host.console.clear() },
            onDismiss = { consoleOpen = false }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun createExtensionWebView(
    context: Context,
    extension: OrthoExtension,
    page: String,
    host: RunnerHost,
    backgroundArgb: Int,
    bridge: ExtensionBridge,
    onChooseFile: (FileRequest) -> Boolean
): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = true // files picked through <input type="file">
        // Without `internet`, requests fail as they would offline (guide, section 4.4).
        blockNetworkLoads = !extension.allowNetwork
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
        setSupportMultipleWindows(false)
        // The extension draws its own light and dark themes; never repaint it.
        if (Build.VERSION.SDK_INT >= 33) {
            isAlgorithmicDarkeningAllowed = false
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            forceDark = WebSettings.FORCE_DARK_OFF
        }
    }
    setBackgroundColor(backgroundArgb)
    host.bridge = bridge
    addJavascriptInterface(bridge, "OrthoTipsNative")

    val ownHost = Uri.parse(ExtensionRepository.baseUrl(extension.id)).host

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            // An extension is one page; navigating inside its own origin would
            // leave the loaded document for nothing.
            if (url.host == ownHost) return true
            if (url.scheme in EXTERNAL_SCHEMES) {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No app to open $url")
                }
            }
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            host.answerFile(null)   // a previous input still waiting
            host.fileCallback = filePathCallback
            val accept = fileChooserParams?.acceptTypes
            val request = FileRequest(
                mimeTypes = pickerMimeTypes(accept).toList(),
                camera = ExtensionCamera.wantsCamera(fileChooserParams?.isCaptureEnabled == true, accept)
            )
            if (!onChooseFile(request)) host.answerFile(null)
            return true
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val isError = message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
            val text = "${message.message()}  (ligne ${message.lineNumber()})"
            host.log(ConsoleLine(isError, text))
            if (isError) Log.w(TAG, "[${extension.id}] $text") else Log.d(TAG, "[${extension.id}] $text")
            return true
        }
    }

    host.webView = this
    loadDataWithBaseURL(ExtensionRepository.baseUrl(extension.id), page, "text/html", "UTF-8", null)
}

private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel", "geo")

/**
 * Only media filters are passed to the picker: a .json or .csv file often
 * reports itself as application/octet-stream, and a strict filter would hide it.
 */
private fun pickerMimeTypes(accept: Array<String>?): Array<String> {
    val types = accept.orEmpty()
        .flatMap { it.split(',') }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
    val media = types.filter { it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/") }
    return if (types.isNotEmpty() && media.size == types.size) media.toTypedArray() else arrayOf("*/*")
}

@Composable
private fun ConsoleDialog(
    lines: List<ConsoleLine>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_console)) },
        text = {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.ext_console_empty))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(lines) { line ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (line.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_close)) }
        },
        dismissButton = {
            if (lines.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.ext_clear_console)) }
            }
        }
    )
}

/** The Activity behind a Compose context, for permission rationale checks. */
private fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * The only control the app draws over a full-screen extension: a small round
 * back button in the top-left corner (40 dp, 8 dp from the edges; the guide asks
 * extensions to keep that corner clear). Tap: leave the extension. Long press:
 * Reload and Console. A red dot means the console has errors.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtensionBackButton(
    errorCount: Int,
    menuEnabled: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onConsole: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f))
                .combinedClickable(
                    onClick = onBack,
                    onClickLabel = stringResource(R.string.ext_back),
                    onLongClick = {
                        if (menuEnabled) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                    onLongClickLabel = stringResource(R.string.ext_more)
                )
                .testTag("extension_back")
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.ext_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
        if (errorCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ext_reload)) },
                onClick = { menuOpen = false; onReload() }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (errorCount > 0) stringResource(R.string.ext_console_errors, errorCount)
                        else stringResource(R.string.ext_console)
                    )
                },
                onClick = { menuOpen = false; onConsole() }
            )
        }
    }
}
