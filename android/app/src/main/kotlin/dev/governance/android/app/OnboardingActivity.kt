package dev.governance.android.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")
private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("complete")

/**
 * Three-screen onboarding flow:
 * 1. Explain governance
 * 2. Prompt accessibility access
 * 3. Prompt overlay permission
 *
 * Records completion in DataStore and does not re-prompt.
 * Visually crude — Phase 2B polish.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already completed, go directly to MainActivity
        val complete = runBlocking {
            onboardingDataStore.data.map { it[KEY_ONBOARDING_COMPLETE] ?: false }.first()
        }
        if (complete) {
            navigateToMain()
            return
        }

        setContent {
            MaterialTheme {
                OnboardingFlow(
                    onComplete = {
                        lifecycleScope.launch {
                            onboardingDataStore.edit { it[KEY_ONBOARDING_COMPLETE] = true }
                        }
                        // Start the governance service
                        startForegroundService(
                            Intent(this@OnboardingActivity, GovernanceKernelService::class.java)
                        )
                        navigateToMain()
                    }
                )
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
private fun OnboardingFlow(onComplete: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (page) {
                0 -> OnboardingPage(
                    title = stringResource(R.string.onboarding_title_1),
                    body = stringResource(R.string.onboarding_body_1),
                    buttonText = stringResource(R.string.btn_next),
                    onAction = { page = 1 },
                )

                1 -> OnboardingPage(
                    title = stringResource(R.string.onboarding_title_2),
                    body = stringResource(R.string.onboarding_body_2),
                    buttonText = stringResource(R.string.btn_grant_accessibility),
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        page = 2
                    },
                )

                2 -> OnboardingPage(
                    title = stringResource(R.string.onboarding_title_3),
                    body = stringResource(R.string.onboarding_body_3),
                    buttonText = if (Settings.canDrawOverlays(context))
                        stringResource(R.string.btn_finish)
                    else
                        stringResource(R.string.btn_grant_overlay),
                    onAction = {
                        if (Settings.canDrawOverlays(context)) {
                            onComplete()
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OnboardingPage(
    title: String,
    body: String,
    buttonText: String,
    onAction: () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(onClick = onAction) {
        Text(buttonText)
    }
}
