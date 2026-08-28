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
import androidx.compose.runtime.setValue
import com.vboard.app.VBoardApp
import com.vboard.app.models.ModelDownloadService
import com.vboard.app.settings.SettingsActivity
import com.vboard.app.ui.VBoardM3Theme
import com.vboard.app.ui.resolveDark
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.PackState

/**
 * Linear first-run setup: welcome → enable IME → select IME → mic permission →
 * model downloads → done. Jumps to the first incomplete step on launch, and
 * re-checks system state whenever the window regains focus (the IME settings
 * screen and picker dialog both hand focus back on completion).
 */
class OnboardingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_STEP = "com.vboard.app.extra.TARGET_STEP"
        const val TARGET_STEP_MODELS = "models"

        /** Deep link straight to the model-download step (used by settings). */
        fun modelsIntent(context: Context): Intent =
            Intent(context, OnboardingActivity::class.java)
                .putExtra(EXTRA_TARGET_STEP, TARGET_STEP_MODELS)
    }

    private var currentStep by mutableStateOf(OnboardingStep.WELCOME)
    private var imeEnabled by mutableStateOf(false)
    private var imeSelected by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VBoardApp
        refreshSystemState()
        currentStep = if (intent?.getStringExtra(EXTRA_TARGET_STEP) == TARGET_STEP_MODELS) {
            OnboardingStep.MODELS
        } else {
            firstIncompleteStep(app)
        }
        setContent {
            val snapshot by app.settings.snapshot.collectAsState()
            val serviceStates by ModelDownloadService.states.collectAsState()
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
                    onStepChange = { currentStep = it },
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
                    onDownloadPack = { packId ->
                        ModelDownloadService.start(this, packId)
                    },
                    onCancelDownloads = {
                        ModelDownloadService.cancel(this)
                    },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                    },
                    onFinished = { finish() },
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
        val requiredInstalled = ModelCatalog.packs
            .filter { it.required }
            .all { app.packInstaller.stateOf(it) == PackState.Installed }
        return when {
            !imeEnabled -> OnboardingStep.WELCOME
            !imeSelected -> OnboardingStep.SELECT
            !micGranted -> OnboardingStep.MIC
            !requiredInstalled -> OnboardingStep.MODELS
            else -> OnboardingStep.DONE
        }
    }
}
