package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Called by the native monitor, on a queue of its own, with the path traffic takes now. */
internal fun interface NetworkPathListener {
    fun onPath(satisfied: Boolean, expensive: Boolean, constrained: Boolean, interfaces: String)
}

/** The network path monitor of the macOS native bridge (Network.framework). Lives in the player bridge library. */
internal object MacNetworkPathNative {
    external fun startMonitor(listener: NetworkPathListener): Boolean
    external fun stopMonitor()
}

/**
 * Reports the macOS network path: expensive (a phone's hotspot, tethering, cellular) and constrained
 * (Low Data Mode). Anything that goes wrong (another system, a native library that did not load or
 * is too old to have the monitor) makes [start] return false, and nothing is reported: the hold then
 * stays on an unknown path, which never holds a download.
 *
 * Reports are handed to [onReport] on a thread of their own, never on the native queue.
 */
internal class MacNetworkPathDetector(
    private val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac"),
    private val loadLibrary: () -> Unit = NativePlayerBridge::ensureLoaded,
    private val native: NativePathMonitor = NativePathMonitor.Real,
) : NetworkPathDetector {
    /** The two native calls, replaceable in tests. */
    internal interface NativePathMonitor {
        fun start(listener: NetworkPathListener): Boolean
        fun stop()

        object Real : NativePathMonitor {
            override fun start(listener: NetworkPathListener): Boolean = MacNetworkPathNative.startMonitor(listener)
            override fun stop() = MacNetworkPathNative.stopMonitor()
        }
    }

    private val log = Logger.withTag("NetworkPath")
    private var executor: ExecutorService? = null

    @Synchronized
    override fun start(onReport: (NetworkPathReport) -> Unit): Boolean {
        if (!isMac) return false
        stop()
        val reports = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nuvio-network-path").apply { isDaemon = true }
        }
        val started = try {
            loadLibrary()
            native.start { satisfied, expensive, constrained, interfaces ->
                val report = pathReport(satisfied, expensive, constrained, interfaces)
                runCatching { reports.execute { runCatching { onReport(report) }.onFailure { log.w(it) { "a path report failed" } } } }
            }
        } catch (error: Throwable) {
            // UnsatisfiedLinkError from a bridge without the monitor, or the library not loading at all.
            log.w(error) { "network path monitor unavailable" }
            false
        }
        if (!started) {
            reports.shutdown()
            return false
        }
        executor = reports
        return true
    }

    @Synchronized
    override fun stop() {
        val running = executor ?: return
        executor = null
        runCatching { native.stop() }
        running.shutdown()
    }
}

/** The report for what the native monitor said. The network key is the interfaces in a stable order. */
internal fun pathReport(satisfied: Boolean, expensive: Boolean, constrained: Boolean, interfaces: String): NetworkPathReport.Known {
    val key = interfaces.split(',').map(String::trim).filter(String::isNotEmpty).sorted().joinToString(",")
    return NetworkPathReport.Known(
        // A path that cannot carry traffic is not limited: downloads wait for the internet in their own way then.
        expensive = satisfied && expensive,
        constrained = satisfied && constrained,
        networkKey = if (satisfied) key else "",
    )
}

/** Watches the network path for the app's connection hold. Started once when the app starts. */
internal object DesktopLimitedConnections {
    @Volatile
    private var detector: NetworkPathDetector? = null

    /** Starts watching on a thread of its own (loading the native library takes a moment). Never throws. */
    fun install(
        hold: ConnectionHold = LimitedConnections.hold,
        newDetector: () -> NetworkPathDetector = { MacNetworkPathDetector() },
    ) {
        Thread {
            runCatching {
                val started = newDetector()
                if (started.start(hold::report)) detector = started else hold.report(NetworkPathReport.Unknown)
            }
        }.apply {
            name = "nuvio-network-path-start"
            isDaemon = true
            start()
        }
    }
}
