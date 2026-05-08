package dev.governance.android.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import dev.governance.android.app.ui.screens.OnboardingScreen
import dev.governance.android.app.ui.theme.OakSparrowTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")
val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("complete")

/**
 * Launcher activity. Checks onboarding completion in DataStore;
 * if done, skips straight to [MainActivity]. Otherwise shows the
 * three-page [OnboardingScreen].
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val complete = runBlocking {
            onboardingDataStore.data.map { it[KEY_ONBOARDING_COMPLETE] ?: false }.first()
        }
        if (complete) {
            navigateToMain()
            return
        }

        setContent {
            OakSparrowTheme {
                OnboardingScreen(
                    onComplete = {
                        lifecycleScope.launch {
                            onboardingDataStore.edit { it[KEY_ONBOARDING_COMPLETE] = true }
                        }
                        startForegroundService(
                            Intent(this@OnboardingActivity, GovernanceKernelService::class.java)
                        )
                        navigateToMain()
                    },
                    onSkip = {
                        // Record skipped so we don't re-prompt, but note it's impaired
                        lifecycleScope.launch {
                            onboardingDataStore.edit { it[KEY_ONBOARDING_COMPLETE] = true }
                        }
                        navigateToMain()
                    },
                )
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
