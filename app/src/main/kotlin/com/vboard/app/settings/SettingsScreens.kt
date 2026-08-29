package com.vboard.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import com.vboard.app.R
import com.vboard.app.clipboard.ClipboardStore
import com.vboard.app.keyboard.ThemeMode
import com.vboard.app.models.ModelDownloadService
import com.vboard.app.voice.VoiceEngines
import com.vboard.app.onboarding.effectivePackState
import com.vboard.app.onboarding.formatBytes
import com.vboard.app.onboarding.installErrorText
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.model.ModelPack
import com.vboard.core.model.PackInstaller
import com.vboard.core.model.PackState
import com.vboard.core.suggest.AutocorrectMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VBoardSettings"

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun autocorrectLabel(mode: AutocorrectMode): String = when (mode) {
    AutocorrectMode.OFF -> "Off"
    AutocorrectMode.CONSERVATIVE -> "Conservative"
    AutocorrectMode.AGGRESSIVE -> "Aggressive"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    snapshot: SettingsSnapshot,
    packInstaller: PackInstaller,
    appVersion: String,
    onSetBoolean: (Preferences.Key<Boolean>, Boolean) -> Unit,
    onSetString: (Preferences.Key<String>, String) -> Unit,
    onSetLong: (Preferences.Key<Long>, Long) -> Unit,
    onOpenModelDownloads: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serviceStates by ModelDownloadService.states.collectAsState()
    // Bumped after a delete so disk-derived pack states are re-read.
    var diskTick by remember { mutableIntStateOf(0) }
    val packStates = remember(serviceStates, diskTick) {
        ModelCatalog.packs.associate { pack ->
            pack.id to effectivePackState(packInstaller, pack, serviceStates[pack.id])
        }
    }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAutocorrectDialog by remember { mutableStateOf(false) }
    var showClipboardDeleteDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ModelPack?>(null) }

    val llmPack = ModelCatalog.byKind(ModelKind.REFINER_LLM).firstOrNull()
    val llmInstalled = llmPack != null && packStates[llmPack.id] == PackState.Installed
    val cleanupEnabled = !snapshot.rawTranscriptMode

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // --------------------------------------------------- Appearance
            item { SectionHeader(stringResource(R.string.settings_group_appearance)) }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_theme),
                    value = themeLabel(snapshot.themeMode),
                    onClick = { showThemeDialog = true },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_number_row),
                    subtitle = stringResource(R.string.settings_number_row_subtitle),
                    checked = snapshot.numberRowEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.NUMBER_ROW, it) },
                )
            }

            // ------------------------------------------------------- Typing
            item { SectionHeader(stringResource(R.string.settings_group_typing)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_haptics),
                    checked = snapshot.hapticsEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.HAPTICS, it) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_key_preview),
                    subtitle = "Show a popup of each key as you type.",
                    checked = snapshot.keyPreviewEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.KEY_PREVIEW, it) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_cap),
                    subtitle = "Capitalize the first word of each sentence.",
                    checked = snapshot.autoCapitalize,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.AUTO_CAP, it) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_double_space),
                    subtitle = "Double-tapping space inserts a period.",
                    checked = snapshot.doubleSpacePeriod,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.DOUBLE_SPACE, it) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_suggestions),
                    subtitle = "Word suggestions above the keyboard.",
                    checked = snapshot.suggestionsEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.SUGGESTIONS, it) },
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_autocorrect),
                    value = autocorrectLabel(snapshot.autocorrectMode),
                    onClick = { showAutocorrectDialog = true },
                )
            }

            // ---------------------------------------------------- Clipboard
            item { SectionHeader(stringResource(R.string.settings_group_clipboard)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_clipboard_history),
                    subtitle = stringResource(R.string.settings_clipboard_history_subtitle),
                    checked = snapshot.clipboardHistoryEnabled,
                    onCheckedChange = { enabled ->
                        onSetBoolean(SettingsRepository.Keys.CLIPBOARD_HISTORY, enabled)
                        // Turning it off is a promise that nothing is left on
                        // disk, so the file goes now rather than whenever a
                        // keyboard next happens to be running.
                        if (!enabled) {
                            scope.launch(Dispatchers.IO) { ClipboardStore.deleteBlocking(context) }
                        }
                    },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_clipboard_suggestions),
                    subtitle = stringResource(R.string.settings_clipboard_suggestions_subtitle),
                    checked = snapshot.clipboardSuggestionsEnabled,
                    enabled = snapshot.clipboardHistoryEnabled,
                    onCheckedChange = {
                        onSetBoolean(SettingsRepository.Keys.CLIPBOARD_SUGGESTIONS, it)
                    },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { showClipboardDeleteDialog = true },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.settings_clipboard_delete_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    supportingContent = {
                        Text(stringResource(R.string.settings_clipboard_delete_all_subtitle))
                    },
                )
            }

            // ------------------------------------------------- Voice typing
            item { SectionHeader(stringResource(R.string.settings_group_voice)) }
            item {
                SwitchRow(
                    title = "Remove filler words",
                    subtitle = "Drops \"um\", \"uh\", and similar sounds.",
                    checked = snapshot.removeFillers,
                    enabled = cleanupEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.FILLERS, it) },
                )
            }
            item {
                SwitchRow(
                    title = "Aggressive filler removal",
                    subtitle = "Also removes \"like\", \"you know\", and \"I mean\".",
                    checked = snapshot.aggressiveFillers,
                    enabled = cleanupEnabled && snapshot.removeFillers,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.FILLERS_AGGRESSIVE, it) },
                )
            }
            item {
                SwitchRow(
                    title = "Resolve self-corrections",
                    subtitle = "\"Meet at five — no, six\" becomes \"Meet at six\".",
                    checked = snapshot.resolveSelfCorrections,
                    enabled = cleanupEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.SELF_CORRECT, it) },
                )
            }
            item {
                SwitchRow(
                    title = "Auto punctuation",
                    subtitle = "Adds periods, commas, and question marks as you speak.",
                    checked = snapshot.autoPunctuate,
                    enabled = cleanupEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.AUTO_PUNCT, it) },
                )
            }
            item {
                SwitchRow(
                    title = "Spoken commands",
                    subtitle = "Say \"new line\", \"comma\", or \"delete that\" to edit.",
                    checked = snapshot.spokenCommands,
                    enabled = cleanupEnabled,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.SPOKEN_COMMANDS, it) },
                )
            }
            item {
                SwitchRow(
                    title = "Raw transcript mode",
                    subtitle = "Insert exactly what the recognizer hears, with no cleanup. " +
                        "Turning this on disables the cleanup options above.",
                    checked = snapshot.rawTranscriptMode,
                    onCheckedChange = { onSetBoolean(SettingsRepository.Keys.RAW_MODE, it) },
                )
            }
            item {
                if (llmInstalled) {
                    SwitchRow(
                        title = "Smart cleanup",
                        subtitle = "An on-device language model polishes grammar and formatting " +
                            "after you speak. Adds about 1–3 seconds before text is final.",
                        checked = snapshot.llmRefineEnabled,
                        enabled = cleanupEnabled,
                        onCheckedChange = { onSetBoolean(SettingsRepository.Keys.LLM_REFINE, it) },
                    )
                } else {
                    ListItem(
                        modifier = Modifier.clickable(onClick = onOpenModelDownloads),
                        headlineContent = { Text("Smart cleanup") },
                        supportingContent = {
                            Text(
                                "Needs the optional Smart cleanup model " +
                                    "(about ${formatBytes(llmPack?.totalBytes ?: 0L)}). Tap to download.",
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Download the Smart cleanup model",
                            )
                        },
                    )
                }
            }

            // ------------------------------------------------------- Models
            item { SectionHeader(stringResource(R.string.settings_group_models)) }
            items(count = ModelCatalog.packs.size) { index ->
                val pack = ModelCatalog.packs[index]
                ModelPackRow(
                    pack = pack,
                    state = packStates[pack.id] ?: PackState.NotInstalled,
                    onDownload = { ModelDownloadService.start(context, pack.id) },
                    onCancel = { ModelDownloadService.cancel(context) },
                    onDeleteRequest = { pendingDelete = pack },
                )
            }

            // ---------------------------------------------- About & privacy
            item { SectionHeader(stringResource(R.string.settings_group_about)) }
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text("Private by design") },
                    supportingContent = {
                        Text(
                            "All speech processing happens on your device. Audio never leaves " +
                                "your phone. Model downloads are the only network activity.",
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Model licenses") },
                    supportingContent = {
                        Column {
                            ModelCatalog.packs.forEach { pack ->
                                Text(
                                    text = pack.licenseNote,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text(appVersion) },
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showThemeDialog) {
        RadioDialog(
            title = stringResource(R.string.settings_theme),
            options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
            selected = snapshot.themeMode,
            label = { themeLabel(it) },
            onSelect = { onSetString(SettingsRepository.Keys.THEME, it.name) },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showAutocorrectDialog) {
        RadioDialog(
            title = stringResource(R.string.settings_autocorrect),
            options = listOf(
                AutocorrectMode.OFF,
                AutocorrectMode.CONSERVATIVE,
                AutocorrectMode.AGGRESSIVE,
            ),
            selected = snapshot.autocorrectMode,
            label = { autocorrectLabel(it) },
            onSelect = { onSetString(SettingsRepository.Keys.AUTOCORRECT, it.name) },
            onDismiss = { showAutocorrectDialog = false },
        )
    }

    if (showClipboardDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showClipboardDeleteDialog = false },
            title = { Text(stringResource(R.string.settings_clipboard_delete_all_confirm)) },
            text = { Text(stringResource(R.string.settings_clipboard_delete_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClipboardDeleteDialog = false
                        scope.launch(Dispatchers.IO) { ClipboardStore.deleteBlocking(context) }
                        // A running keyboard holds its own copy in memory. This
                        // timestamp is what tells it to let go of it.
                        onSetLong(
                            SettingsRepository.Keys.CLIPBOARD_CLEARED_AT,
                            System.currentTimeMillis(),
                        )
                    },
                ) {
                    Text(
                        text = stringResource(R.string.dialog_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClipboardDeleteDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    val packToDelete = pendingDelete
    if (packToDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${packToDelete.displayName}?") },
            text = {
                Text(
                    "This frees ${formatBytes(packToDelete.totalBytes)}. " +
                        "You can download it again anytime.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch(Dispatchers.IO) {
                            // Unload before deleting: the engines mmap these files,
                            // so deleting underneath them neither frees the space
                            // nor stops dictation from working against models the
                            // user believes they removed.
                            VoiceEngines.releaseAll()
                            runCatching { packInstaller.delete(packToDelete) }
                                .onFailure { Log.e(TAG, "pack delete failed", it) }
                            withContext(Dispatchers.Main) { diskTick++ }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.pack_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

// -------------------------------------------------------------- components

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        headlineContent = {
            Text(
                text = title,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
    )
}

@Composable
private fun <T> RadioDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(option)
                                    onDismiss()
                                },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun ModelPackRow(
    pack: ModelPack,
    state: PackState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Column {
        ListItem(
            headlineContent = { Text(pack.displayName) },
            supportingContent = {
                val stateText = when (state) {
                    PackState.NotInstalled -> stringResource(R.string.pack_not_installed)
                    is PackState.Downloading -> stringResource(
                        R.string.pack_downloading_percent,
                        (state.fraction * 100).toInt(),
                    )
                    PackState.Verifying -> stringResource(R.string.pack_verifying)
                    PackState.Installed -> stringResource(R.string.pack_installed)
                    is PackState.Failed -> installErrorText(state.error, pack)
                }
                Text("${formatBytes(pack.totalBytes)} · $stateText")
            },
            trailingContent = {
                when (state) {
                    PackState.Installed -> IconButton(onClick = onDeleteRequest) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete ${pack.displayName}",
                        )
                    }
                    is PackState.Downloading -> IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.pack_cancel),
                        )
                    }
                    PackState.Verifying -> IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.pack_cancel),
                        )
                    }
                    is PackState.Failed -> TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.pack_retry))
                    }
                    PackState.NotInstalled -> TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.pack_download))
                    }
                }
            },
        )
        if (state is PackState.Downloading) {
            LinearProgressIndicator(
                progress = { state.fraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}
