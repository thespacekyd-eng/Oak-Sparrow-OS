package dev.governance.android.app.agent

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import dev.governance.android.app.AssistantActivity
import dev.governance.android.platform.AccessibilityObservationService
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.GateDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Dispatches approved actions to the device. Verifies the
 * decision's attestation before every dispatch.
 *
 * Supported kinds: read_calendar, send_email, share_to_social_app,
 * open_app, send_sms, make_call, set_alarm, set_timer, open_url,
 * search_web, get_directions, take_photo, change_setting, play_music,
 * create_event.
 *
 * All other kinds return [DispatchResult.Unsupported].
 */
class ActionDispatcher(
    private val context: Context,
    /** LLM engine for the agent loop (ui_interact). Set after construction. */
    var agentLoopEngine: LlmEngine? = null,
) {

    suspend fun dispatch(decision: GateDecision, step: PlannedStep): DispatchResult =
        withContext(Dispatchers.Main) {
            // Re-verify attestation before every dispatch
            if (!AttestationVerifier.verify(decision)) {
                return@withContext DispatchResult.Failed(
                    "Attestation verification failed. Action not executed."
                )
            }

            dispatchByKind(step)
        }

    /**
     * Speculative dispatch — fires the reversible work for a step WITHOUT
     * requiring a signed [GateDecision]. Used by [SpeculativeOrchestrator]
     * to launch the intent immediately while the kernel decides in parallel.
     *
     * Hard requirements:
     * - Caller must verify the step is FullyReversible AND App-tier before
     *   calling this. Speculative dispatch on OneShot/Irreversible/RootSystem
     *   actions is forbidden — those must wait for the kernel decision.
     * - The audit log is NOT written by this method. The real signed decision
     *   that arrives from the kernel produces the audit record.
     */
    suspend fun dispatchSpeculative(step: PlannedStep): DispatchResult =
        withContext(Dispatchers.Main) {
            require(step.reversibility == dev.governance.core.Reversibility.FullyReversible) {
                "Speculative dispatch only allowed for FullyReversible steps; got ${step.reversibility}"
            }
            require(dev.governance.core.ActionTier.classify(step.kind) ==
                dev.governance.core.ActionTier.App) {
                "Speculative dispatch only allowed for App-tier kinds; '${step.kind}' is RootSystem"
            }
            dispatchByKind(step)
        }

    private suspend fun dispatchByKind(step: PlannedStep): DispatchResult {
        // Hide the assistant overlay for actions that launch external apps,
        // so the target app is visible and usable.
        val launchesApp = step.kind in setOf(
            "open_app", "send_sms", "make_call", "send_email", "share_to_social_app",
            "open_url", "search_web", "get_directions", "take_photo", "play_music",
            "set_alarm", "set_timer", "create_event",
        )
        if (launchesApp) {
            Log.i("ActionDispatcher", "Hiding overlay for ${step.kind}")
            AssistantActivity.hideOverlay()
            delay(200) // Let overlay start hiding before launching the target app
        }

        return when (step.kind) {
            "read_calendar" -> dispatchReadCalendar()
            "send_email" -> dispatchSendEmail(step.target ?: "", step.message)
            "share_to_social_app" -> dispatchShareToSocial(step.target ?: "")
            "open_app" -> dispatchOpenApp(step.target ?: "")
            "send_sms" -> dispatchSendSms(step.target ?: "", step.message)
            "make_call" -> dispatchMakeCall(step.target ?: "")
            "set_alarm" -> dispatchSetAlarm(step.target ?: "")
            "set_timer" -> dispatchSetTimer(step.target ?: "")
            "open_url" -> dispatchOpenUrl(step.target ?: "")
            "search_web" -> dispatchSearchWeb(step.target ?: "")
            "get_directions" -> dispatchGetDirections(step.target ?: "")
            "take_photo" -> dispatchTakePhoto()
            "change_setting" -> dispatchChangeSetting(step.target ?: "")
            "play_music" -> dispatchPlayMusic(step.target ?: "")
            "create_event" -> dispatchCreateEvent(step.target ?: "", step.message)
            "set_wallpaper" -> dispatchSetWallpaper()
            "set_volume" -> dispatchSetVolume(step.target ?: "")
            "toggle_flashlight" -> dispatchToggleFlashlight()
            "toggle_dnd" -> dispatchToggleDnd()
            "custom_intent" -> dispatchCustomIntent(step.target ?: "", step.message)
            "ui_interact" -> dispatchUiInteract(step.target ?: "", step.message)
            else -> DispatchResult.Unsupported(
                "Action '${step.kind}' isn't yet supported. The agent will skip it."
            )
        }
    }

    private suspend fun dispatchReadCalendar(): DispatchResult {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            delay(2000) // wait for calendar to render

            // Try to read event text via accessibility
            val text = AccessibilityObservationService.readCurrentWindowText()
            return if (text != null && text.length > 10) {
                DispatchResult.Success("Calendar: $text")
            } else {
                DispatchResult.Success("Opened Calendar app.")
            }
        } catch (e: Exception) {
            return DispatchResult.Failed("Could not open Calendar: ${e.message}")
        }
    }

    private suspend fun dispatchSendEmail(target: String, message: String?): DispatchResult {
        try {
            val mailto = if (target.contains("@")) target else "$target@example.com"
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$mailto")
                putExtra(Intent.EXTRA_SUBJECT, "From Oak & Sparrow")
                putExtra(Intent.EXTRA_TEXT, message ?: "")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            delay(3000)

            val sent = AccessibilityObservationService.clickByText("Send")
            return if (sent) {
                delay(1000)
                DispatchResult.Success("Email sent to $target.")
            } else {
                DispatchResult.Success("Email compose opened for $target. Tap Send to complete.")
            }
        } catch (e: Exception) {
            return DispatchResult.Failed("Could not compose email: ${e.message}")
        }
    }

    private suspend fun dispatchOpenApp(target: String): DispatchResult {
        if (target.isBlank()) {
            return DispatchResult.Failed("No app name provided.")
        }
        return try {
            // Resolve human-friendly app names to launch intents.
            val pm = context.packageManager
            val candidate = APP_NAME_TO_PACKAGE[target.lowercase()]
                ?: target // user may have typed a package name directly

            val launchIntent = pm.getLaunchIntentForPackage(candidate)
                ?: pm.getLaunchIntentForPackage(target.lowercase())

            if (launchIntent == null) {
                return DispatchResult.Failed("Could not find $target on this device.")
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            DispatchResult.Success("Opened $target.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open $target: ${e.message}")
        }
    }

    private fun dispatchShareToSocial(target: String): DispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            DispatchResult.Success("Share sheet opened. Choose an app to share.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open share sheet: ${e.message}")
        }
    }

    private fun dispatchSendSms(target: String, message: String?): DispatchResult {
        return try {
            // If target is already a phone number, use it directly
            val number = if (target.any { it.isDigit() }) {
                target.replace(Regex("[^0-9+*#]"), "")
            } else {
                // Look up contact name → phone number
                resolveContactNumber(target)
            }

            val uri = if (number != null) "smsto:$number" else "smsto:"
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse(uri)
                putExtra("sms_body", message ?: "")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val desc = if (number != null) "$target ($number)" else target
            DispatchResult.Success("SMS compose opened for $desc.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open SMS: ${e.message}")
        }
    }

    /**
     * Resolves a contact display name to their phone number.
     * Uses a case-insensitive LIKE query on the contacts provider.
     */
    private fun resolveContactNumber(name: String): String? {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf(name),
                null,
            )
            if (cursor != null && cursor.moveToFirst()) {
                val number = cursor.getString(0)
                Log.i("ActionDispatcher", "Resolved contact '$name' → $number")
                return number
            }
            // Try partial/fuzzy match
            cursor?.close()
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null,
            )
            if (cursor != null && cursor.moveToFirst()) {
                val matchedName = cursor.getString(0)
                val number = cursor.getString(1)
                Log.i("ActionDispatcher", "Fuzzy resolved '$name' → '$matchedName' $number")
                return number
            }
        } catch (e: Exception) {
            Log.w("ActionDispatcher", "Contact lookup failed: ${e.message}")
        } finally {
            cursor?.close()
        }
        Log.w("ActionDispatcher", "No contact found for '$name'")
        return null
    }

    private fun dispatchMakeCall(target: String): DispatchResult {
        return try {
            val number = target.replace(Regex("[^0-9+*#]"), "")
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Dialer opened for $number.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open dialer: ${e.message}")
        }
    }

    private fun dispatchSetAlarm(target: String): DispatchResult {
        return try {
            // Parse "7:30 am", "7am", "14:00", etc.
            val hourMin = parseTime(target)
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hourMin.first)
                putExtra(AlarmClock.EXTRA_MINUTES, hourMin.second)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Alarm set for ${hourMin.first}:${"%02d".format(hourMin.second)}.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not set alarm: ${e.message}")
        }
    }

    private fun dispatchSetTimer(target: String): DispatchResult {
        return try {
            val seconds = parseDuration(target)
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Timer set for ${seconds / 60} min ${seconds % 60} sec.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not set timer: ${e.message}")
        }
    }

    private fun dispatchOpenUrl(target: String): DispatchResult {
        return try {
            val url = if (target.startsWith("http")) target else "https://$target"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Opened $url.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open URL: ${e.message}")
        }
    }

    private fun dispatchSearchWeb(target: String): DispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=${Uri.encode(target)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Searching for \"$target\".")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not search: ${e.message}")
        }
    }

    private fun dispatchGetDirections(target: String): DispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("google.navigation:q=${Uri.encode(target)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Fall back to maps web if no maps app
            try { context.startActivity(intent) }
            catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://maps.google.com/maps?daddr=${Uri.encode(target)}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            }
            DispatchResult.Success("Getting directions to $target.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not get directions: ${e.message}")
        }
    }

    private fun dispatchTakePhoto(): DispatchResult {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Camera opened.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open camera: ${e.message}")
        }
    }

    private fun dispatchChangeSetting(target: String): DispatchResult {
        return try {
            val settingsAction = SETTING_NAME_TO_ACTION[target.lowercase()]
                ?: Settings.ACTION_SETTINGS
            val intent = Intent(settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Opened ${target.ifBlank { "device" }} settings.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open settings: ${e.message}")
        }
    }

    private fun dispatchPlayMusic(target: String): DispatchResult {
        return try {
            // Try to open a music app; fall back to search
            val intent = if (target.isNotBlank()) {
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, target)
                    putExtra(android.app.SearchManager.QUERY, target)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MUSIC)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            DispatchResult.Success(if (target.isNotBlank()) "Playing \"$target\"." else "Music app opened.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not play music: ${e.message}")
        }
    }

    private fun dispatchCreateEvent(target: String, message: String?): DispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                putExtra(android.provider.CalendarContract.Events.TITLE, target)
                putExtra(android.provider.CalendarContract.Events.DESCRIPTION, message ?: "")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Calendar event creation opened for \"$target\".")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not create event: ${e.message}")
        }
    }

    private fun dispatchSetWallpaper(): DispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Wallpaper picker opened.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open wallpaper picker: ${e.message}")
        }
    }

    private fun dispatchSetVolume(target: String): DispatchResult {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val lower = target.lowercase()
            when {
                lower.contains("mute") || lower.contains("silent") -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    DispatchResult.Success("Volume muted.")
                }
                lower.contains("max") || lower.contains("full") -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    DispatchResult.Success("Volume set to max.")
                }
                lower.contains("up") || lower.contains("raise") || lower.contains("louder") -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    DispatchResult.Success("Volume raised.")
                }
                lower.contains("down") || lower.contains("lower") || lower.contains("quiet") -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    DispatchResult.Success("Volume lowered.")
                }
                else -> {
                    // Try to parse a number (0-100 scale)
                    val pct = Regex("\\d+").find(lower)?.value?.toIntOrNull()
                    if (pct != null) {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val vol = (pct * max / 100).coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, AudioManager.FLAG_SHOW_UI)
                        DispatchResult.Success("Volume set to $pct%.")
                    } else {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                        DispatchResult.Success("Volume panel shown.")
                    }
                }
            }
        } catch (e: Exception) {
            DispatchResult.Failed("Could not adjust volume: ${e.message}")
        }
    }

    private fun dispatchToggleFlashlight(): DispatchResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return DispatchResult.Failed("No camera available.")
            // Toggle: try to enable; if it fails, it might already be on
            cm.setTorchMode(cameraId, true)
            DispatchResult.Success("Flashlight turned on.")
        } catch (e: Exception) {
            // If already on, turning on again throws — try turning off
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull()
                    ?: return DispatchResult.Failed("No camera available.")
                cm.setTorchMode(id, false)
                DispatchResult.Success("Flashlight turned off.")
            } catch (_: Exception) {
                DispatchResult.Failed("Could not toggle flashlight: ${e.message}")
            }
        }
    }

    private fun dispatchToggleDnd(): DispatchResult {
        return try {
            val intent = Intent("android.settings.ZEN_MODE_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.Success("Do Not Disturb settings opened.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open DND settings: ${e.message}")
        }
    }

    /**
     * Multi-step UI interaction via the accessibility agent loop.
     * The LLM observes the screen, reasons about what to do, and
     * executes taps/scrolls/types in a loop until the task is done.
     *
     * Before running, hides the assistant overlay so the target app's
     * window is in the foreground with a full accessibility tree.
     * After the loop completes, brings the overlay back.
     */
    private suspend fun dispatchUiInteract(task: String, extra: String?): DispatchResult {
        return try {
            if (dev.governance.android.app.BuildConfig.CLOUD_API_KEY.isBlank()) {
                return DispatchResult.Failed(
                    "Cloud API key required for complex UI tasks. Configure in Settings."
                )
            }

            // Hide the assistant overlay so the target app gets full
            // accessibility tree access (background windows are truncated)
            AssistantActivity.hideOverlay()

            // Wait for the target app to actually reach the foreground.
            delay(3000)

            // Use vision-based agent loop — takes screenshots and sends
            // to Claude vision for reasoning. Works on ANY app regardless
            // of accessibility tree quality.
            val visionLoop = VisionAgentLoop(
                apiKey = dev.governance.android.app.BuildConfig.CLOUD_API_KEY,
            )
            val result = visionLoop.execute(task)

            // Bring the assistant overlay back
            withContext(Dispatchers.Main) {
                AssistantActivity.showOverlay()
            }

            if (result.success) {
                DispatchResult.Success(result.summary + " (${result.stepsExecuted} steps)")
            } else {
                DispatchResult.Failed(result.summary)
            }
        } catch (e: Exception) {
            // Try to restore overlay even on error
            try { withContext(Dispatchers.Main) { AssistantActivity.showOverlay() } } catch (_: Exception) {}
            DispatchResult.Failed("UI interaction failed: ${e.message}")
        }
    }

    /**
     * General-purpose intent launcher. The LLM specifies:
     * - target = intent action (e.g., "android.intent.action.SET_WALLPAPER")
     * - message = optional data URI
     */
    private fun dispatchCustomIntent(action: String, dataUri: String?): DispatchResult {
        return try {
            val intent = Intent(action).apply {
                if (!dataUri.isNullOrBlank()) data = Uri.parse(dataUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i("ActionDispatcher", "custom_intent: action=$action data=$dataUri")
            DispatchResult.Success("Launched: $action")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not launch intent '$action': ${e.message}")
        }
    }

    // --- Parsing helpers ---

    private fun parseTime(input: String): Pair<Int, Int> {
        // Handles: "7am", "7:30am", "7:30 am", "14:00", "7"
        val cleaned = input.lowercase().trim()
        val isPm = cleaned.contains("pm")
        val digits = cleaned.replace(Regex("[^0-9:]"), "")
        val parts = digits.split(":")
        var hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (isPm && hour < 12) hour += 12
        if (!isPm && cleaned.contains("am") && hour == 12) hour = 0
        return Pair(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    private fun parseDuration(input: String): Int {
        // Handles: "5 minutes", "30 seconds", "1 hour", "90s", "5m"
        val cleaned = input.lowercase().trim()
        val num = Regex("(\\d+)").find(cleaned)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        return when {
            cleaned.contains("hour") || cleaned.endsWith("h") -> num * 3600
            cleaned.contains("second") || cleaned.endsWith("s") -> num
            else -> num * 60 // default to minutes
        }
    }

    companion object {
        private val SETTING_NAME_TO_ACTION = mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "bt" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "airplane mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "brightness" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "volume" to Settings.ACTION_SOUND_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "network" to Settings.ACTION_WIRELESS_SETTINGS,
        )
        /**
         * Common app names → package names. Used by [dispatchOpenApp] to
         * resolve human-friendly names like "instagram" to package ids
         * like "com.instagram.android". Not exhaustive — falls back to
         * treating the user's input as a package name directly.
         */
        internal val APP_NAME_TO_PACKAGE = mapOf(
            "instagram" to "com.instagram.android",
            "ig" to "com.instagram.android",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "calendar" to "com.google.android.calendar",
            "messages" to "com.google.android.apps.messaging",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "settings" to "com.android.settings",
            "spotify" to "com.spotify.music",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "whatsapp" to "com.whatsapp",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "slack" to "com.Slack",
            "photos" to "com.google.android.apps.photos",
            "camera" to "com.android.camera",
            "files" to "com.google.android.apps.nbu.files",
            "play store" to "com.android.vending",
            "store" to "com.android.vending",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "contacts" to "com.google.android.contacts",
            "phone" to "com.google.android.dialer",
            "notes" to "com.google.android.keep",
            "drive" to "com.google.android.apps.docs",
        )
    }
}
