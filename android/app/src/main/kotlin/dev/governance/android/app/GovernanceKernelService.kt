package dev.governance.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.governance.android.platform.AccessibilityObservationService
import dev.governance.android.platform.AndroidJsonlAuditWriter
import dev.governance.android.platform.AndroidKeystoreKeyProvider
import dev.governance.android.platform.StatePersistence
import dev.governance.android.platform.SystemClockAdapter
import dev.governance.android.platform.parcel.GateDecisionParcel
import dev.governance.android.platform.parcel.GovernanceSnapshotParcel
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.calibration.DefensivePriorCalibrator
import dev.governance.core.*
import dev.governance.gate.DefaultGovernanceKernel
import dev.governance.metrics.DefaultDilationFactor
import dev.governance.metrics.DefaultPredictiveEntropy
import dev.governance.metrics.DefaultTrajectoryDivergence
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service hosting a single [DefaultGovernanceKernel] instance.
 *
 * The service holds the current [GovernanceState] in memory and mediates
 * concurrent access via a single-threaded synchronized wrapper. State is
 * persisted to disk on every update and re-loaded on start.
 *
 * ## Concurrency
 *
 * All kernel operations ([decide], [resolve], [snapshot]) are synchronized
 * on [stateLock]. The kernel itself is stateless (pure functions of
 * [GovernanceState]), but the mutable state holder requires serialized access.
 *
 * ## Notification
 *
 * Runs as a foreground service with type FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
 * subtype "agent_governance". The notification shows current gamma.
 */
class GovernanceKernelService : Service() {

    private lateinit var kernel: DefaultGovernanceKernel
    private lateinit var persistence: StatePersistence
    private lateinit var auditWriter: AndroidJsonlAuditWriter

    private var currentState: GovernanceState = StatePersistence.freshDefensiveState()
    private val stateLock = Any()

    // Track recent decisions by auditId for resolve() lookups
    private val pendingDecisions = ConcurrentHashMap<String, GateDecision>()

    private val binder = object : AgentKernelInterface.Stub() {

        override fun decide(action: ProposedActionParcel): GateDecisionParcel {
            val proposed = action.toKernel()
            val (decision, _) = synchronizedDecide(proposed)
            return GateDecisionParcel.from(decision)
        }

        override fun resolve(decisionAuditId: String, outcome: ResolvedOutcomeParcel) {
            val resolvedOutcome = outcome.toKernel()
            val decision = pendingDecisions.remove(decisionAuditId)
                ?: throw IllegalArgumentException("Unknown decision auditId: $decisionAuditId")
            synchronizedResolve(decision, resolvedOutcome)
        }

        override fun snapshot(): GovernanceSnapshotParcel {
            val snap = synchronized(stateLock) { kernel.snapshot(currentState) }
            return GovernanceSnapshotParcel.from(snap)
        }
    }

    private val rateLimitedBinder by lazy {
        AgentBinderRateLimiter(binder)
    }

    override fun onCreate() {
        super.onCreate()
        persistence = StatePersistence(this)
        auditWriter = AndroidJsonlAuditWriter(this)

        val calibrator = DefensivePriorCalibrator(warmupThreshold = 100)
        kernel = DefaultGovernanceKernel(
            metrics = GateMetrics(
                dilationFactor = DefaultDilationFactor(),
                predictiveEntropy = DefaultPredictiveEntropy(),
                trajectoryDivergence = DefaultTrajectoryDivergence(),
            ),
            barriers = emptyList(),
            calibrator = calibrator,
            keyProvider = AndroidKeystoreKeyProvider(),
            auditWriter = auditWriter,
            clock = SystemClockAdapter(),
        )

        // Restore persisted state or start fresh
        val restored = persistence.load()
        if (restored != null) {
            currentState = restored
        } else {
            currentState = StatePersistence.freshDefensiveState()
            logSystemEvent("boot", "fresh defensive-prior state initialized")
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // PHASE2B-FOLLOWUP: instrumentation test on API 34+ emulator verifying
        // service starts without MissingForegroundServiceTypeException

        // Wire accessibility observation callback via static locator
        AccessibilityObservationService.callbackLocator = { createObservationCallback() }
    }

    override fun onBind(intent: Intent?): IBinder = rateLimitedBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityObservationService.callbackLocator = null
        synchronized(stateLock) { persistence.save(currentState) }
        logSystemEvent("shutdown", "governance service destroyed")
    }

    private fun synchronizedDecide(proposed: ProposedAction): Pair<GateDecision, GovernanceState> {
        synchronized(stateLock) {
            val decision = kernel.decide(currentState, proposed)
            pendingDecisions[decision.auditId.value] = decision
            updateNotification()
            return decision to currentState
        }
    }

    private fun synchronizedResolve(decision: GateDecision, outcome: ResolvedOutcome) {
        synchronized(stateLock) {
            currentState = kernel.resolve(currentState, decision, outcome)
            persistence.save(currentState)
            updateNotification()
        }
    }

    /**
     * Stub callback that logs accessibility observations to the system event
     * audit channel. Does NOT yet override the agent's self-reported outcome.
     *
     * // PHASE2B-FOLLOWUP: Add override-on-mismatch logic here. When the
     * // accessibility observation contradicts the agent's self-report (e.g.,
     * // agent says "email sent" but an error toast appeared), the callback
     * // should re-resolve the pending decision as Flagged instead of
     * // BenignSuccess. This requires correlating observations with pending
     * // decisions by timestamp or action context.
     */
    private fun createObservationCallback(): AccessibilityObservationService.OutcomeCallback {
        return object : AccessibilityObservationService.OutcomeCallback {
            override fun onErrorDetected(text: String) {
                logSystemEvent(
                    "accessibility_error",
                    "Error detected: $text",
                    SystemEventRecord.Severity.WARN,
                )
            }

            override fun onWindowChanged(className: String) {
                logSystemEvent(
                    "accessibility_window",
                    "Window changed: $className",
                    SystemEventRecord.Severity.INFO,
                )
            }
        }
    }

    private fun logSystemEvent(
        kind: String,
        message: String,
        severity: SystemEventRecord.Severity = SystemEventRecord.Severity.INFO,
    ) {
        try {
            auditWriter.writeSystemEvent(SystemEventRecord(
                timestamp = kotlinx.datetime.Clock.System.now(),
                kind = kind,
                message = message,
                severity = severity,
            ))
        } catch (_: Exception) {
            // Best-effort logging; don't crash the service
        }
    }

    // -- Notification --

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_ambient))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val CHANNEL_ID = "governance_service"
        const val NOTIFICATION_ID = 1
    }
}
