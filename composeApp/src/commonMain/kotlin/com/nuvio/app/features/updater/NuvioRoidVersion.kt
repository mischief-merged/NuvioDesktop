package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.features.settings.ForkIdentity

/**
 * Comparable fork version built from the upstream major.minor.patch and the NuvioRoid build number.
 *
 * macOS packaging lifts major 0 to 1 (turning 0.1.36 into 1.1.36), so 1.1.x tags with patch >= 36
 * are normalized back to 0.1.x so older 1.1.36.1 builds never outrank 0.1.36.2+.
 */
data class NuvioRoidVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
) : Comparable<NuvioRoidVersion> {
    override fun compareTo(other: NuvioRoidVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return compareValues(build, other.build)
    }

    fun format(): String = "$major.$minor.$patch.$build"

    companion object {
        const val TAG_PREFIX = "nuvioroid-"
        private val versionPattern = Regex("""^(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$""")

        fun fromForkIdentity(
            upstreamVersion: String = AppVersionPolicy.displayVersionName,
            build: Int = ForkIdentity.BUILD,
        ): NuvioRoidVersion =
            parse(upstreamVersion, explicitBuild = build)
                ?: NuvioRoidVersion(0, 0, 0, build.coerceAtLeast(0))

        fun parse(raw: String?, explicitBuild: Int? = null): NuvioRoidVersion? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val withoutPrefix = if (trimmed.startsWith(TAG_PREFIX, ignoreCase = true)) {
                trimmed.substring(TAG_PREFIX.length)
            } else {
                trimmed
            }.removePrefix("v").removePrefix("V")
            val match = versionPattern.matchEntire(withoutPrefix) ?: return null
            val rawMajor = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            val parsedBuild = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val build = explicitBuild?.takeIf { it > 0 } ?: parsedBuild ?: 0
            val normalizedMajor = if (rawMajor == 1 && minor == 1 && patch >= 36) 0 else rawMajor
            return NuvioRoidVersion(
                major = normalizedMajor,
                minor = minor,
                patch = patch,
                build = build,
            )
        }

        fun isRemoteNewer(
            remoteVersion: String?,
            remoteBuild: Int? = null,
            localVersion: NuvioRoidVersion = fromForkIdentity(),
        ): Boolean {
            val remote = parse(remoteVersion, explicitBuild = remoteBuild) ?: return false
            return remote > localVersion
        }
    }
}
