package com.vboard.app.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vboard.app.VBoardApp
import com.vboard.app.models.ModelDownloadService
import com.vboard.app.settings.SettingsActivity
import com.vboard.app.ui.VBoardM3Theme
import com.vboard.app.ui.resolveDark
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelReadiness
import com.vboard.core.model.NetworkState

/**
 * Linear first-run setup: welcome → enable IME → select IME → mic permission →
 * model downloads → done. Jumps to the first incomplete step on launch, and
 * re-checks system state whenever the window regains focus (the IME settings
 * screen and picker dialog both hand focus back on completion).
 *
 * The download step is never a gate. Setup can be finished with nothing downloaded — the
 * keyboard types either way — and [SetupState] remembers that so a user who chose to skip is
 * not dropped back onto the download screen by every subsequent launch.
 */
class OnboardingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_STEP = "com.vboard.app.extra.TARGET_STEP"
        const val TARGET_STEP_MODELS = "models"

        /** Deep link straight to the model-download step (used by settings). */
        fun modelsIntent(context: Context): Intent =
            Intent(context, OnboardingActivity::class.java)
                .putExtra(EXTRA_TARGET_STEP, TARGET_STEP_MODELS)

        /**
         * Intent for offering the voice-model download off the back of something the user did
         * for another reason — a mic tap with no models installed.
         *
         * Returns null when the offer has already been made in this process: PRODUCT_SPEC
         * VB-408 says the app never nags more than once per session, and a user who has
         * already declined once is telling us they are happy typing.
         */
        fun modelPromptIntentOrNull(context: Context): Intent? =
            if (SetupState.claimPromptSlot()) {
                modelsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                null
            }
    }

    private var currentStep by mutableStateOf(OnboardingStep.WELCOME)
    private var imeEnabled by mutableStateOf(false)
    private var imeSelected by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var networkState by mutableStateOf(NetworkState.OFFLINE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VBoardApp
        refreshSystemState()
        val deepLinkedToModels = intent?.getStringExtra(EXTRA_TARGET_STEP) == TARGET_STEP_MODELS
        currentStep = if (deepLinkedToModels) {
            // Arriving here from settings or a mic tap: this *is* the offer, so it counts as
            // the session's one prompt even if the caller did not go through
            // modelPromptIntentOrNull.
            SetupState.claimPromptSlot()
            OnboardingStep.MODELS
        } else {
            firstIncompleteStep(app)
        }
        setContent {
            val snapshot by app.settings.snapshot.collectAsState()
            val serviceStates by ModelDownloadService.states.collectAsState()
            val scheduledFlow = remember { ModelDownloadService.observeScheduledWork(this) }
            val scheduled by scheduledFlow.collectAsState(initial = emptyList())
            val packStates = ModelCatalog.packs.associate { pack ->
                pack.id to effectivePackState(app.packInstaller, pack, serviceStates[pack.id])
            }
            VBoardM3Theme(darkTheme = snapshot.themeMode.resolveDark()) {
                OnboardingFlow(
                    step = currentStep,
                    imeEnabled = imeEnabled,
                    imeSelected = imeSelected,
                    micGranted = micGranted,
                    packStates = packStates,
                    scheduled = scheduled,
                    networkState = networkState,
                    onStepChange = { step ->
                        currentStep = step
                        // Reaching the end counts as done however the user got there, with or
                        // without downloads.
                        if (step == OnboardingStep.DONE) SetupState.markComplete(this)
                    },
                    onOpenImeSettings = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onShowImePicker = {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    onRecheckSystemState = { refreshAndAdvance() },
                    onMicResult = { granted ->
                        micGranted = granted
                        if (granted && currentStep == OnboardingStep.MIC) {
                            currentStep = OnboardingStep.MODELS
                        }
                    },
                    onOpenAppSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            ),
                        )
                    },
                    onDownloadPack = { packId, allowMetered ->
                        if (allowMetered) {
                            ModelDownloadService.startAllowingMetered(this, packId)
                        } else {
                            ModelDownloadService.start(this, packId)
                        }
                    },
                    onCancelDownloads = {
                        ModelDownloadService.cancel(this)
                    },
                    onOpenSettings = {
                        SetupState.markComplete(this)
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                    },
                    onFinished = {
                        SetupState.markComplete(this)
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAndAdvance()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshAndAdvance()
    }

    // ------------------------------------------------------------- detection

    private fun refreshSystemState() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imeEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        imeSelected = Settings.Secure
            .getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.contains(packageName) == true
        micGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        networkState = ModelDownloadService.networkState(this)
    }

    private fun refreshAndAdvance() {
        refreshSystemState()
        if (currentStep == OnboardingStep.ENABLE && imeEnabled) {
            currentStep = OnboardingStep.SELECT
        }
        if (currentStep == OnboardingStep.SELECT && imeSelected) {
            currentStep = OnboardingStep.MIC
        }
    }

    private fun firstIncompleteStep(app: VBoardApp): OnboardingStep {
        val installedIds = app.modelStore.installedPackIds(app.packInstaller)
        val canDictate = ModelReadiness.canDictate(installedIds)
        return when {
            !imeEnabled -> OnboardingStep.WELCOME
            !imeSelected -> OnboardingStep.SELECT
            !micGranted -> OnboardingStep.MIC
            // Only steer a user to the downloads if they have never finished setup. Once
            // they have chosen to skip, the launcher icon must not re-open onto the screen
            // they walked away from (PRODUCT_SPEC VB-408).
            !canDictate && !SetupState.isComplete(this) -> OnboardingStep.MODELS
            else -> OnboardingStep.DONE
        }
    }
}
