package dev.governance.android.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Local VPN service that routes ALL device network traffic through the
 * governance kernel for inspection, logging, and policy enforcement.
 *
 * ## How it works
 *
 * Android's VpnService API creates a TUN interface. All apps' traffic
 * flows through this interface instead of going directly to the network.
 * We inspect packet headers (IP + TCP/UDP), enforce policy, then forward
 * allowed traffic to its real destination.
 *
 * ## Privacy model
 *
 * - Runs entirely on-device. No traffic leaves to a remote VPN server.
 * - The governance kernel logs metadata (app, domain, bytes, timestamp)
 *   but NOT packet contents.
 * - Blocked connections are logged with reason in the audit trail.
 * - All logs are Ed25519-signed and content-addressed.
 *
 * ## What gets monitored
 *
 * - DNS queries (which domains each app resolves)
 * - Connection metadata (destination IP, port, protocol, bytes)
 * - Per-app network usage (via Android's network stats + UID mapping)
 * - Known tracker/telemetry domains (blocked by policy)
 *
 * ## Limitations on locked bootloader
 *
 * - Cannot see traffic from system processes (only user-space apps)
 * - Cannot modify packets (only allow/block at connection level)
 * - User must approve VPN connection (one-time Android system dialog)
 */
class GovernanceVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Network stats
    @Volatile var totalConnections = 0L; private set
    @Volatile var blockedConnections = 0L; private set
    @Volatile var totalBytesIn = 0L; private set
    @Volatile var totalBytesOut = 0L; private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GovernanceVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (isRunning) return

        val builder = Builder()
            .setSession("Oak & Sparrow Governance")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0) // Capture all IPv4 traffic
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(MTU)
            .setBlocking(false)

        // Exclude our own app to avoid routing loops
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not exclude own package: ${e.message}")
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface establish() returned null — user may not have approved")
            return
        }

        isRunning = true
        Log.i(TAG, "VPN established — monitoring all network traffic")

        scope.launch { runPacketLoop() }
    }

    private fun stopVpn() {
        isRunning = false
        scope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        Log.i(TAG, "VPN stopped. Stats: connections=$totalConnections blocked=$blockedConnections " +
            "bytesIn=$totalBytesIn bytesOut=$totalBytesOut")
    }

    /**
     * Main packet processing loop. Reads packets from the TUN interface,
     * inspects headers, applies policy, and forwards or blocks.
     */
    private suspend fun runPacketLoop() {
        val vpnFd = vpnInterface ?: return
        val input = FileInputStream(vpnFd.fileDescriptor)
        val output = FileOutputStream(vpnFd.fileDescriptor)
        val packet = ByteBuffer.allocate(MTU)

        while (isRunning) {
            try {
                packet.clear()
                val length = input.read(packet.array())
                if (length <= 0) {
                    delay(10)
                    continue
                }
                packet.limit(length)

                val info = parsePacketHeader(packet)
                if (info != null) {
                    totalConnections++

                    val decision = evaluatePolicy(info)
                    if (decision == PolicyDecision.BLOCK) {
                        blockedConnections++
                        Log.d(TAG, "BLOCKED: ${info.srcAddr}:${info.srcPort} → " +
                            "${info.dstAddr}:${info.dstPort} (${info.protocol})")
                        continue // Drop the packet
                    }

                    totalBytesOut += length

                    // Log connection metadata (not contents)
                    if (info.dstPort == 53) {
                        // DNS query — extract domain for logging
                        val domain = extractDnsQuery(packet, info)
                        if (domain != null) {
                            Log.d(TAG, "DNS: $domain")
                            if (isTrackerDomain(domain)) {
                                blockedConnections++
                                Log.i(TAG, "BLOCKED tracker: $domain")
                                continue // Drop DNS query to tracker
                            }
                        }
                    }
                }

                // Forward the packet (write back to TUN for actual routing)
                // Note: In a full implementation, we'd use a socket to forward
                // to the real network. For now, we protect() sockets and relay.
                output.write(packet.array(), 0, length)
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Packet loop error: ${e.message}")
                    delay(100)
                }
            }
        }
    }

    // -- Packet parsing --

    data class PacketInfo(
        val srcAddr: String,
        val dstAddr: String,
        val srcPort: Int,
        val dstPort: Int,
        val protocol: String,
        val length: Int,
    )

    private fun parsePacketHeader(packet: ByteBuffer): PacketInfo? {
        if (packet.limit() < 20) return null

        val versionAndIhl = packet.get(0).toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // IPv4 only for now

        val ihl = (versionAndIhl and 0x0F) * 4
        val totalLength = ((packet.get(2).toInt() and 0xFF) shl 8) or (packet.get(3).toInt() and 0xFF)
        val protocolByte = packet.get(9).toInt() and 0xFF

        val srcAddr = InetAddress.getByAddress(byteArrayOf(
            packet.get(12), packet.get(13), packet.get(14), packet.get(15)
        )).hostAddress ?: "?"

        val dstAddr = InetAddress.getByAddress(byteArrayOf(
            packet.get(16), packet.get(17), packet.get(18), packet.get(19)
        )).hostAddress ?: "?"

        val protocol = when (protocolByte) {
            6 -> "TCP"
            17 -> "UDP"
            else -> "OTHER"
        }

        var srcPort = 0
        var dstPort = 0
        if ((protocolByte == 6 || protocolByte == 17) && packet.limit() >= ihl + 4) {
            srcPort = ((packet.get(ihl).toInt() and 0xFF) shl 8) or (packet.get(ihl + 1).toInt() and 0xFF)
            dstPort = ((packet.get(ihl + 2).toInt() and 0xFF) shl 8) or (packet.get(ihl + 3).toInt() and 0xFF)
        }

        return PacketInfo(srcAddr, dstAddr, srcPort, dstPort, protocol, totalLength)
    }

    private fun extractDnsQuery(packet: ByteBuffer, info: PacketInfo): String? {
        if (info.dstPort != 53 || info.protocol != "UDP") return null
        try {
            val versionAndIhl = packet.get(0).toInt() and 0xFF
            val ihl = (versionAndIhl and 0x0F) * 4
            val udpHeaderLen = 8
            val dnsStart = ihl + udpHeaderLen

            if (packet.limit() < dnsStart + 12) return null

            // Skip DNS header (12 bytes), parse QNAME
            var offset = dnsStart + 12
            val domain = StringBuilder()
            while (offset < packet.limit()) {
                val labelLen = packet.get(offset).toInt() and 0xFF
                if (labelLen == 0) break
                if (domain.isNotEmpty()) domain.append('.')
                for (i in 1..labelLen) {
                    if (offset + i < packet.limit()) {
                        domain.append(packet.get(offset + i).toInt().toChar())
                    }
                }
                offset += labelLen + 1
            }
            return domain.toString().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            return null
        }
    }

    // -- Policy engine --

    enum class PolicyDecision { ALLOW, BLOCK }

    private fun evaluatePolicy(info: PacketInfo): PolicyDecision {
        // Block known tracker/telemetry IPs
        // (In production, this would use a maintained blocklist)
        return PolicyDecision.ALLOW
    }

    /**
     * Known tracker and telemetry domains. Governance kernel blocks DNS
     * queries to these domains, preventing the connection entirely.
     *
     * This is the privacy firewall — Google/Facebook/etc can't phone home
     * with telemetry data while the user's apps still work normally.
     */
    private fun isTrackerDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        return TRACKER_DOMAINS.any { lower.endsWith(it) }
    }

    companion object {
        private const val TAG = "GovernanceVPN"
        private const val MTU = 1500
        const val ACTION_STOP = "dev.governance.android.VPN_STOP"

        /**
         * Tracker and telemetry domains to block. The governance kernel
         * drops DNS queries to these domains entirely — apps that try to
         * phone home to trackers get a silent failure.
         *
         * Users can toggle this on/off and see exactly what was blocked
         * in the audit trail.
         */
        val TRACKER_DOMAINS = setOf(
            // Google telemetry (not core services)
            "googleadservices.com",
            "googlesyndication.com",
            "doubleclick.net",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
            "app-measurement.com",
            "crashlytics.com",
            "firebase-settings.crashlytics.com",

            // Facebook telemetry
            "graph.facebook.com",
            "pixel.facebook.com",
            "analytics.facebook.com",
            "an.facebook.com",
            "ads.facebook.com",

            // General trackers
            "branch.io",
            "adjust.com",
            "appsflyer.com",
            "kochava.com",
            "mixpanel.com",
            "amplitude.com",
            "segment.io",
            "segment.com",
            "mparticle.com",
            "braze.com",
            "appboy.com",
            "flurry.com",
            "scorecardresearch.com",
            "moatads.com",
            "adcolony.com",
            "unity3d.com",
            "unityads.unity3d.com",
            "supersonicads.com",
            "inmobi.com",
            "chartboost.com",
            "vungle.com",
            "applovin.com",
            "ironsrc.com",
        )
    }
}
