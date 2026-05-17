package dev.governance.android.app.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import dev.governance.android.platform.AccessibilityObservationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Vision-based agent loop: takes screenshots of the screen and sends
 * them to Claude's vision API for reasoning. Claude sees the actual
 * pixels and returns tap coordinates, scroll directions, or text to type.
 *
 * This replaces the text-based [AgentLoop] which relied on the
 * accessibility tree — many apps (Instagram, TikTok, etc.) don't
 * expose enough accessibility info for text-based reasoning.
 *
 * ## Flow
 *
 * 1. Take screenshot via AccessibilityService.takeScreenshot()
 * 2. Encode as JPEG, base64
 * 3. Send to Claude vision: "Here's the screen. Task: X. What to do?"
 * 4. Claude returns: TAP 540 1200 | SCROLL down | TYPE hello | DONE
 * 5. Execute the action via gesture dispatch
 * 6. Wait, screenshot again, repeat
 */
class VisionAgentLoop(
    private val apiKey: String,
    private val model: String = "claude-opus-4-6",
    private val maxSteps: Int = 15,
) {

    data class LoopResult(
        val success: Boolean,
        val stepsExecuted: Int,
        val summary: String,
    )

    suspend fun execute(task: String): LoopResult {
        val history = mutableListOf<String>()
        var consecutiveFailures = 0
        // Get actual screen size for coordinate scaling.
        // Screenshots are scaled to SCREENSHOT_WIDTH px wide (aspect preserved),
        // so both x and y use the same scale factor.
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
        val coordScale = screenWidth.toFloat() / SCREENSHOT_WIDTH
        Log.i(TAG, "Screen width: $screenWidth, coordScale: $coordScale")

        for (step in 0 until maxSteps) {
            // 1. Take screenshot (must be on Main thread for accessibility service)
            val screenshot = withContext(Dispatchers.Main) { takeScreenshot() }
            if (screenshot == null) {
                Log.w(TAG, "Step $step: screenshot failed")
                delay(1000)
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    return LoopResult(false, step, "Cannot take screenshots — accessibility service may not be connected")
                }
                continue
            }

            // 2. Encode as base64 JPEG
            val base64 = encodeScreenshot(screenshot)
            screenshot.recycle()
            Log.i(TAG, "Step $step: screenshot ${base64.length} chars base64")

            // 3. Ask Claude what to do (network call must be on IO thread)
            val response = try {
                withContext(Dispatchers.IO) {
                    askClaude(task, base64, history, step)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Step $step: Claude API failed: ${e.message}", e)
                return LoopResult(false, step, "Vision API error: ${e.message}")
            }

            Log.i(TAG, "Step $step: Claude says: ${response.take(80)}")

            // 4. Parse and execute
            val action = parseAction(response)
            history.add("Step $step: $action")

            when (action) {
                is Action.Tap -> {
                    // Scale from screenshot coords to actual screen coords
                    val realX = (action.x * coordScale).toInt()
                    val realY = (action.y * coordScale).toInt()
                    Log.i(TAG, "Step $step: TAP screenshot(${action.x},${action.y}) → screen($realX,$realY)")
                    UiInteractor.tapAtCoordinates(realX, realY)
                    delay(1500)
                    consecutiveFailures = 0
                }
                is Action.LongPress -> {
                    val realX = (action.x * coordScale).toInt()
                    val realY = (action.y * coordScale).toInt()
                    Log.i(TAG, "Step $step: LONG_PRESS screenshot(${action.x},${action.y}) → screen($realX,$realY)")
                    UiInteractor.longPressAtCoordinates(realX, realY)
                    delay(1500)
                    consecutiveFailures = 0
                }
                is Action.Scroll -> {
                    Log.i(TAG, "Step $step: SCROLL ${action.direction}")
                    UiInteractor.scroll(action.direction)
                    delay(1200)
                    consecutiveFailures = 0
                }
                is Action.Type -> {
                    Log.i(TAG, "Step $step: TYPE '${action.text.take(30)}'")
                    UiInteractor.typeText(action.text)
                    delay(800)
                    consecutiveFailures = 0
                }
                is Action.Wait -> {
                    Log.i(TAG, "Step $step: WAIT")
                    delay(2000)
                }
                is Action.Done -> {
                    Log.i(TAG, "Step $step: DONE — ${action.summary}")
                    return LoopResult(true, step + 1, action.summary)
                }
                is Action.Abort -> {
                    Log.i(TAG, "Step $step: ABORT — ${action.reason}")
                    return LoopResult(false, step + 1, action.reason)
                }
                is Action.Unknown -> {
                    Log.w(TAG, "Step $step: unparseable — ${action.raw.take(50)}")
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        return LoopResult(false, step + 1, "Too many unparseable responses")
                    }
                }
            }
        }

        return LoopResult(false, maxSteps, "Reached max steps ($maxSteps)")
    }

    private suspend fun takeScreenshot(): Bitmap? {
        val service = AccessibilityObservationService.getInstance() ?: return null
        return suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            // Convert to software bitmap for JPEG encoding
                            val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            result.hardwareBuffer.close()
                            cont.resume(swBitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "takeScreenshot failed: errorCode=$errorCode")
                            cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception: ${e.message}")
                cont.resume(null)
            }
        }
    }

    private fun encodeScreenshot(bitmap: Bitmap): String {
        // Scale down for faster upload — 720px wide is enough for Claude
        val scale = 720f / bitmap.width
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            720,
            (bitmap.height * scale).toInt(),
            true
        )
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        scaled.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun askClaude(
        task: String,
        screenshotBase64: String,
        history: List<String>,
        stepNum: Int,
    ): String {
        val historyText = if (history.isEmpty()) "No actions taken yet."
        else history.takeLast(5).joinToString("\n")

        val userText = buildString {
            appendLine("Task: $task")
            appendLine("Step: $stepNum / $maxSteps")
            appendLine()
            appendLine("Recent actions:")
            appendLine(historyText)
            appendLine()
            appendLine("Look at the screenshot and decide the next action.")
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", 60)
            put("system", SYSTEM_PROMPT)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", screenshotBase64)
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", userText)
                        }
                    }
                }
            }
        }.toString()

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
        }

        try {
            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "Vision API error $responseCode: ${error.take(300)}")
                throw RuntimeException("Vision API returned $responseCode")
            }

            val responseStr = connection.inputStream.bufferedReader().readText()
            val json = Json.parseToJsonElement(responseStr).jsonObject
            return json["content"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: throw RuntimeException("No content in vision response")
        } finally {
            connection.disconnect()
        }
    }

    sealed class Action {
        data class Tap(val x: Int, val y: Int) : Action()
        data class LongPress(val x: Int, val y: Int) : Action()
        data class Scroll(val direction: String) : Action()
        data class Type(val text: String) : Action()
        data object Wait : Action()
        data class Done(val summary: String) : Action()
        data class Abort(val reason: String) : Action()
        data class Unknown(val raw: String) : Action()
    }

    private fun parseAction(response: String): Action {
        // Scan all lines for a valid action — Claude sometimes prefixes with explanation
        for (line in response.trim().lines().map { it.trim() }) {
            val action = parseLine(line)
            if (action != null) return action
        }
        // Fallback: check if response contains DONE/ABORT keywords
        val lower = response.lowercase()
        if (lower.contains("task is complete") || lower.contains("task completed")) {
            return Action.Done(response.trim().take(100))
        }
        return Action.Unknown(response.trim().lines().first().take(60))
    }

    private fun parseLine(line: String): Action? {
        return when {
            line.startsWith("TAP ", ignoreCase = true) -> {
                val coords = Regex("(\\d+)[,\\s]+(\\d+)").find(line)
                if (coords != null) {
                    Action.Tap(coords.groupValues[1].toInt(), coords.groupValues[2].toInt())
                } else null
            }
            line.startsWith("LONG_PRESS ", ignoreCase = true) || line.startsWith("LONGPRESS ", ignoreCase = true) -> {
                val coords = Regex("(\\d+)[,\\s]+(\\d+)").find(line)
                if (coords != null) {
                    Action.LongPress(coords.groupValues[1].toInt(), coords.groupValues[2].toInt())
                } else null
            }
            line.startsWith("SCROLL ", ignoreCase = true) ->
                Action.Scroll(line.substringAfter(" ").trim().lowercase())
            line.startsWith("TYPE ", ignoreCase = true) ->
                Action.Type(line.substringAfter(" ").trim())
            line.equals("WAIT", ignoreCase = true) -> Action.Wait
            line.startsWith("DONE", ignoreCase = true) ->
                Action.Done(line.substringAfter(" ").trim().ifBlank { "Task completed" })
            line.startsWith("ABORT", ignoreCase = true) ->
                Action.Abort(line.substringAfter(" ").trim().ifBlank { "Cannot complete task" })
            else -> null
        }
    }

    companion object {
        private const val TAG = "VisionAgentLoop"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val SCREENSHOT_WIDTH = 720

        private val SYSTEM_PROMPT = """
You are Oak, an AI agent that controls a phone by looking at screenshots.
You see a screenshot of the phone screen and decide what action to take.

ACTIONS — respond with EXACTLY ONE line, nothing else:
TAP x y — tap at pixel coordinates (x, y)
LONG_PRESS x y — long-press at coordinates (for context menus, copy, select)
SCROLL down — swipe up to scroll content down (see more below)
SCROLL up — swipe down to scroll content up (see more above)
TYPE text — type text into the focused input field
WAIT — wait for the screen to update
DONE summary — task is complete (explain what you did)
ABORT reason — task cannot be completed

IMPORTANT RULES:
1. Coordinates are pixel positions on the 720px-wide screenshot image.
   Do NOT scale or multiply — use positions directly from the screenshot.
2. Look carefully at the screenshot. Identify buttons, icons, and text.
3. Be precise with coordinates — tap the CENTER of the target element.
4. One action per response. No explanations. Just the action line.

SOCIAL MEDIA TIPS:
- For "like" on Instagram: heart icon is below post image, left side.
- For scrolling feeds: use SCROLL down to see next post. Posts should
  be centered on screen — scroll until the target post is well-framed.
- For "scroll through feed": SCROLL down, observe each post, repeat.
  When user says "scroll through" they want to browse the feed.
- Use LONG_PRESS to copy text, select elements, or open context menus.

RESPOND WITH ONLY THE ACTION LINE. NO OTHER TEXT.
        """.trim()
    }
}
