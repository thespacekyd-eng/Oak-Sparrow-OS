package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import dev.governance.android.app.util.UiAutomatorExt
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test

/**
 * Tests foreground service lifecycle: notification presence
 * and persistence across activity destruction.
 *
 * Note: process-death recovery cannot be tested in-process
 * because `am force-stop` kills the test runner. That check
 * remains manual in the runbook.
 *
 * Requires a connected emulator:
 * ```
 * ./gradlew :android-app:connectedDebugAndroidTest
 * ```
 */
class ForegroundServiceLifecycleTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val device by lazy { UiAutomatorExt.device() }

    @Test
    fun notificationAppearsAfterServiceStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        serviceRule.bindService(intent)
        Thread.sleep(3000) // allow notification to post

        device.openNotification()
        Thread.sleep(3000)

        // Search broadly for our notification
        val found = device.findObject(By.textContains("governance")) != null
            || device.findObject(By.textContains("Sparrow")) != null
            || device.findObject(By.textContains("Oak")) != null
        found shouldBe true

        device.pressBack()
        Thread.sleep(500)
    }

    @Test
    fun notificationPersistsAcrossActivityDestruction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        serviceRule.bindService(intent)
        Thread.sleep(2000)

        device.pressHome()
        Thread.sleep(1500)

        device.openNotification()
        Thread.sleep(3000)

        val found = device.findObject(By.textContains("governance")) != null
            || device.findObject(By.textContains("Sparrow")) != null
            || device.findObject(By.textContains("Oak")) != null
        found shouldBe true

        device.pressBack()
        Thread.sleep(500)
    }

    @Test
    fun serviceBinderRemainsValidAcrossRebind() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)

        val binder1 = serviceRule.bindService(intent)
        val kernel1 = AgentKernelInterface.Stub.asInterface(binder1)
        kernel1.snapshot() shouldNotBe null
        serviceRule.unbindService()

        val binder2 = serviceRule.bindService(intent)
        val kernel2 = AgentKernelInterface.Stub.asInterface(binder2)
        kernel2.snapshot() shouldNotBe null
    }
}
