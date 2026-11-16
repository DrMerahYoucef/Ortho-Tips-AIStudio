package com.example

import android.net.Uri
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.utils.AppLock
import com.example.utils.currentFragmentActivity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.prefs.FontSizePreference
import com.example.data.prefs.ThemeMode
import com.example.di.AppModule
import com.example.util.AppLocale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.example.navigation.OrthoDestinations
import com.example.ui.screens.bone.BoneDetailScreen
import com.example.ui.screens.activation.ActivationScreen
import com.example.ui.screens.bone.BoneListScreen
import com.example.ui.screens.classifications.ClassificationChapterDetailRoute
import com.example.ui.screens.extensions.ExtensionImportScreen
import com.example.ui.screens.extensions.ExtensionRunnerScreen
import com.example.ui.screens.importer.ImportScreen
import com.example.ui.screens.main.MainScreen
import com.example.ui.screens.patients.ImageAnnotationScreen
import com.example.ui.screens.patients.PatientCameraCaptureScreen
import com.example.ui.screens.patients.PatientDataTransferScreen
import com.example.ui.screens.patients.PatientDetailScreen
import com.example.ui.screens.patients.PatientEditScreen
import com.example.ui.screens.patients.QrScannerScreen
import com.example.ui.screens.patients.SurgeryEditScreen
import com.example.ui.screens.patients.SurgeonProfileScreen
import com.example.ui.screens.patients.TimelineEventEditScreen
import com.example.ui.screens.favorites.FavoritesScreen
import com.example.ui.screens.search.SearchScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.viewmodel.SavedViewModel
import com.example.viewmodel.SearchViewModel
import com.example.viewmodel.SettingsViewModel
import com.example.ui.screens.storage.StorageDiagnosticsScreen
import com.example.ui.screens.storage.TelegramDriveSettingsScreen
import com.example.ui.screens.storage.TelegramSetupWizardScreen
import com.example.ui.screens.viewer.ImageViewerScreen
import com.example.ui.theme.OrthoTipsTheme
import com.example.utils.ProtocolLanguage
import com.example.utils.rememberDocumentScannerLauncher
import com.example.data.local.entity.ImageCategory
import com.example.viewmodel.AppViewModel
import com.example.viewmodel.BoneDetailViewModel
import com.example.viewmodel.ClassificationViewModel
import com.example.viewmodel.ExtensionImportViewModel
import com.example.viewmodel.ClassificationDocumentViewModel
import com.example.viewmodel.ClassificationDraftViewModel
import com.example.viewmodel.ClassificationEditorHomeViewModel
import com.example.ui.screens.classifications.ClassificationDocumentScreen
import com.example.ui.screens.classifications.ClassificationDraftScreen
import com.example.ui.screens.classifications.ClassificationEditorHomeScreen
import com.example.viewmodel.ImageAnnotationViewModel
import com.example.viewmodel.ImportViewModel
import com.example.viewmodel.MediaCaptureViewModel
import com.example.viewmodel.OperativeProtocolViewModel
import com.example.viewmodel.PatientDataTransferViewModel
import com.example.viewmodel.PatientDetailViewModel
import com.example.viewmodel.PatientEditViewModel
import com.example.viewmodel.SurgeonProfileViewModel
import com.example.viewmodel.TelegramDriveSettingsViewModel
import com.example.viewmodel.TelegramSetupViewModel
import com.example.viewmodel.SurgeryEditViewModel
import com.example.viewmodel.TimelineEventEditViewModel
import com.example.viewmodel.ViewerViewModel

class MainActivity : androidx.fragment.app.FragmentActivity() {

    /**
     * Applies the saved language before any resource is read.
     *
     * This runs before the first frame, which is why [AppLocale] keeps the
     * setting in SharedPreferences rather than the app's DataStore — an async
     * read here would render the first screen in the wrong language and then
     * flicker when the real value arrived.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // An extension's notification tapped while the app is running.
        com.example.ui.screens.extensions.ExtensionNotificationRouter.post(intent)
        postWidgetOpenIfPresent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appModule = (application as OrthoApplication).appModule
        // Opened by tapping an extension's notification or a widget appointment.
        if (savedInstanceState == null) {
            com.example.ui.screens.extensions.ExtensionNotificationRouter.post(intent)
            postWidgetOpenIfPresent(intent)
        }

        // The home-screen widget cannot read the DataStore in time, so hand it the
        // theme choice each time it changes (and once at launch for older installs).
        lifecycleScope.launch {
            appModule.settingsRepository.settingsFlow
                .map { it.themeMode }
                .distinctUntilChanged()
                .collect { com.example.widget.WidgetTheme.setMode(applicationContext, it) }
        }

        setContent {
            val appViewModel: AppViewModel = viewModel(
                factory = AppViewModel.provideFactory(appModule.settingsRepository)
            )
            val settings by appViewModel.settings.collectAsStateWithLifecycle()
            val isActivated by appModule.activationRepository.isActivated.collectAsStateWithLifecycle(initialValue = null)

            val isDarkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val fontMultiplier = when (settings.fontSize) {
                FontSizePreference.STANDARD -> 1.0f
                FontSizePreference.LARGE -> 1.15f
                FontSizePreference.EXTRA_LARGE -> 1.30f
            }

            // OR mode keeps the screen awake. Applied here, at the root, so it holds
            // from launch; it used to take effect only once Settings was opened.
            LaunchedEffect(settings.operativeMode) {
                if (settings.operativeMode) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val currentDensity = LocalDensity.current
            val adjustedDensity = Density(
                density = currentDensity.density,
                fontScale = currentDensity.fontScale * fontMultiplier
            )

            CompositionLocalProvider(LocalDensity provides adjustedDensity) {
                OrthoTipsTheme(
                    darkTheme = isDarkTheme,
                    dynamicColor = settings.dynamicColor
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (isActivated) {
                            true -> AppLockGate(enabled = settings.appLockEnabled) {
                                OrthoAppNavHost(appModule = appModule)
                            }
                            false -> ActivationScreen(
                                activationRepository = appModule.activationRepository,
                                onActivated = { }
                            )
                            null -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun postWidgetOpenIfPresent(intent: Intent?) {
        val eventId = intent?.getStringExtra(com.example.widget.OrthoWidgetProvider.EXTRA_OPEN_EVENT)
        val patientId = intent?.getStringExtra(com.example.widget.OrthoWidgetProvider.EXTRA_PATIENT_ID)
        if (!eventId.isNullOrBlank() && !patientId.isNullOrBlank()) {
            intent.removeExtra(com.example.widget.OrthoWidgetProvider.EXTRA_OPEN_EVENT)
            intent.removeExtra(com.example.widget.OrthoWidgetProvider.EXTRA_PATIENT_ID)
            pendingWidgetOpen.value = patientId to eventId
        }
    }

    companion object {
        internal val pendingWidgetOpen = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    }
}

/**
 * Holds the app behind the phone's own lock (fingerprint, face, PIN, pattern).
 * Asks once per launch, and again whenever the app comes back from the background,
 * so a phone left unlocked on a desk does not expose patient data.
 */
@Composable
private fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    val activity = currentFragmentActivity()
    val context = LocalContext.current
    val needsLock = enabled && activity != null && AppLock.isAvailable(context)

    var unlocked by remember { mutableStateOf(!needsLock || AppLock.sessionUnlocked) }
    var prompting by remember { mutableStateOf(false) }
    var leftAt by remember { mutableStateOf(0L) }

    fun ask() {
        val host = activity ?: return
        if (prompting) return
        prompting = true
        AppLock.authenticate(
            activity = host,
            title = context.getString(R.string.app_lock_title),
            subtitle = context.getString(R.string.app_lock_subtitle)
        ) { success ->
            prompting = false
            AppLock.sessionUnlocked = success
            unlocked = success
        }
    }

    // Locking the phone always locks the app, however briefly the user was away.
    DisposableEffect(needsLock) {
        if (!needsLock) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                AppLock.sessionUnlocked = false
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, needsLock) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> leftAt = System.currentTimeMillis()
                Lifecycle.Event.ON_RESUME -> {
                    if (needsLock) {
                        // Short trips out of the app — the camera, the gallery picker,
                        // a quick glance at another app — keep the session open.
                        val awayFor = if (leftAt == 0L) 0L else System.currentTimeMillis() - leftAt
                        if (!AppLock.sessionUnlocked || awayFor > AppLock.GRACE_MS) {
                            AppLock.sessionUnlocked = false
                            unlocked = false
                            // Asking here, not in a LaunchedEffect: the prompt can only
                            // attach once the activity is actually resumed.
                            ask()
                        } else {
                            unlocked = true
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!needsLock || unlocked) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_lock_locked),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { ask() }) {
                    Text(stringResource(R.string.app_lock_unlock))
                }
            }
        }
    }
}

@Composable
fun OrthoAppNavHost(appModule: AppModule) {
    val navController = rememberNavController()

    // The widget's note button asks for one appointment; open it once the graph exists.
    // A tapped extension notification: to the open extension, or open it with `launch`.
    LaunchedEffect(navController) {
        com.example.ui.screens.extensions.ExtensionNotificationRouter.pending.collect { tap ->
            if (!com.example.ui.screens.extensions.ExtensionNotificationRouter.deliverToOpen(tap)) {
                com.example.ui.screens.extensions.ExtensionNotificationRouter.setLaunch(tap)
                navController.navigate(OrthoDestinations.extensionRunner(tap.extensionId))
            }
        }
    }
    val widgetTarget by MainActivity.pendingWidgetOpen.collectAsStateWithLifecycle()
    LaunchedEffect(navController, widgetTarget) {
        val (patientId, eventId) = widgetTarget ?: return@LaunchedEffect
        MainActivity.pendingWidgetOpen.value = null
        navController.navigate(OrthoDestinations.timelineEventEdit(patientId, eventId))
    }
    val hostActivity = currentFragmentActivity()
    LaunchedEffect(hostActivity) {
        val intent = hostActivity?.intent ?: return@LaunchedEffect
        // A bank SMS notification opens the extension that handles it.
        intent.getStringExtra(com.example.data.sms.BankSmsInbox.EXTRA_OPEN_EXTENSION)?.let { extensionId ->
            intent.removeExtra(com.example.data.sms.BankSmsInbox.EXTRA_OPEN_EXTENSION)
            navController.navigate(OrthoDestinations.extensionRunner(extensionId))
        }
    }

    NavHost(
        navController = navController,
        // No in-app splash: it held every cold start for two seconds. Android's own
        // launch screen (icon on the window background) covers the brief load.
        startDestination = OrthoDestinations.MAIN
    ) {

        composable(OrthoDestinations.MAIN) { entry ->
            MainScreen(
                appModule = appModule,
                onNavigateToChapter = { chapterId, chapterTitle ->
                    navController.navigateFrom(entry, OrthoDestinations.chapterBones(chapterId, chapterTitle))
                },
                onNavigateToBoneDetail = { boneId ->
                    navController.navigateFrom(entry, OrthoDestinations.boneDetail(boneId))
                },
                onNavigateToImport = {
                    navController.navigateFrom(entry, OrthoDestinations.IMPORT)
                },
                onNavigateToClassificationChapter = { chapterFile ->
                    navController.navigateFrom(entry, OrthoDestinations.classificationChapterDetail(chapterFile))
                },
                onNavigateToExtension = { extensionId ->
                    navController.navigateFrom(entry, OrthoDestinations.extensionRunner(extensionId))
                },
                onNavigateToExtensionImport = {
                    navController.navigateFrom(entry, OrthoDestinations.EXTENSION_IMPORT)
                },
                onNavigateToClassificationEditor = {
                    navController.navigateFrom(entry, OrthoDestinations.CLASSIFICATION_EDITOR)
                },
                onNavigateToPatientDetail = { patientId ->
                    navController.navigateFrom(entry, OrthoDestinations.patientDetail(patientId))
                },
                onNavigateToAddPatient = {
                    navController.navigateFrom(entry, OrthoDestinations.patientEdit())
                },
                onNavigateToSurgeonProfile = {
                    navController.navigateFrom(entry, OrthoDestinations.SURGEON_PROFILE)
                },
                onNavigateToDataTransfer = {
                    navController.navigateFrom(entry, OrthoDestinations.PATIENT_DATA_IMPORT_EXPORT)
                },
                onNavigateToQrScanner = {
                    navController.navigateFrom(entry, OrthoDestinations.QR_SCANNER)
                },
                onNavigateToSearch = { navController.navigateFrom(entry, OrthoDestinations.SEARCH) },
                onNavigateToSaved = { navController.navigateFrom(entry, OrthoDestinations.SAVED) },
                onNavigateToSettings = { navController.navigateFrom(entry, OrthoDestinations.SETTINGS) }
            )
        }

        composable(OrthoDestinations.SEARCH) { entry ->
            SearchScreen(
                viewModel = viewModel(factory = SearchViewModel.provideFactory(appModule.orthoRepository)),
                onNavigateBack = { navController.popFrom(entry) },
                onNavigateToBoneDetail = { boneId ->
                    navController.navigateFrom(entry, OrthoDestinations.boneDetail(boneId))
                }
            )
        }

        composable(OrthoDestinations.SAVED) { entry ->
            FavoritesScreen(
                viewModel = viewModel(factory = SavedViewModel.provideFactory(appModule.orthoRepository)),
                onNavigateBack = { navController.popFrom(entry) },
                onNavigateToBoneDetail = { boneId ->
                    navController.navigateFrom(entry, OrthoDestinations.boneDetail(boneId))
                }
            )
        }

        composable(OrthoDestinations.SETTINGS) { entry ->
            SettingsScreen(
                viewModel = viewModel(
                    factory = SettingsViewModel.provideFactory(
                        appModule.settingsRepository,
                        appModule.backupManager,
                        appModule.orthoRepository,
                        appModule.patientRepository
                    )
                ),
                onNavigateBack = { navController.popFrom(entry) },
                onNavigateToTelegramDrive = {
                    navController.navigateFrom(entry, OrthoDestinations.TELEGRAM_DRIVE_SETTINGS)
                },
                onNavigateToGemini = { navController.navigateFrom(entry, OrthoDestinations.GEMINI_SETTINGS) }
            )
        }

        composable(OrthoDestinations.GEMINI_SETTINGS) { entry ->
            com.example.ui.screens.ai.GeminiSettingsScreen(
                viewModel = viewModel(
                    key = "gemini_settings",
                    factory = com.example.viewmodel.GeminiSettingsViewModel.provideFactory(appModule.geminiService)
                ),
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(
            route = OrthoDestinations.PATIENT_ASSISTANT,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { entry ->
            val patientId = entry.arguments?.getString("patientId")?.let { Uri.decode(it) } ?: ""
            com.example.ui.screens.ai.PatientAssistantScreen(
                viewModel = viewModel(
                    key = "patient_assistant_$patientId",
                    factory = com.example.viewmodel.PatientAssistantViewModel.provideFactory(
                        appModule.patientRepository, appModule.geminiService, patientId
                    )
                ),
                onNavigateBack = { navController.popFrom(entry) },
                onOpenGeminiSettings = { navController.navigateFrom(entry, OrthoDestinations.GEMINI_SETTINGS) }
            )
        }

        composable(
            route = OrthoDestinations.CHAPTER_BONES,
            arguments = listOf(
                navArgument("chapterId") { type = NavType.StringType },
                navArgument("chapterTitle") { type = NavType.StringType }
            )
        ) { entry ->
            val chapterId = entry.arguments?.getString("chapterId")?.let { Uri.decode(it) } ?: ""
            val chapterTitle = entry.arguments?.getString("chapterTitle")?.let { Uri.decode(it) } ?: "Chapter"

            BoneListScreen(
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                repository = appModule.orthoRepository,
                onNavigateBack = { navController.popFrom(entry) },
                onNavigateToBoneDetail = { boneId ->
                    navController.navigateFrom(entry, OrthoDestinations.boneDetail(boneId))
                }
            )
        }

        composable(
            route = OrthoDestinations.BONE_DETAIL,
            arguments = listOf(
                navArgument("boneId") { type = NavType.StringType }
            )
        ) { entry ->
            val boneId = entry.arguments?.getString("boneId")?.let { Uri.decode(it) } ?: ""
            val detailViewModel: BoneDetailViewModel = viewModel(
                key = "bone_detail_$boneId",
                factory = BoneDetailViewModel.provideFactory(
                    appModule.orthoRepository,
                    boneId
                )
            )
            BoneDetailScreen(
                viewModel = detailViewModel,
                onNavigateBack = { navController.popFrom(entry) },
                onNavigateToImageViewer = { imageId ->
                    navController.navigateFrom(entry, OrthoDestinations.imageViewer(imageId))
                }
            )
        }

        composable(
            route = OrthoDestinations.IMAGE_VIEWER,
            arguments = listOf(
                navArgument("imageId") { type = NavType.StringType }
            )
        ) { entry ->
            val imageId = entry.arguments?.getString("imageId")?.let { Uri.decode(it) } ?: ""
            val viewerViewModel: ViewerViewModel = viewModel(
                key = "viewer_$imageId",
                factory = ViewerViewModel.provideFactory(
                    appModule.orthoRepository,
                    imageId
                )
            )
            ImageViewerScreen(
                viewModel = viewerViewModel,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(OrthoDestinations.IMPORT) { entry ->
            val importViewModel: ImportViewModel = viewModel(
                factory = ImportViewModel.provideFactory(appModule.boneDatabaseImporter)
            )
            ImportScreen(
                viewModel = importViewModel,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(
            route = OrthoDestinations.CLASSIFICATION_CHAPTER_DETAIL,
            arguments = listOf(
                navArgument("chapterFile") { type = NavType.StringType }
            )
        ) { entry ->
            val chapterFile = entry.arguments?.getString("chapterFile")?.let { Uri.decode(it) } ?: ""
            val classificationDetailViewModel: ClassificationViewModel = viewModel(
                key = "classification_detail_$chapterFile",
                factory = ClassificationViewModel.provideFactory(appModule.classificationRepository)
            )
            ClassificationChapterDetailRoute(
                viewModel = classificationDetailViewModel,
                repository = appModule.classificationRepository,
                chapterFile = chapterFile,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(
            route = OrthoDestinations.PATIENT_DETAIL,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { entry ->
            val patientId = entry.arguments?.getString("patientId")?.let { Uri.decode(it) } ?: ""
            val detailViewModel: PatientDetailViewModel = viewModel(
                key = "patient_detail_$patientId",
                factory = PatientDetailViewModel.provideFactory(appModule.patientRepository, patientId)
            )
            val protocolContext = LocalContext.current
            val protocolViewModel: OperativeProtocolViewModel = viewModel(
                factory = OperativeProtocolViewModel.provideFactory(
                    appModule.patientRepository,
                    appModule.surgeonProfileRepository,
                    appModule.pdfOptionsRepository
                )
            )
            val savedSurgeryPdfOptions by protocolViewModel.savedSurgeryOptions.collectAsStateWithLifecycle()
            val savedChartPdfOptions by protocolViewModel.savedChartOptions.collectAsStateWithLifecycle()
            PatientDetailScreen(
                viewModel = detailViewModel,
                onNavigateBack = { navController.popFrom(entry) },
                onNavigateToEdit = { navController.navigateFrom(entry, OrthoDestinations.patientEdit(patientId)) },
                onOpenAssistant = { navController.navigateFrom(entry, OrthoDestinations.patientAssistant(patientId)) },
                onNavigateToAddTimelineEvent = { navController.navigateFrom(entry, OrthoDestinations.timelineEventEdit(patientId)) },
                onNavigateToEditTimelineEvent = { eventId ->
                    navController.navigateFrom(entry, OrthoDestinations.timelineEventEdit(patientId, eventId))
                },
                onNavigateToAddSurgery = { navController.navigateFrom(entry, OrthoDestinations.surgeryEdit(patientId)) },
                onNavigateToEditSurgery = { surgeryId ->
                    navController.navigateFrom(entry, OrthoDestinations.surgeryEdit(patientId, surgeryId))
                },
                onNavigateToCapture = { navController.navigateFrom(entry, OrthoDestinations.mediaCapture(patientId)) },
                onNavigateToAnnotate = { imageId -> navController.navigateFrom(entry, OrthoDestinations.imageAnnotate(imageId)) },
                onNavigateToVideo = { mediaId -> navController.navigateFrom(entry, OrthoDestinations.videoPlayer(mediaId)) },
                savedSurgeryPdfOptions = savedSurgeryPdfOptions,
                savedChartPdfOptions = savedChartPdfOptions,
                onGenerateProtocol = { surgeryId, language, anonymize, options ->
                    protocolViewModel.generateAndShare(protocolContext, patientId, surgeryId, language, anonymize, options)
                },
                onGenerateChartPdf = { anonymize, options ->
                    protocolViewModel.generatePatientChart(protocolContext, patientId, anonymize, options)
                }
            )
        }

        composable(
            route = OrthoDestinations.PATIENT_EDIT,
            arguments = listOf(
                navArgument("patientId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { entry ->
            val patientId = entry.arguments?.getString("patientId")?.let { Uri.decode(it) }
            val editViewModel: PatientEditViewModel = viewModel(
                key = "patient_edit_${patientId ?: "new"}",
                factory = PatientEditViewModel.provideFactory(appModule.patientRepository, appModule.suggestionRepository, patientId)
            )
            PatientEditScreen(
                viewModel = editViewModel,
                classificationRepository = appModule.classificationRepository,
                onNavigateBack = { navController.popFrom(entry) },
                onSaved = { savedId ->
                    // created from the list FAB: land straight on the new profile
                    if (navController.popFrom(entry) && patientId == null) {
                        navController.navigate(OrthoDestinations.patientDetail(savedId))
                    }
                }
            )
        }

        composable(
            route = OrthoDestinations.TIMELINE_EVENT_EDIT,
            arguments = listOf(
                navArgument("patientId") { type = NavType.StringType },
                navArgument("eventId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { entry ->
            val patientId = entry.arguments?.getString("patientId")?.let { Uri.decode(it) } ?: ""
            val eventId = entry.arguments?.getString("eventId")?.let { Uri.decode(it) }

            val existingEventState = androidx.compose.runtime.produceState<com.example.data.local.entity.TimelineEventEntity?>(
                initialValue = null,
                key1 = eventId
            ) {
                value = eventId?.let { appModule.patientRepository.getTimelineEvent(it) }
            }
            // Wait for the (possibly null) lookup so an edit doesn't briefly render as "new".
            if (eventId != null && existingEventState.value == null) {
                // A spinner rather than a blank screen while the record loads.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@composable
            }

            val editViewModel: TimelineEventEditViewModel = viewModel(
                key = "timeline_edit_${eventId ?: "new"}_$patientId",
                factory = TimelineEventEditViewModel.provideFactory(appModule.patientRepository, appModule.suggestionRepository, patientId, existingEventState.value)
            )
            TimelineEventEditScreen(
                viewModel = editViewModel,
                onNavigateBack = { navController.popFrom(entry) },
                onSaved = { navController.popFrom(entry) }
            )
        }

        composable(
            route = OrthoDestinations.SURGERY_EDIT,
            arguments = listOf(
                navArgument("patientId") { type = NavType.StringType },
                navArgument("surgeryId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { entry ->
            val patientId = entry.arguments?.getString("patientId")?.let { Uri.decode(it) } ?: ""
            val surgeryId = entry.arguments?.getString("surgeryId")?.let { Uri.decode(it) }

            val existingSurgeryState = androidx.compose.runtime.produceState<com.example.data.local.entity.SurgeryEntity?>(
                initialValue = null,
                key1 = surgeryId
            ) {
                value = surgeryId?.let { appModule.patientRepository.getSurgery(it) }
            }
            if (surgeryId != null && existingSurgeryState.value == null) {
                // A spinner rather than a blank screen while the record loads.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@composable
            }

            val editViewModel: SurgeryEditViewModel = viewModel(
                key = "surgery_edit_${surgeryId ?: "new"}_$patientId",
                factory = SurgeryEditViewModel.provideFactory(appModule.patientRepository, appModule.suggestionRepository, patientId, existingSurgeryState.value)
            )
            SurgeryEditScreen(
                viewModel = editViewModel,
                classificationRepository = appModule.classificationRepository,
                onNavigateBack = { navController.popFrom(entry) },
                onSaved = { navController.popFrom(entry) }
            )
        }

        composable(
            route = OrthoDestinations.MEDIA_CAPTURE,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { entry ->
            val patientId = entry.arguments?.getString("patientId")?.let { Uri.decode(it) } ?: ""
            val context = LocalContext.current
            val captureViewModel: MediaCaptureViewModel = viewModel(
                key = "media_capture_$patientId",
                factory = MediaCaptureViewModel.provideFactory(appModule.patientRepository, patientId)
            )
            val captureState by captureViewModel.uiState.collectAsStateWithLifecycle()

            val launchScanner = rememberDocumentScannerLauncher { scannedUris ->
                captureViewModel.saveCapturedImages(context, scannedUris, ImageCategory.DOCUMENT_SCAN)
            }

            LaunchedEffect(captureState.isSaving, captureState.savedCount, captureState.totalToSave) {
                if (!captureState.isSaving && captureState.totalToSave > 0 && captureState.savedCount == captureState.totalToSave) {
                    navController.popFrom(entry)
                }
            }

            PatientCameraCaptureScreen(
                onNavigateBack = { navController.popFrom(entry) },
                onPhotoCaptured = { uri, category ->
                    captureViewModel.saveCapturedImages(context, listOf(uri), category)
                },
                onOpenScanner = launchScanner,
                onMediaPicked = { uris, category ->
                    captureViewModel.saveCapturedMedia(context, uris, category)
                },
                onVideoRecorded = { uri, category ->
                    captureViewModel.saveCapturedVideos(context, listOf(uri), category)
                }
            )
        }

        composable(
            route = OrthoDestinations.VIDEO_PLAYER,
            arguments = listOf(navArgument("mediaId") { type = NavType.StringType })
        ) { entry ->
            val mediaId = entry.arguments?.getString("mediaId")?.let { Uri.decode(it) } ?: ""
            com.example.ui.screens.patients.VideoPlayerScreen(
                repository = appModule.patientRepository,
                mediaId = mediaId,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(
            route = OrthoDestinations.IMAGE_ANNOTATE,
            arguments = listOf(navArgument("imageId") { type = NavType.StringType })
        ) { entry ->
            val imageId = entry.arguments?.getString("imageId")?.let { Uri.decode(it) } ?: ""
            val annotationViewModel: ImageAnnotationViewModel = viewModel(
                key = "annotate_$imageId",
                factory = ImageAnnotationViewModel.provideFactory(appModule.patientRepository, imageId)
            )
            ImageAnnotationScreen(
                viewModel = annotationViewModel,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(OrthoDestinations.QR_SCANNER) { entry ->
            QrScannerScreen(
                onNavigateBack = { navController.popFrom(entry) },
                onPatientFound = { patientId ->
                    navController.navigateFrom(entry, OrthoDestinations.patientDetail(patientId)) {
                        popUpTo(OrthoDestinations.QR_SCANNER) { inclusive = true }
                    }
                }
            )
        }

        composable(OrthoDestinations.SURGEON_PROFILE) { entry ->
            val profileViewModel: SurgeonProfileViewModel = viewModel(
                factory = SurgeonProfileViewModel.provideFactory(appModule.surgeonProfileRepository)
            )
            SurgeonProfileScreen(
                viewModel = profileViewModel,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(OrthoDestinations.TELEGRAM_DRIVE_SETTINGS) { entry ->
            val driveContext = LocalContext.current.applicationContext
            val driveViewModel: TelegramDriveSettingsViewModel = viewModel(
                key = "telegram_drive_settings",
                factory = TelegramDriveSettingsViewModel.provideFactory(appModule, driveContext)
            )
            TelegramDriveSettingsScreen(
                viewModel = driveViewModel,
                onNavigateBack = { navController.popFrom(entry) },
                onNavigateToSetup = {
                    navController.navigateFrom(entry, OrthoDestinations.TELEGRAM_DRIVE_SETUP)
                },
                onNavigateToDiagnostics = {
                    navController.navigateFrom(entry, OrthoDestinations.TELEGRAM_DRIVE_DIAGNOSTICS)
                }
            )
        }

        composable(OrthoDestinations.TELEGRAM_DRIVE_SETUP) { entry ->
            val setupContext = LocalContext.current.applicationContext
            val setupViewModel: TelegramSetupViewModel = viewModel(
                key = "telegram_drive_setup",
                factory = TelegramSetupViewModel.provideFactory(appModule, setupContext)
            )
            TelegramSetupWizardScreen(
                viewModel = setupViewModel,
                onNavigateBack = { navController.popFrom(entry) },
                onSetupComplete = {
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        navController.popBackStack(
                            route = OrthoDestinations.TELEGRAM_DRIVE_SETTINGS,
                            inclusive = false
                        )
                    }
                }
            )
        }

        composable(OrthoDestinations.TELEGRAM_DRIVE_DIAGNOSTICS) { entry ->
            val diagnosticsContext = LocalContext.current.applicationContext
            val diagnosticsViewModel: TelegramDriveSettingsViewModel = viewModel(
                key = "telegram_drive_settings",
                factory = TelegramDriveSettingsViewModel.provideFactory(appModule, diagnosticsContext)
            )
            StorageDiagnosticsScreen(
                viewModel = diagnosticsViewModel,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(
            route = OrthoDestinations.EXTENSION_RUNNER,
            arguments = listOf(navArgument("extensionId") { type = NavType.StringType })
        ) { entry ->
            val extensionId = entry.arguments?.getString("extensionId")?.let { Uri.decode(it) }.orEmpty()
            ExtensionRunnerScreen(
                repository = appModule.extensionRepository,
                databaseAccess = appModule.extensionDatabaseAccess,
                extensionId = extensionId,
                onNavigateBack = { navController.popFrom(entry) },
                onOpenGeminiSettings = { navController.navigateFrom(entry, OrthoDestinations.GEMINI_SETTINGS) }
            )
        }

        composable(OrthoDestinations.EXTENSION_IMPORT) { entry ->
            ExtensionImportScreen(
                viewModel = viewModel(factory = ExtensionImportViewModel.provideFactory(appModule.extensionRepository)),
                onNavigateBack = { navController.popFrom(entry) },
                onOpenClassificationEditor = {
                    navController.navigateFrom(entry, OrthoDestinations.CLASSIFICATION_EDITOR)
                }
            )
        }

        composable(OrthoDestinations.CLASSIFICATION_EDITOR) { entry ->
            ClassificationEditorHomeScreen(
                viewModel = viewModel(
                    factory = ClassificationEditorHomeViewModel.provideFactory(
                        appModule.classificationDraftRepository,
                        appModule.extensionRepository
                    )
                ),
                onNavigateBack = { navController.popFrom(entry) },
                onOpenDraft = { draftId ->
                    navController.navigateFrom(entry, OrthoDestinations.classificationDraft(draftId))
                }
            )
        }

        composable(
            route = OrthoDestinations.CLASSIFICATION_DRAFT,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType })
        ) { entry ->
            val draftId = entry.arguments?.getString("draftId")?.let { Uri.decode(it) } ?: ""
            ClassificationDraftScreen(
                viewModel = viewModel(
                    factory = ClassificationDraftViewModel.provideFactory(appModule.classificationDraftRepository, draftId)
                ),
                onNavigateBack = { navController.popFrom(entry) },
                onOpenChapter = { chapterFile ->
                    navController.navigateFrom(entry, OrthoDestinations.classificationDocument(draftId, chapterFile))
                }
            )
        }

        composable(
            route = OrthoDestinations.CLASSIFICATION_DOCUMENT,
            arguments = listOf(
                navArgument("draftId") { type = NavType.StringType },
                navArgument("chapterFile") { type = NavType.StringType }
            )
        ) { entry ->
            val draftId = entry.arguments?.getString("draftId")?.let { Uri.decode(it) } ?: ""
            val chapterFile = entry.arguments?.getString("chapterFile")?.let { Uri.decode(it) } ?: ""
            ClassificationDocumentScreen(
                viewModel = viewModel(
                    factory = ClassificationDocumentViewModel.provideFactory(
                        appModule.classificationDraftRepository,
                        draftId,
                        chapterFile
                    )
                ),
                onNavigateBack = { navController.popFrom(entry) }
            )
        }

        composable(OrthoDestinations.PATIENT_DATA_IMPORT_EXPORT) { entry ->
            val transferViewModel: PatientDataTransferViewModel = viewModel(
                factory = PatientDataTransferViewModel.provideFactory(appModule.patientRepository)
            )
            PatientDataTransferScreen(
                viewModel = transferViewModel,
                onNavigateBack = { navController.popFrom(entry) }
            )
        }
    }
}

/**
 * Pops only while [entry] is still the screen on top. A double tap on a back
 * arrow, or a callback that fires twice, used to pop two screens and could empty
 * the back stack, leaving a blank window.
 */
private fun NavController.popFrom(entry: NavBackStackEntry): Boolean =
    currentBackStackEntry?.id == entry.id && popBackStack()

/** Navigates only from the screen on top, so a double tap opens one copy, not two. */
private fun NavController.navigateFrom(
    entry: NavBackStackEntry,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {}
) {
    if (currentBackStackEntry?.id == entry.id) navigate(route, builder)
}
