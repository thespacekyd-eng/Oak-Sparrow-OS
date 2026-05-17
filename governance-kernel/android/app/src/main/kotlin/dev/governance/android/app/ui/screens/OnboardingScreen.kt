package dev.governance.android.app.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.governance.android.app.R
import dev.governance.android.app.ui.theme.OakPalette
import kotlinx.coroutines.launch

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
            .background(OakPalette.Background)
            .padding(24.dp),
    ) {
        // Skip button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onSkip) {
                Text(
                    stringResource(R.string.btn_skip),
                    color = OakPalette.TextTertiary,
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> OnboardingPage(
                    icon = Icons.Filled.Park,
                    title = stringResource(R.string.onboarding_title_1),
                    body = stringResource(R.string.onboarding_body_1),
                    buttonText = stringResource(R.string.btn_next),
                    onAction = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                1 -> OnboardingPage(
                    icon = Icons.Filled.Accessibility,
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
                        try {
                            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                                !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                            ) {
                                assistantRoleLauncher.launch(
                                    roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                                )
                            } else {
                                // Role already held or not available — open assist settings
                                context.startActivity(
                                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                scope.launch { pagerState.animateScrollToPage(3) }
                            }
                        } catch (_: Exception) {
                            // Fallback: open general assist settings
                            context.startActivity(
                                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
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
                val isActive = i == pagerState.currentPage
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isActive) 10.dp else 8.dp),
                    shape = CircleShape,
                    color = if (isActive) OakPalette.Primary else OakPalette.OutlineVariant,
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
        // Icon in a circle
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = OakPalette.PrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = OakPalette.Primary,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = OakPalette.TextPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = OakPalette.TextSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
        )

        Spacer(Modifier.height(36.dp))

        Button(
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = OakPalette.Primary,
                contentColor = OakPalette.OnPrimary,
            ),
        ) {
            Text(
                buttonText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
