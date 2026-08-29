package com.vboard.app.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vboard.app.R
import com.vboard.app.models.ModelDownloadService
import com.vboard.core.model.ByteSize
import com.vboard.core.model.DownloadDecision
import com.vboard.core.model.DownloadPolicy
import com.vboard.core.model.DownloadSizes
import com.vboard.core.model.InstallError
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.model.ModelPack
import com.vboard.core.model.ModelReadiness
import com.vboard.core.model.NetworkState
import com.vboard.core.model.PackInstaller
import com.vboard.core.model.PackState
import kotlinx.coroutines.delay

/** The linear onboarding steps, in order. */
enum class OnboardingStep { WELCOME, ENABLE, SELECT, MIC, MODELS, DONE }

/**
 * Live per-pack state for the UI: transient states (downloading/verifying) come
 * from the service; terminal states are re-derived from disk so they stay
 * correct across process restarts and deletions.
 */
internal fun effectivePackState(
    installer: PackInstaller,
    pack: ModelPack,
    serviceState: PackState?,
): PackState = when (serviceState) {
    is PackState.Downloading -> serviceState
    PackState.Verifying -> PackState.Verifying
    is PackState.Failed ->
        if (installer.stateOf(pack) == PackState.Installed) PackState.Installed else serviceState
    else -> installer.stateOf(pack)
}

/**
 * Renders a byte count for display. Delegates to [ByteSize] so setup, settings and the
 * download notifications cannot disagree, and so the numbers are unit-tested in `:core`.
 */
internal fun formatBytes(bytes: Long): String = ByteSize.format(bytes)

internal fun installErrorText(error: InstallError, pack: ModelPack? = null): String = when (error) {
    InstallError.NETWORK -> "Download interrupted. Check your connection and retry."
    InstallError.CHECKSUM_MISMATCH -> "The downloaded file didn't verify. Retry to download it again."
    InstallError.INSUFFICIENT_STORAGE -> if (pack == null) {
        "Not enough free storage. Free up space and retry."
    } else {
        // The footprint exceeds the download: an archive is unpacked before it is deleted.
        "Needs about ${formatBytes(pack.installFootprintBytes)} free while installing. " +
            "Free up space and retry."
    }
    InstallError.CANCELLED -> "Download cancelled."
    InstallError.IO -> "Couldn't save the model to storage. Retry."
}

@Composable
fun OnboardingFlow(
    step: OnboardingStep,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    micGranted: Boolean,
    packStates: Map<String, PackState>,
    scheduled: List<ModelDownloadService.Scheduled>,
    networkState: NetworkState,
    onStepChange: (OnboardingStep) -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onRecheckSystemState: () -> Unit,
    onMicResult: (Boolean) -> Unit,
    onOpenAppSettings: () -> Unit,
    onDownloadPack: (packId: String, allowMetered: Boolean) -> Unit,
    onCancelDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onFinished: () -> Unit,
) {
    val installedIds = packStates.filterValues { it == PackState.Installed }.keys
    // "Can the user dictate?", not "are all the files present?". The two stopped being the
    // same question once the accuracy pack became an optional upgrade.
    val canDictate = ModelReadiness.canDictate(installedIds)
    val completed = buildSet {
        if (step != OnboardingStep.WELCOME) add(OnboardingStep.WELCOME)
        if (imeEnabled) add(OnboardingStep.ENABLE)
        if (imeSelected) add(OnboardingStep.SELECT)
        if (micGranted) add(OnboardingStep.MIC)
        if (canDictate) add(OnboardingStep.MODELS)
        if (step == OnboardingStep.DONE) add(OnboardingStep.DONE)
    }
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StepDots(
                current = step,
                completed = completed,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeStep(
                        onContinue = { onStepChange(OnboardingStep.ENABLE) },
                    )
                    OnboardingStep.ENABLE -> EnableStep(
                        imeEnabled = imeEnabled,
                        onOpenImeSettings = onOpenImeSettings,
                        onContinue = { onStepChange(OnboardingStep.SELECT) },
                    )
                    OnboardingStep.SELECT -> SelectStep(
                        imeSelected = imeSelected,
                        onShowImePicker = onShowImePicker,
                        onRecheck = onRecheckSystemState,
                        onContinue = { onStepChange(OnboardingStep.MIC) },
                    )
                    OnboardingStep.MIC -> MicStep(
                        micGranted = micGranted,
                        onMicResult = onMicResult,
                        onOpenAppSettings = onOpenAppSettings,
                        onSkip = { onStepChange(OnboardingStep.MODELS) },
                        onContinue = { onStepChange(OnboardingStep.MODELS) },
                    )
                    OnboardingStep.MODELS -> ModelsStep(
                        packStates = packStates,
                        scheduled = scheduled,
                        networkState = networkState,
                        canDictate = canDictate,
                        onDownloadPack = onDownloadPack,
                        onCancelDownloads = onCancelDownloads,
                        onFinish = { onStepChange(OnboardingStep.DONE) },
                    )
                    OnboardingStep.DONE -> DoneStep(
                        canDictate = canDictate,
                        onAddVoice = { onStepChange(OnboardingStep.MODELS) },
                        onOpenSettings = onOpenSettings,
                        onClose = onFinished,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- stepper

@Composable
private fun StepDots(
    current: OnboardingStep,
    completed: Set<OnboardingStep>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnboardingStep.entries.forEach { s ->
            val isCurrent = s == current
            val isDone = s in completed
            val color = if (isDone || isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .size(if (isCurrent) 14.dp else 12.dp)
                    .background(color = color, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isDone) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(9.dp),
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------- step chrome

@Composable
private fun StepScaffold(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(28.dp))
        content()
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCaption(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

// -------------------------------------------------------------------- steps

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    StepScaffold(
        icon = Icons.Filled.GraphicEq,
        title = "Type with your voice.",
        body = "Voice-first typing. Private by design.\n\n" +
            "VBoard turns speech into clean, punctuated text — entirely on your phone. " +
            "Nothing you say ever leaves your device.",
    ) {
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_get_started))
        }
    }
}

@Composable
private fun EnableStep(
    imeEnabled: Boolean,
    onOpenImeSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    StepScaffold(
        icon = Icons.Filled.Keyboard,
        title = "Turn on VBoard",
        body = "Android needs you to enable new keyboards in Settings. " +
            "We'll take you there — just switch on VBoard.",
    ) {
        if (imeEnabled) {
            StatusCaption("VBoard is enabled.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        } else {
            Button(
                onClick = onOpenImeSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_open_keyboard_settings))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "VBoard isn't enabled yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectStep(
    imeSelected: Boolean,
    onShowImePicker: () -> Unit,
    onRecheck: () -> Unit,
    onContinue: () -> Unit,
) {
    // The IME picker is a system dialog that may not pause this activity, so
    // poll the current-keyboard setting while this step is visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            onRecheck()
        }
    }
    StepScaffold(
        icon = Icons.Filled.TouchApp,
        title = "Make VBoard your keyboard",
        body = "Choose VBoard as your current keyboard. You can switch back anytime " +
            "from the keyboard icon in your navigation bar.",
    ) {
        if (imeSelected) {
            StatusCaption("VBoard is your current keyboard.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        } else {
            Button(
                onClick = onShowImePicker,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_choose_vboard))
            }
        }
    }
}

@Composable
private fun MicStep(
    micGranted: Boolean,
    onMicResult: (Boolean) -> Unit,
    onOpenAppSettings: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    var wasDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) wasDenied = true
        onMicResult(granted)
    }
    StepScaffold(
        icon = Icons.Filled.Mic,
        title = "Let VBoard hear you",
        body = "Voice typing needs the microphone. Audio is processed on this device " +
            "and never recorded, stored, or uploaded.",
    ) {
        if (micGranted) {
            StatusCaption("Microphone access granted.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        } else {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_allow_mic))
            }
            if (wasDenied) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No problem — you can still type. To use voice later, allow the " +
                        "microphone in Settings → Apps → VBoard → Permissions.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_open_app_settings))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.onboarding_not_now))
            }
        }
    }
}

/**
 * The step that used to be a dead end.
 *
 * Three things changed. The Finish button is unconditionally enabled, so setup can always be
 * completed — with nothing downloaded, the keyboard still types. Every size in the copy is
 * computed from [ModelCatalog] instead of written by hand. And a download on a metered link
 * asks first, naming the real number of megabytes it is about to spend.
 */
@Composable
private fun ModelsStep(
    packStates: Map<String, PackState>,
    scheduled: List<ModelDownloadService.Scheduled>,
    networkState: NetworkState,
    canDictate: Boolean,
    onDownloadPack: (packId: String, allowMetered: Boolean) -> Unit,
    onCancelDownloads: () -> Unit,
    onFinish: () -> Unit,
) {
    val sizes = remember { DownloadSizes.of() }
    var meteredPrompt by remember { mutableStateOf<Pair<ModelPack, Long>?>(null) }

    /** Routes a tap through [DownloadPolicy] so cellular data is never spent silently. */
    fun requestDownload(pack: ModelPack) {
        when (val decision = DownloadPolicy.decide(networkState, meteredConsent = false, bytes = pack.totalBytes)) {
            is DownloadDecision.Enqueue -> onDownloadPack(pack.id, decision.allowMetered)
            is DownloadDecision.ConfirmMetered -> meteredPrompt = pack to decision.bytes
        }
    }

    StepScaffold(
        icon = Icons.Filled.CloudDownload,
        title = stringResource(R.string.setup_models_title),
        body = stringResource(R.string.setup_models_body, sizes.requiredText),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when (networkState) {
                    NetworkState.UNMETERED ->
                        stringResource(R.string.setup_models_wifi_hint, sizes.totalText)
                    NetworkState.METERED -> stringResource(R.string.setup_models_mobile_hint)
                    NetworkState.OFFLINE -> stringResource(R.string.setup_models_offline_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        ModelCatalog.packs.forEach { pack ->
            PackRow(
                pack = pack,
                state = packStates[pack.id] ?: PackState.NotInstalled,
                scheduled = scheduled.firstOrNull { it.packId == pack.id },
                onDownload = { requestDownload(pack) },
                onCancel = onCancelDownloads,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Always enabled. Gating this on a download is what turned setup into a funnel with
        // no exit: a user on a slow or expensive connection had no way to reach a working
        // keyboard, which they already had.
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (canDictate) {
                    stringResource(R.string.onboarding_finish)
                } else {
                    stringResource(R.string.setup_skip_downloads)
                },
            )
        }
        if (!canDictate) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_skip_note),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val prompt = meteredPrompt
    if (prompt != null) {
        val (pack, bytes) = prompt
        AlertDialog(
            onDismissRequest = { meteredPrompt = null },
            title = { Text(stringResource(R.string.setup_metered_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.setup_metered_body,
                        pack.displayName,
                        ByteSize.format(bytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        meteredPrompt = null
                        onDownloadPack(pack.id, true)
                    },
                ) {
                    Text(stringResource(R.string.setup_metered_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        meteredPrompt = null
                        // Queue it anyway: the constraint holds it until Wi-Fi, which is
                        // what "wait for Wi-Fi" means to the user.
                        onDownloadPack(pack.id, false)
                    },
                ) {
                    Text(stringResource(R.string.setup_metered_wait))
                }
            },
        )
    }
}

@Composable
private fun PackRow(
    pack: ModelPack,
    state: PackState,
    scheduled: ModelDownloadService.Scheduled?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        // Size always comes from the catalog, never from prose.
                        text = if (pack.required) {
                            stringResource(R.string.setup_pack_required, formatBytes(pack.totalBytes))
                        } else {
                            stringResource(R.string.setup_pack_optional, formatBytes(pack.totalBytes))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // An optional pack has to read as an upgrade the user may take, not as a
                    // missing dependency; say what it buys and that voice works without it.
                    val upgradeNote = when {
                        pack.required -> null
                        pack.kind == ModelKind.FINAL_ASR -> stringResource(R.string.setup_upgrade_accuracy)
                        pack.kind == ModelKind.REFINER_LLM -> stringResource(R.string.setup_upgrade_refiner)
                        else -> null
                    }
                    if (upgradeNote != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = upgradeNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                PackStateChip(state)
            }
            // Enqueued-but-not-running is a real state the old service could not express:
            // the download is scheduled and the system is holding it for Wi-Fi.
            val waiting = scheduled?.waitingForNetwork == true && state != PackState.Installed
            if (waiting) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.setup_pack_waiting_wifi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.pack_cancel),
                        )
                    }
                }
            } else when (state) {
                is PackState.Downloading -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    val percent = (state.fraction * 100).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { state.fraction.toFloat() },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { stateDescription = "Downloading, $percent percent" },
                        )
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.pack_cancel),
                            )
                        }
                    }
                }
                PackState.Verifying -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is PackState.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = installErrorText(state.error, pack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.pack_retry))
                    }
                }
                PackState.NotInstalled -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onDownload) {
                        Text(
                            stringResource(
                                R.string.setup_pack_download_sized,
                                formatBytes(pack.totalBytes),
                            ),
                        )
                    }
                }
                PackState.Installed -> {
                    // The chip already shows the installed checkmark.
                }
            }
        }
    }
}

@Composable
private fun PackStateChip(state: PackState) {
    val label = when (state) {
        PackState.NotInstalled -> stringResource(R.string.pack_not_installed)
        is PackState.Downloading ->
            stringResource(R.string.pack_downloading_percent, (state.fraction * 100).toInt())
        PackState.Verifying -> stringResource(R.string.pack_verifying)
        PackState.Installed -> stringResource(R.string.pack_installed)
        is PackState.Failed -> stringResource(R.string.pack_failed)
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = if (state == PackState.Installed) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun DoneStep(
    canDictate: Boolean,
    onAddVoice: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val sizes = remember { DownloadSizes.of() }
    StepScaffold(
        icon = Icons.Filled.CheckCircle,
        // Finishing without the models is a success, not a half-finished setup: the keyboard
        // works. The copy has to say that rather than imply something is still missing.
        title = if (canDictate) {
            stringResource(R.string.setup_done_title_voice)
        } else {
            stringResource(R.string.setup_done_title_typing)
        },
        body = if (canDictate) {
            stringResource(R.string.setup_done_body_voice)
        } else {
            stringResource(R.string.setup_done_body_typing, sizes.requiredText)
        },
    ) {
        // Whatever brought the user here — finishing setup, the launcher icon, or the mic
        // key's "Download" action — this screen must offer a live route to voice typing
        // rather than congratulate them and stop. It was previously possible to arrive on a
        // "You're all set" page with nothing installed and no way forward.
        if (!canDictate) {
            Button(
                onClick = onAddVoice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setup_add_voice, sizes.requiredText))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_open_settings))
            }
        } else {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_open_settings))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.onboarding_close))
        }
    }
}
