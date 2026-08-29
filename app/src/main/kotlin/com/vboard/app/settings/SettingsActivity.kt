package com.vboard.app.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.vboard.app.VBoardApp
import com.vboard.app.onboarding.OnboardingActivity
import com.vboard.app.ui.VBoardM3Theme
import com.vboard.app.ui.resolveDark
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VBoardApp
        val appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        setContent {
            val snapshot by app.settings.snapshot.collectAsState()
            VBoardM3Theme(darkTheme = snapshot.themeMode.resolveDark()) {
                SettingsScreen(
                    snapshot = snapshot,
                    packInstaller = app.packInstaller,
                    appVersion = appVersion,
                    onSetBoolean = { key, value ->
                        lifecycleScope.launch { app.settings.setBoolean(key, value) }
                    },
                    onSetString = { key, value ->
                        lifecycleScope.launch { app.settings.setString(key, value) }
                    },
                    onSetLong = { key, value ->
                        lifecycleScope.launch { app.settings.setLong(key, value) }
                    },
                    onOpenModelDownloads = {
                        startActivity(OnboardingActivity.modelsIntent(this@SettingsActivity))
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}
