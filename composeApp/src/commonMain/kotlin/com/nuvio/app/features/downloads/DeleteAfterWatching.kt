package com.nuvio.app.features.downloads

/** What happened to a removal that was asked for or was due. */
internal enum class AutoDownloadRemovalOutcome {
    Removed,

    /** The episode is open in the player. The removal stays scheduled. */
    Playing,

    /** No later episode of the show is known to be released. The removal stays scheduled. */
    LastEpisode,

    /** The file could not be moved out of the downloads folder. The removal stays scheduled. */
    NotMoved,

    /** The removal no longer applies and was dropped. */
    Cancelled,
}

/**
 * Removes an automatically downloaded episode some time after it was watched to the end on this
 * device. Off for every show until its rule says otherwise.
 *
 * - Only [onLocalPlaybackCompleted] schedules a removal. Marking an episode watched, or watching it
 *   elsewhere, schedules nothing.
 * - Only a finished download that carries the key of a rule with
 *   [AutoDownloadRule.deleteAfterWatching] is scheduled or removed. A download the user started
 *   carries no rule key and is never touched.
 * - The time a removal is due is stored, so it is the same after the computer slept or the app was
 *   restarted. Watching the episode to the end again sets a new time.
 * - When it is due the download is looked at again. It is left alone while it is open in the
 *   player, and until a later episode of the show is known to be released, so the last episode is
 *   never removed by itself. It is dropped from the schedule when the user chose Keep, when it is
 *   no longer watched to the end, or when its rule no longer asks for removal.
 * - Turning the option or the rule off drops what was scheduled for it at once.
 * - [deleteNow] is the user's own request: it skips the wait and the last two checks.
 *
 * The schedule is kept in [store] with the rules of the profile, and every call names the profile
 * it is for.
 */
internal class DeleteAfterWatching(
    private val store: AutoDownloadStore,
    private val downloads: () -> List<DownloadItem>,
    /** The playback progress of the episode is still at its end. */
    private val isWatchedToTheEnd: (DownloadItem) -> Boolean,
    private val isInPlayer: (DownloadItem) -> Boolean,
    /** Whether an episode after this one is released, or null when the episodes are not known. */
    private val hasLaterEpisode: (AutoDownloadRule, DownloadItem) -> Boolean?,
    /**
     * The step that moves the file away and drops the download. It checks the download once more
     * and asks stillWanted at the last moment before it touches the file.
     */
    private val removeDownload: (downloadId: String, ruleKey: String, stillWanted: () -> Boolean) -> AutoDownloadRemovalResult,
    private val nowEpochMs: () -> Long,
    private val holdMs: Long = DefaultHoldMs,
) {
    /** True when a removal was scheduled for [item]. */
    fun onLocalPlaybackCompleted(profileId: Int, item: DownloadItem): Boolean {
        val ruleKey = item.autoDownloadRuleKey ?: return false
        if (item.status != DownloadStatus.Completed) return false
        if (store.rule(profileId, ruleKey)?.deleteAfterWatching != true) return false
        return store.scheduleRemoval(profileId, AutoDownloadRemoval(item.id, ruleKey, nowEpochMs() + holdMs))
    }

    fun keep(profileId: Int, downloadId: String) {
        store.keep(profileId, downloadId)
    }

    /** Removes the download now, at the user's request. Null when no removal is scheduled for it. */
    fun deleteNow(profileId: Int, downloadId: String): AutoDownloadRemovalOutcome? {
        val removal = store.removals(profileId).firstOrNull { it.downloadId == downloadId } ?: return null
        return process(profileId, removal, askedByUser = true)
    }

    /** Handles every removal whose time has come. */
    fun runDue(profileId: Int): Map<String, AutoDownloadRemovalOutcome> {
        val now = nowEpochMs()
        return store.removals(profileId)
            .filter { it.dueAtEpochMs <= now }
            .associate { it.downloadId to process(profileId, it, askedByUser = false) }
    }

    private fun process(profileId: Int, removal: AutoDownloadRemoval, askedByUser: Boolean): AutoDownloadRemovalOutcome {
        val item = downloads().firstOrNull { it.id == removal.downloadId }
        val rule = store.rule(profileId, removal.ruleKey)
        if (
            item == null ||
            rule?.deleteAfterWatching != true ||
            item.autoDownloadRuleKey != removal.ruleKey ||
            item.status != DownloadStatus.Completed ||
            store.isKept(profileId, item.id)
        ) {
            return cancel(profileId, removal)
        }
        if (isInPlayer(item)) return AutoDownloadRemovalOutcome.Playing
        if (!askedByUser) {
            if (!isWatchedToTheEnd(item)) return cancel(profileId, removal)
            if (hasLaterEpisode(rule, item) != true) return AutoDownloadRemovalOutcome.LastEpisode
        }
        // Things may change while this runs: the episode is opened, Keep, a new time after a rewatch,
        // the option turned off, another profile. All of that is asked again right before the file
        // is touched.
        val stillWanted = {
            !isInPlayer(item) &&
                removal in store.removals(profileId) &&
                !store.isKept(profileId, item.id) &&
                store.rule(profileId, removal.ruleKey)?.deleteAfterWatching == true
        }
        return when (removeDownload(item.id, removal.ruleKey, stillWanted)) {
            AutoDownloadRemovalResult.Removed -> {
                // The rule must know it had this episode, so that it does not fetch it again.
                val season = item.seasonNumber
                val episode = item.episodeNumber
                if (season != null && episode != null) {
                    store.update(profileId, removal.ruleKey) {
                        it.copy(ownedEpisodeKeys = it.ownedEpisodeKeys + autoDownloadEpisodeKey(season, episode))
                    }
                }
                store.dropRemoval(profileId, removal)
                AutoDownloadRemovalOutcome.Removed
            }
            AutoDownloadRemovalResult.Changed -> cancel(profileId, removal)
            AutoDownloadRemovalResult.NotMoved -> AutoDownloadRemovalOutcome.NotMoved
        }
    }

    private fun cancel(profileId: Int, removal: AutoDownloadRemoval): AutoDownloadRemovalOutcome {
        store.dropRemoval(profileId, removal)
        return AutoDownloadRemovalOutcome.Cancelled
    }

    companion object {
        const val DefaultHoldMs = 24L * 60 * 60 * 1000
    }
}

/** Whether [episodes] has a regular episode after the one [item] is. Null when it is not an episode. */
internal fun hasLaterReleasedEpisode(item: DownloadItem, episodes: List<SeasonBatchEpisode>): Boolean? {
    val season = item.seasonNumber ?: return null
    val episode = item.episodeNumber ?: return null
    return episodes.any {
        it.seasonNumber > 0 && (it.seasonNumber > season || (it.seasonNumber == season && it.episodeNumber > episode))
    }
}
