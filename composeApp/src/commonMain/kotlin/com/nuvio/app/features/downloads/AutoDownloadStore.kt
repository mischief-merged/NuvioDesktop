package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where the automatic-download state of a profile is kept. Always [AutoDownloadStorage] in the app. */
internal interface AutoDownloadPayloads {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
    fun saveUnreadable(profileId: Int, payload: String)
}

internal object PlatformAutoDownloadPayloads : AutoDownloadPayloads {
    override fun load(profileId: Int): String? = AutoDownloadStorage.loadPayload(profileId)

    override fun save(profileId: Int, payload: String) = AutoDownloadStorage.savePayload(profileId, payload)

    override fun saveUnreadable(profileId: Int, payload: String) =
        AutoDownloadStorage.saveUnreadablePayload(profileId, payload)
}

@Serializable
private data class StoredAutoDownloads(
    val rules: List<AutoDownloadRule> = emptyList(),
    /** Null means no limit. */
    val storageLimitGb: Int? = AutoDownloadStore.DefaultStorageLimitGb,
)

/**
 * The automatic-download rules of the active profile.
 *
 * Every read first makes sure the rules in memory are those of the profile that is active now, so
 * one profile's rules are never shown or saved for another. Every change names the profile it was
 * made for and is dropped when that profile is no longer the active one.
 *
 * A stored payload that cannot be read is copied aside before anything is saved over it.
 */
internal class AutoDownloadStore(
    private val payloads: AutoDownloadPayloads,
    private val activeProfileId: () -> Int,
) {
    private val lock = SynchronizedObject()
    // Every field is written, also one that has its default value, so that a rule keeps what it
    // was saved with when a default changes later.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var loadedProfileId: Int? = null
    private var state = StoredAutoDownloads()
    private val _rules = MutableStateFlow<List<AutoDownloadRule>>(emptyList())

    private val _storageLimitGb = MutableStateFlow<Int?>(DefaultStorageLimitGb)

    /** The rules of the profile that was active at the last read or change. */
    val rules: StateFlow<List<AutoDownloadRule>> = _rules.asStateFlow()

    /** The storage limit for automatic downloads of that profile, in gigabytes. Null means no limit. */
    val storageLimitGb: StateFlow<Int?> = _storageLimitGb.asStateFlow()

    fun storageLimitBytes(): Long? = synchronized(lock) {
        ensureLoaded()
        state.storageLimitGb?.let { it * BytesPerGigabyte }
    }

    /** False when [profileId] is not the active profile, in which case nothing is changed. */
    fun setStorageLimit(profileId: Int, limitGb: Int?): Boolean = synchronized(lock) {
        if (ensureLoaded() != profileId) return false
        save(profileId, state.copy(storageLimitGb = limitGb).normalized())
        true
    }

    /** The active profile, with its rules loaded. */
    fun profileId(): Int = synchronized(lock) { ensureLoaded() }

    fun currentRules(): List<AutoDownloadRule> = synchronized(lock) {
        ensureLoaded()
        state.rules
    }

    fun rule(profileId: Int, ruleKey: String): AutoDownloadRule? = synchronized(lock) {
        if (ensureLoaded() != profileId) return null
        state.rules.firstOrNull { it.key == ruleKey }
    }

    /** Adds [rule] or replaces the rule of the same show. What the earlier rule downloaded is kept. */
    fun upsert(profileId: Int, rule: AutoDownloadRule): Boolean = change(profileId) { rules ->
        val existing = rules.firstOrNull { it.key == rule.key }
        val saved = rule.normalized().copy(ownedEpisodeKeys = rule.ownedEpisodeKeys + existing?.ownedEpisodeKeys.orEmpty())
        if (existing == null) rules + saved else rules.map { if (it.key == saved.key) saved else it }
    }

    fun remove(profileId: Int, ruleKey: String): Boolean = change(profileId) { rules ->
        rules.filterNot { it.key == ruleKey }
    }

    fun update(profileId: Int, ruleKey: String, transform: (AutoDownloadRule) -> AutoDownloadRule): Boolean =
        change(profileId) { rules -> rules.map { if (it.key == ruleKey) transform(it) else it } }

    /** False when [profileId] is not the active profile, in which case nothing is changed. */
    private fun change(profileId: Int, transform: (List<AutoDownloadRule>) -> List<AutoDownloadRule>): Boolean =
        synchronized(lock) {
            if (ensureLoaded() != profileId) return false
            save(profileId, state.copy(rules = transform(state.rules)))
            true
        }

    private fun save(profileId: Int, updated: StoredAutoDownloads) {
        if (updated == state) return
        state = updated
        _rules.value = updated.rules
        _storageLimitGb.value = updated.storageLimitGb
        payloads.save(profileId, json.encodeToString(updated))
    }

    private fun ensureLoaded(): Int {
        val active = activeProfileId()
        if (loadedProfileId != active) {
            state = read(active)
            loadedProfileId = active
            _rules.value = state.rules
            _storageLimitGb.value = state.storageLimitGb
        }
        return active
    }

    private fun read(profileId: Int): StoredAutoDownloads {
        val payload = payloads.load(profileId)?.takeIf { it.isNotBlank() } ?: return StoredAutoDownloads()
        return runCatching {
            json.decodeFromString<StoredAutoDownloads>(payload).normalized()
        }.getOrElse {
            payloads.saveUnreadable(profileId, payload)
            StoredAutoDownloads()
        }
    }

    private fun StoredAutoDownloads.normalized(): StoredAutoDownloads = copy(
        rules = rules.map(AutoDownloadRule::normalized).distinctBy { it.key },
        storageLimitGb = if (storageLimitGb in storageLimitOptionsGb) storageLimitGb else DefaultStorageLimitGb,
    )

    companion object {
        const val DefaultStorageLimitGb = 50
        val storageLimitOptionsGb: List<Int?> = listOf(null, 10, 25, 50, 100, 250)
    }
}
