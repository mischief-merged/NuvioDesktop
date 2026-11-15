package com.nuvio.app.features.streams

enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH,

    /**
     * Plays the source that best fits the quality, maximum bitrate and audio language saved for
     * downloads. Shows the source list when no source fits them.
     */
    PREFERRED;

    companion object {
        /** Reads a stored value. A missing or unknown value (written by another version) is [MANUAL]. */
        fun fromStored(value: String?): StreamAutoPlayMode =
            entries.firstOrNull { it.name == value } ?: MANUAL
    }
}

enum class StreamAutoPlaySource {
    ALL_SOURCES,
    INSTALLED_ADDONS_ONLY,
    ENABLED_PLUGINS_ONLY,
}
