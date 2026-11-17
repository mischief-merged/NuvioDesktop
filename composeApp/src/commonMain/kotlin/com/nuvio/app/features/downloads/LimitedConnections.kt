package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why the connection counts as limited, so downloads wait. Each reason is one rule; later rules (a
 * measured speed below what the user set, a Wi-Fi network the user named) are added as further cases.
 */
internal sealed interface LimitedConnectionReason {
    /** The system says traffic is expensive: a phone's hotspot, tethering or a cellular link. */
    data object Hotspot : LimitedConnectionReason

    /** The user turned on Low Data Mode for this network. */
    data object LowDataMode : LimitedConnectionReason
}

/** What the system says about the path traffic takes now. */
internal sealed interface NetworkPathReport {
    /** Nothing is known: no detector on this platform, or it could not start. Never holds anything. */
    data object Unknown : NetworkPathReport

    data class Known(
        val expensive: Boolean,
        val constrained: Boolean,
        /**
         * Names the network the path goes over: "interfaces|routers", each list sorted, a router as
         * "address@hardware address" or "address" when its hardware address is not known. Compared
         * with [sameNetwork]; another network ends a "Download anyway" given for the one before.
         */
        val networkKey: String,
    ) : NetworkPathReport
}

/** Tells what path traffic takes, now and whenever that changes. */
internal interface NetworkPathDetector {
    /**
     * Starts reporting to [onReport]: once with the path as it is now, as soon as that is known, and
     * again on every change. False when it cannot report on this machine; nothing is reported then.
     */
    fun start(onReport: (NetworkPathReport) -> Unit): Boolean

    fun stop()
}

/**
 * Whether two network keys name the same network: the same interfaces and the same router addresses,
 * and no router whose hardware address is known in both differs. A hardware address that is known in
 * one only (the router was not in the address table yet) is no difference.
 */
internal fun sameNetwork(first: String, second: String): Boolean {
    if (first == second) return true
    fun parse(key: String): Pair<String, Map<String, String?>> {
        val interfaces = key.substringBefore('|')
        val routers = key.substringAfter('|', "").split(',').filter(String::isNotEmpty).associate { router ->
            router.substringBefore('@') to router.substringAfter('@', "").ifEmpty { null }
        }
        return interfaces to routers
    }
    val (firstInterfaces, firstRouters) = parse(first)
    val (secondInterfaces, secondRouters) = parse(second)
    if (firstInterfaces != secondInterfaces || firstRouters.keys != secondRouters.keys) return false
    return firstRouters.all { (address, hardware) ->
        val other = secondRouters[address]
        hardware == null || other == null || hardware == other
    }
}

/** The limited reason of [report], whatever the setting. Hotspot first: it is the one people recognise. */
internal fun limitedReasonOf(report: NetworkPathReport): LimitedConnectionReason? = when (report) {
    NetworkPathReport.Unknown -> null
    is NetworkPathReport.Known -> when {
        report.expensive -> LimitedConnectionReason.Hotspot
        report.constrained -> LimitedConnectionReason.LowDataMode
        else -> null
    }
}

internal data class ConnectionHoldState(
    /** Why the connection is limited now, or null when it is not or nothing is known. Whatever the setting. */
    val reason: LimitedConnectionReason? = null,
    /** Whether the setting to pause downloads on limited connections is on. */
    val enabled: Boolean = true,
    /** The user chose "Download anyway" on this network, in this run of the app. */
    val overridden: Boolean = false,
) {
    /** Downloads wait now: the connection is limited, the setting is on and the user did not override it. */
    val holding: Boolean get() = reason != null && enabled && !overridden

    /** Why downloads wait now, or null when they do not. */
    val holdReason: LimitedConnectionReason? get() = reason.takeIf { holding }
}

/**
 * Decides whether downloads wait because of the connection. It never holds on an unknown path. A
 * "Download anyway" lasts until the network changes or the app quits: it is not saved.
 *
 * Whoever applies the hold listens with [listen]; it is told after every change of [state], outside
 * any lock of this class, and reads [state] itself (a later change may have come in between).
 */
internal class ConnectionHold(
    private val loadEnabled: () -> Boolean? = { null },
    private val saveEnabled: (Boolean) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private var report: NetworkPathReport = NetworkPathReport.Unknown
    private var enabled: Boolean? = null

    // The network the user said "Download anyway" for, null when there is no such choice.
    private var overrideKey: String? = null
    private var listener: (() -> Unit)? = null

    private val _state = MutableStateFlow(ConnectionHoldState())
    val state: StateFlow<ConnectionHoldState> = _state.asStateFlow()

    init {
        synchronized(lock) { _state.value = computeLocked() }
    }

    /** Why downloads wait now, or null. Reads the published state only, so it never waits for a lock. */
    val holdReason: LimitedConnectionReason? get() = _state.value.holdReason

    fun listen(listener: () -> Unit) {
        synchronized(lock) { this.listener = listener }
    }

    fun report(report: NetworkPathReport) = change {
        this.report = report
        val key = (report as? NetworkPathReport.Known)?.networkKey
        // Another network, or one that is not limited, ends a "Download anyway".
        val chosen = overrideKey
        overrideKey = when {
            chosen == null -> null
            key == null || !sameNetwork(key, chosen) || limitedReasonOf(report) == null -> null
            // The same network, maybe now with a router's hardware address that was not known before: kept, so a
            // network with the same addresses but another router is told apart from then on.
            else -> key
        }
    }

    fun setEnabled(value: Boolean) = change {
        enabled = value
        runCatching { saveEnabled(value) }
    }

    /** Lets downloads run on this network until it changes or the app quits. Nothing when nothing is held. */
    fun downloadAnyway() = change {
        val known = report as? NetworkPathReport.Known
        if (known != null && limitedReasonOf(known) != null) overrideKey = known.networkKey
    }

    private fun enabledLocked(): Boolean =
        enabled ?: (runCatching { loadEnabled() }.getOrNull() ?: true).also { enabled = it }

    private fun computeLocked(): ConnectionHoldState {
        val known = report as? NetworkPathReport.Known
        return ConnectionHoldState(
            reason = limitedReasonOf(report),
            enabled = enabledLocked(),
            overridden = known != null && overrideKey?.let { sameNetwork(it, known.networkKey) } == true,
        )
    }

    private inline fun change(block: () -> Unit) {
        val notify = synchronized(lock) {
            val before = _state.value
            block()
            _state.value = computeLocked()
            listener.takeIf { _state.value != before }
        }
        notify?.invoke()
    }
}

/** The app's hold, with the setting saved where the platform keeps settings. */
internal object LimitedConnections {
    val hold: ConnectionHold = ConnectionHold(
        loadEnabled = LimitedConnectionStorage::loadPauseOnLimited,
        saveEnabled = LimitedConnectionStorage::savePauseOnLimited,
    )
}

/** Where the setting "Pause downloads on hotspots and Low Data Mode networks" is kept. Null: never set (on). */
internal expect object LimitedConnectionStorage {
    fun loadPauseOnLimited(): Boolean?
    fun savePauseOnLimited(value: Boolean)
}
