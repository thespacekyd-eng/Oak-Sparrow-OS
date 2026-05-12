package dev.governance.android.app.util

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Small helpers to reduce UI Automator boilerplate in
 * instrumented tests.
 */
object UiAutomatorExt {

    /** Returns a [UiDevice] for the current instrumentation. */
    fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Waits up to [timeoutMs] for [text] to appear on screen. */
    fun UiDevice.waitForText(text: String, timeoutMs: Long = 5000): Boolean =
        wait(Until.hasObject(By.text(text)), timeoutMs)

    /** Waits up to [timeoutMs] for text containing [substring] to appear. */
    fun UiDevice.waitForTextContaining(substring: String, timeoutMs: Long = 5000): Boolean =
        wait(Until.hasObject(By.textContains(substring)), timeoutMs)

    /** Finds an element by visible text and clicks it. */
    fun UiDevice.tapByText(text: String) {
        findObject(By.text(text)).click()
    }

    /**
     * Dismisses the auth dialog if it's currently visible by
     * tapping Skip. No-op if the dialog isn't on screen.
     */
    fun UiDevice.dismissActiveAuthDialog(timeoutMs: Long = 1000) {
        val skipButton = wait(Until.findObject(By.text("Skip")), timeoutMs)
        skipButton?.click()
        // Wait for dialog to disappear
        wait(Until.gone(By.textContains("CONFIRM")), 2000)
    }

    /**
     * Types text into the currently focused field via shell input.
     * More reliable than UiObject2.setText for Compose TextFields.
     * Spaces are encoded as %s for the `input text` command.
     */
    fun UiDevice.shellType(text: String) {
        val escaped = text.replace(" ", "%s")
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input text '$escaped'")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        Thread.sleep(300)
    }

    /**
     * Clears onboarding and state files without killing the app
     * process. Uses `run-as` to delete DataStore and state dirs.
     *
     * Note: does NOT use `pm clear` which would kill the test
     * process (instrumented tests share the app's process).
     */
    fun clearAppState(packageName: String = "dev.governance.android") {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(
            "run-as $packageName sh -c 'rm -rf files/datastore files/state'"
        ).close()
        Thread.sleep(500)
    }
}
