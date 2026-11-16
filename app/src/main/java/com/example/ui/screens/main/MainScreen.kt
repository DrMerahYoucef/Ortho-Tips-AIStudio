package com.example.ui.screens.main

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.di.AppModule
import com.example.ui.components.DriveSpaceBadge
import com.example.ui.screens.agenda.AgendaScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.patients.PatientListScreen
import com.example.viewmodel.AppointmentsViewModel
import com.example.viewmodel.ClassificationViewModel
import com.example.viewmodel.ExtensionsViewModel
import com.example.viewmodel.HomeViewModel
import com.example.viewmodel.PatientDataTransferViewModel
import com.example.viewmodel.PatientListViewModel
import com.example.viewmodel.ShiftsViewModel

/**
 * The three top-level areas: Agenda, Patients and Extensions (operative guides,
 * classifications and tools, all installed as `.OTips` extensions). Search,
 * Saved and Settings are ordinary screens pushed on top, not hidden tabs.
 */
enum class MainTab(
    @StringRes val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    // Declaration order is the order in the bottom bar.
    AGENDA(R.string.nav_agenda, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth, "nav_agenda"),
    PATIENTS(R.string.nav_patients, Icons.Filled.People, Icons.Outlined.PeopleOutline, "nav_patients"),
    EXTENSIONS(R.string.nav_extensions, Icons.Filled.Extension, Icons.Outlined.Extension, "nav_home");

    companion object {
        /** Names saved by earlier versions, so the app still reopens on the same tab. */
        private val LEGACY_NAMES = mapOf("STUDIES" to EXTENSIONS)

        fun fromSaved(name: String?): MainTab? =
            name?.let { saved -> entries.firstOrNull { it.name == saved } ?: LEGACY_NAMES[saved] }
    }
}

private const val UI_PREFS = "ui_state"
private const val KEY_LAST_TAB = "main_tab"

@Composable
fun MainScreen(
    appModule: AppModule,
    onNavigateToChapter: (String, String) -> Unit,
    onNavigateToBoneDetail: (String) -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToClassificationChapter: (String) -> Unit,
    onNavigateToExtension: (String) -> Unit,
    onNavigateToExtensionImport: () -> Unit,
    onNavigateToClassificationEditor: () -> Unit,
    onNavigateToPatientDetail: (String) -> Unit,
    onNavigateToAddPatient: () -> Unit,
    onNavigateToSurgeonProfile: () -> Unit,
    onNavigateToDataTransfer: () -> Unit,
    onNavigateToQrScanner: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSaved: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // The app reopens on the tab last used; a small file read once, like AppLocale.
    val prefs = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    var selectedTab by rememberSaveable {
        mutableStateOf(
            MainTab.fromSaved(prefs.getString(KEY_LAST_TAB, null)) ?: MainTab.entries.first()
        )
    }
    fun select(tab: MainTab) {
        selectedTab = tab
        prefs.edit().putString(KEY_LAST_TAB, tab.name).apply()
    }

    // Keeps each tab's scroll position and sub-tab while another tab is shown.
    val tabStates = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("main_bottom_nav")) {
                MainTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { select(tab) },
                        icon = {
                            val icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon
                            // Only Patients carries the badge, and only while backed-up
                            // media is still taking local space.
                            if (tab == MainTab.PATIENTS) {
                                DriveSpaceBadge(refreshKey = selectedTab) { Icon(icon, contentDescription = null) }
                            } else {
                                Icon(icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(tab.titleRes), maxLines = 1) },
                        modifier = Modifier.testTag(tab.testTag)
                    )
                }
            }
        },
        // The bar draws its own inset; each tab's app bar handles the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
    ) { padding ->
        Crossfade(
            targetState = selectedTab,
            label = "tab_crossfade",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Tells the tabs' own Scaffolds the bottom bar already covers the
                // navigation bar, so no extra gap is added above it.
                .consumeWindowInsets(padding)
        ) { tab ->
            tabStates.SaveableStateProvider(tab.name) {
                // ViewModels are created when their tab is first shown, not all at
                // launch. They live on the Main back-stack entry, so switching tabs
                // keeps them.
                when (tab) {
                    MainTab.EXTENSIONS -> HomeScreen(
                        viewModel = viewModel(
                            factory = HomeViewModel.provideFactory(appModule.orthoRepository)
                        ),
                        classificationViewModel = viewModel<ClassificationViewModel>(
                            factory = ClassificationViewModel.provideFactory(appModule.classificationRepository)
                        ),
                        extensionsViewModel = viewModel<ExtensionsViewModel>(
                            factory = ExtensionsViewModel.provideFactory(appModule.extensionRepository)
                        ),
                        onNavigateToChapter = onNavigateToChapter,
                        onNavigateToBoneDetail = onNavigateToBoneDetail,
                        onNavigateToSearch = onNavigateToSearch,
                        onNavigateToSaved = onNavigateToSaved,
                        onNavigateToImport = onNavigateToImport,
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToClassificationChapter = onNavigateToClassificationChapter,
                        onNavigateToExtension = onNavigateToExtension,
                        onNavigateToExtensionImport = onNavigateToExtensionImport,
                        onNavigateToClassificationEditor = onNavigateToClassificationEditor
                    )

                    MainTab.AGENDA -> AgendaScreen(
                        appointmentsViewModel = viewModel<AppointmentsViewModel>(
                            factory = AppointmentsViewModel.provideFactory(context)
                        ),
                        shiftsViewModel = viewModel<ShiftsViewModel>(
                            factory = ShiftsViewModel.provideFactory(appModule.shiftRepository)
                        ),
                        onOpenPatient = onNavigateToPatientDetail,
                        onOpenSettings = onNavigateToSettings
                    )

                    MainTab.PATIENTS -> {
                        val dataTransferViewModel: PatientDataTransferViewModel = viewModel(
                            factory = PatientDataTransferViewModel.provideFactory(appModule.patientRepository)
                        )
                        PatientListScreen(
                            viewModel = viewModel<PatientListViewModel>(
                                factory = PatientListViewModel.provideFactory(appModule.patientRepository)
                            ),
                            onNavigateToPatientDetail = onNavigateToPatientDetail,
                            onNavigateToAddPatient = onNavigateToAddPatient,
                            onNavigateToSurgeonProfile = onNavigateToSurgeonProfile,
                            onNavigateToDataTransfer = onNavigateToDataTransfer,
                            onNavigateToQrScanner = onNavigateToQrScanner,
                            onNavigateToSettings = onNavigateToSettings,
                            onExportSelected = { ids -> dataTransferViewModel.exportPatients(context, ids) }
                        )
                    }
                }
            }
        }
    }
}
