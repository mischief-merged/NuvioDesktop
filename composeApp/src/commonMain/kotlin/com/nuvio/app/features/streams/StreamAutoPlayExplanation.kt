package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.debrid.DebridStreamMetadata
import com.nuvio.app.features.debrid.DebridStreamPreferences
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.downloads.SeasonDownloadDefaults

/**
 * What the best-match Play mode was asked to match, kept so the screens can say what happened. Only
 * the preferences that were really used are reported: a bitrate limit is checked against file sizes
 * only when the running time is known, so without it the limit counts as not applied.
 */
data class AutoPlayContext(
    val resolution: DebridStreamResolution? = null,
    val maxBitrateMbps: Int? = null,
    val audioLanguage: DebridStreamLanguage? = null,
    val runtimeKnown: Boolean = false,
) {
    /** The bitrate limit that was applied: one is set and the running time is known. */
    val appliedMaxBitrateMbps: Int?
        get() = maxBitrateMbps?.takeIf { runtimeKnown }

    /** A bitrate limit is set but could not be applied, because the running time is unknown. */
    val bitrateLimitSkipped: Boolean
        get() = maxBitrateMbps != null && !runtimeKnown

    /** True when at least one preference was applied. */
    val hasAppliedPreferences: Boolean
        get() = resolution != null || appliedMaxBitrateMbps != null || audioLanguage != null
}

internal fun autoPlayContextFor(preferences: SeasonDownloadDefaults, runtimeMinutes: Int?): AutoPlayContext =
    AutoPlayContext(
        resolution = preferences.quality.resolution,
        maxBitrateMbps = preferences.maxBitrateMbps,
        audioLanguage = preferences.audioLanguage,
        runtimeKnown = runtimeMinutes != null && runtimeMinutes > 0,
    )

/** What is known about the source that was picked automatically. A null field is not known. */
data class AutoPlayChoice(
    val sourceName: String?,
    val resolution: DebridStreamResolution?,
    val language: DebridStreamLanguage?,
    /** Set only when a bitrate limit was applied and the size of this source is known. */
    val withinBitrateMbps: Int?,
)

private val choiceParsingPreferences = DebridStreamPreferences()

/** Describes [stream] for the loading screen. [context] is null when best match was not the mode. */
internal fun describeAutoPlayChoice(stream: StreamItem, context: AutoPlayContext?): AutoPlayChoice {
    val facts = DebridStreamMetadata.facts(stream, choiceParsingPreferences)
    val language = context?.audioLanguage?.takeIf { it in facts.languages } ?: facts.languages.firstOrNull()
    return AutoPlayChoice(
        sourceName = stream.addonName.takeIf { it.isNotBlank() },
        resolution = facts.resolution.takeIf { it != DebridStreamResolution.UNKNOWN },
        language = language,
        withinBitrateMbps = context?.appliedMaxBitrateMbps?.takeIf { (facts.size ?: 0L) > 0L },
    )
}

/** The preference that decided which sources fit, in the order they are named. */
internal sealed interface AutoPlayPreferencePart {
    data class Quality(val resolution: DebridStreamResolution) : AutoPlayPreferencePart
    data class MaxBitrate(val megabitsPerSecond: Int) : AutoPlayPreferencePart
    data class Language(val language: DebridStreamLanguage) : AutoPlayPreferencePart
}

internal fun AutoPlayContext.preferenceParts(): List<AutoPlayPreferencePart> = buildList {
    resolution?.let { add(AutoPlayPreferencePart.Quality(it)) }
    appliedMaxBitrateMbps?.let { add(AutoPlayPreferencePart.MaxBitrate(it)) }
    audioLanguage?.let { add(AutoPlayPreferencePart.Language(it)) }
}

/** The banner above the source list is shown when best match found nothing, and sources exist. */
internal fun StreamsUiState.autoPlayNoMatchBanner(): AutoPlayContext? =
    autoPlayContext?.takeIf { autoPlayFoundNoMatch && autoPlayStream == null && groups.any { it.streams.isNotEmpty() } }

internal fun StreamsUiState.autoPlayChoiceOrNull(): AutoPlayChoice? =
    autoPlayStream?.let { describeAutoPlayChoice(it, autoPlayContext) }
