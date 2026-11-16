package com.example.ui.screens.patients

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.prefs.SurgeonProfile
import com.example.utils.PatientMediaStorage
import com.example.utils.SignatureProcessor
import com.example.utils.SignatureResult
import com.example.utils.rememberDocumentScannerLauncher
import com.example.viewmodel.SurgeonProfileViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurgeonProfileScreen(
    viewModel: SurgeonProfileViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val storedProfile by viewModel.profile.collectAsStateWithLifecycle()
    val isProfileLoaded by viewModel.isLoaded.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf(SurgeonProfile()) }
    var initialized by remember { mutableStateOf(false) }
    var isProcessingSignature by remember { mutableStateOf(false) }
    var signatureRevision by remember { mutableStateOf(0L) }
    var signatureStatusMessage by remember { mutableStateOf<String?>(null) }
    var signatureStatusIsWarning by remember { mutableStateOf(false) }
    var logoRevision by remember { mutableStateOf(0L) }
    var logoStatusMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(storedProfile, isProfileLoaded) {
        if (!initialized && isProfileLoaded) {
            form = storedProfile
            initialized = true
        }
    }

    fun processSignature(uri: Uri) {
        isProcessingSignature = true
        signatureStatusMessage = null
        scope.launch(Dispatchers.IO) {
            val destination = File(PatientMediaStorage.rootDir(context), "surgeon_signature.png").also { file ->
                file.parentFile?.mkdirs()
            }
            val result = runCatching {
                SignatureProcessor.processFromUri(context.contentResolver, uri, destination)
            }.getOrElse { SignatureResult.Failure(R.string.signature_error_generic) }

            scope.launch {
                when (result) {
                    is SignatureResult.Success -> {
                        form = form.copy(signatureImagePath = result.file.absolutePath)
                        signatureRevision = System.currentTimeMillis()
                        signatureStatusMessage = null
                        signatureStatusIsWarning = false
                    }
                    is SignatureResult.Warning -> {
                        form = form.copy(signatureImagePath = result.file.absolutePath)
                        signatureRevision = System.currentTimeMillis()
                        signatureStatusMessage = context.getString(result.messageRes)
                        signatureStatusIsWarning = true
                    }
                    is SignatureResult.Failure -> {
                        signatureStatusMessage = context.getString(result.messageRes)
                        signatureStatusIsWarning = true
                    }
                }
                isProcessingSignature = false
            }
        }
    }

    fun processLogo(uri: Uri) {
        logoStatusMessage = null
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                File(PatientMediaStorage.rootDir(context), "surgeon_logo.png").also { destination ->
                    destination.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Unable to read that image.")
                }
            }
            scope.launch {
                result.onSuccess { file ->
                    form = form.copy(logoImagePath = file.absolutePath)
                    logoRevision = System.currentTimeMillis()
                }.onFailure {
                    logoStatusMessage = context.getString(R.string.surgeon_profile_logo_load_failed)
                }
            }
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processSignature(it) }
    }

    val scanLauncher = rememberDocumentScannerLauncher { scannedUris ->
        scannedUris.firstOrNull()?.let { processSignature(it) }
    }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processLogo(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.surgeon_profile_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            item {
                Text(
                    text = stringResource(R.string.surgeon_profile_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { ProfileField(stringResource(R.string.surgeon_profile_full_name), form.name) { form = form.copy(name = it) } }
            item { ProfileField(stringResource(R.string.surgeon_profile_specialty), form.specialty) { form = form.copy(specialty = it) } }
            item { ProfileField(stringResource(R.string.surgeon_profile_hospital), form.hospital) { form = form.copy(hospital = it) } }
            item { ProfileField(stringResource(R.string.surgeon_profile_address), form.address, singleLine = false) { form = form.copy(address = it) } }
            item { ProfileField(stringResource(R.string.surgeon_profile_phone), form.phone) { form = form.copy(phone = it) } }
            item { ProfileField(stringResource(R.string.surgeon_profile_email), form.email) { form = form.copy(email = it) } }
            item { ProfileField(stringResource(R.string.surgeon_profile_registration_number), form.registrationNumber) { form = form.copy(registrationNumber = it) } }

            item {
                Text(
                    text = stringResource(R.string.surgeon_profile_clinic_logo_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Text(
                    text = stringResource(R.string.surgeon_profile_logo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (form.logoImagePath != null) {
                        AsyncImage(
                            model = "file://${form.logoImagePath}?rev=$logoRevision",
                            contentDescription = stringResource(R.string.surgeon_profile_clinic_logo),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(stringResource(R.string.surgeon_profile_no_logo), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            logoStatusMessage?.let { message ->
                item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { logoPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.surgeon_profile_select_logo), modifier = Modifier.padding(start = 4.dp))
                    }
                    if (form.logoImagePath != null) {
                        TextButton(onClick = { form = form.copy(logoImagePath = null); logoStatusMessage = null }) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.surgeon_profile_remove), modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.surgeon_profile_signature_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Text(
                    text = stringResource(R.string.surgeon_profile_signature_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        // Faint checkerboard-like backdrop so a transparent-background
                        // signature is visibly "cut out" rather than looking blank.
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isProcessingSignature -> CircularProgressIndicator()
                        form.signatureImagePath != null -> AsyncImage(
                            model = "file://${form.signatureImagePath}?rev=$signatureRevision",
                            contentDescription = stringResource(R.string.surgeon_profile_signature),
                            modifier = Modifier.fillMaxSize()
                        )
                        else -> Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                }
            }
            signatureStatusMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (signatureStatusIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { scanLauncher() },
                        enabled = !isProcessingSignature,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.surgeon_profile_scan), modifier = Modifier.padding(start = 4.dp))
                    }
                    OutlinedButton(
                        onClick = { galleryPicker.launch("image/*") },
                        enabled = !isProcessingSignature,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.surgeon_profile_upload), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (form.signatureImagePath != null) {
                item {
                    TextButton(
                        onClick = {
                            form = form.copy(signatureImagePath = null)
                            signatureStatusMessage = null
                        },
                        enabled = !isProcessingSignature
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.surgeon_profile_remove_signature), modifier = Modifier.padding(start = 4.dp))
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.surgeon_profile_include_signature),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = form.includeSignatureInPdf,
                                onCheckedChange = { form = form.copy(includeSignatureInPdf = it) }
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.save(form); onNavigateBack() },
                    enabled = !isProcessingSignature,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.surgeon_profile_save))
                }
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) androidx.compose.ui.text.input.ImeAction.Next
            else androidx.compose.ui.text.input.ImeAction.Default
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
