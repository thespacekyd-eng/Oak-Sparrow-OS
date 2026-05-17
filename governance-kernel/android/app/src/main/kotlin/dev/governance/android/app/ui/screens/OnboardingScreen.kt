package dev.governance.android.app.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.governance.android.app.R
import kotlinx.coroutines.launch

/**
 * Three-page onboarding inside a HorizontalPager with dot indicators.
 * Skip button on each page allows skipping. Onboarding completion
 * is recorded externally by the hosting activity.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageCount = 5
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val roleManager = context.getSystemService(RoleManager::class.java)

    val assistantRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { scope.launch { pagerState.animateScrollToPage(3) } }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { scope.launch { pagerState.animateScrollToPage(4) } }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onComplete() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.btn_skip))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> OnboardingPage(
                    icon = Icons.Filled.Star,
                    title = stringResource(R.string.onboarding_title_1),
                    body = stringResource(R.string.onboarding_body_1),
                    buttonText = stringResource(R.string.btn_next),
                    onAction = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                1 -> OnboardingPage(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.onboarding_title_2),
                    body = stringResource(R.string.onboarding_body_2),
                    buttonText = stringResource(R.string.btn_open_settings),
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                )
                2 -> OnboardingPage(
                    icon = Icons.Filled.PhoneAndroid,
                    title = stringResource(R.string.onboarding_title_4),
                    body = stringResource(R.string.onboarding_body_4),
                    buttonText = stringResource(R.string.btn_set_assistant),
                    onAction = {
                        if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                            !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                        ) {
                            assistantRoleLauncher.launch(
                                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                            )
                        } else {
                            scope.launch { pagerState.animateScrollToPage(3) }
                        }
                    },
                )
                3 -> OnboardingPage(
                    icon = Icons.Filled.Layers,
                    title = stringResource(R.string.onboarding_title_5),
                    body = stringResource(R.string.onboarding_body_5),
                    buttonText = stringResource(R.string.btn_allow_overlay),
                    onAction = {
                        if (!Settings.canDrawOverlays(context)) {
                            overlayLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } else {
                            scope.launch { pagerState.animateScrollToPage(4) }
                        }
                    },
                )
                4 -> OnboardingPage(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(R.string.onboarding_title_3),
                    body = stringResource(R.string.onboarding_body_3),
                    buttonText = stringResource(R.string.btn_grant),
                    onAction = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
        }

        // Dot indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pageCount) { i ->
                val color = if (i == pagerState.currentPage)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outlineVariant
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = color,
                    content = {},
                )
            }
        }
    }
}

@Composable
internal fun OnboardingPage(
    icon: ImageVector,
    title: String,
    body: String,
    buttonText: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAction) {
            Text(buttonText)
        }
    }
}
