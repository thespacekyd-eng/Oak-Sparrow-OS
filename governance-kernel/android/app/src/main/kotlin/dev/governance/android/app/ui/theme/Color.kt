package dev.governance.android.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Oak & Sparrow brand palette — dark-first, nature-inspired.
 * Deep charcoal backgrounds with warm sage-green accents.
 */
object OakPalette {
    // Surfaces — deep, rich blacks
    val Background = Color(0xFF0D0D0F)
    val Surface = Color(0xFF141416)
    val SurfaceVariant = Color(0xFF1C1C20)
    val SurfaceElevated = Color(0xFF222226)
    val SurfaceBright = Color(0xFF2A2A2E)

    // Primary — sage green (the "Oak" feel)
    val Primary = Color(0xFF7CB68E)
    val PrimaryDark = Color(0xFF5A9A6E)
    val PrimaryContainer = Color(0xFF1A3324)
    val OnPrimary = Color(0xFF0D0D0F)
    val OnPrimaryContainer = Color(0xFFA8D8B8)

    // Secondary — warm teal
    val Secondary = Color(0xFF5EAAB4)
    val SecondaryContainer = Color(0xFF1A2F32)
    val OnSecondary = Color(0xFF0D0D0F)
    val OnSecondaryContainer = Color(0xFF90D0D8)

    // Text
    val TextPrimary = Color(0xFFE8E8EA)
    val TextSecondary = Color(0xFF9A9AA0)
    val TextTertiary = Color(0xFF666670)

    // User message bubble
    val UserBubble = Color(0xFF2E5A42)
    val OnUserBubble = Color(0xFFE0F0E6)

    // Agent message — no bubble, just text on surface
    val AgentText = Color(0xFFD8D8DC)

    // Accents
    val Error = Color(0xFFE57373)
    val ErrorContainer = Color(0xFF3D1A1A)
    val Outline = Color(0xFF2E2E34)
    val OutlineVariant = Color(0xFF3A3A42)

    // Drawer
    val DrawerBackground = Color(0xFF111114)
    val DrawerSelectedItem = Color(0xFF1A2A20)
}
