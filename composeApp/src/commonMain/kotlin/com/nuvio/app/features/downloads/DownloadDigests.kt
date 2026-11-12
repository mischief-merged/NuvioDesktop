package com.nuvio.app.features.downloads

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A digest of the whole body that a server sent along with it. */
internal class ExpectedBodyDigest(
    /** MD5, SHA-1, SHA-256 or SHA-512. */
    val algorithm: String,
    val value: ByteArray,
)

/**
 * The digest of the body that the response headers state, strongest statement first: `Repr-Digest`
 * and `Digest` (RFC 9530 and its predecessor), `x-amz-checksum-sha256` and `-sha1`, `Content-MD5`,
 * the MD5 in `x-goog-hash`, and last an `ETag`, which is taken for an MD5 of the body only from a
 * server that identifies itself as S3, without server-side key encryption, and only when it is 32
 * hexadecimal digits: not a weak tag and not the tag of a multipart upload, which has a dash.
 * [header] returns the value of a response header, by case-insensitive name.
 */
internal fun expectedBodyDigest(header: (String) -> String?): ExpectedBodyDigest? {
    listOf("Repr-Digest", "Digest").forEach { name ->
        header(name)?.split(',')?.forEach { entry ->
            val algorithm = digestAlgorithm(entry.substringBefore('=').trim()) ?: return@forEach
            val value = base64(entry.substringAfter('=', "").trim().trim(':')) ?: return@forEach
            return ExpectedBodyDigest(algorithm, value)
        }
    }
    header("x-amz-checksum-sha256")?.let(::base64)?.let { return ExpectedBodyDigest("SHA-256", it) }
    header("x-amz-checksum-sha1")?.let(::base64)?.let { return ExpectedBodyDigest("SHA-1", it) }
    header("Content-MD5")?.let(::base64)?.takeIf { it.size == 16 }?.let { return ExpectedBodyDigest("MD5", it) }
    header("x-goog-hash")?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("md5=") }
        ?.let { base64(it.removePrefix("md5=")) }?.takeIf { it.size == 16 }
        ?.let { return ExpectedBodyDigest("MD5", it) }

    val fromS3 = header("x-amz-request-id") != null || header("Server").orEmpty().contains("AmazonS3", ignoreCase = true)
    val keyEncrypted = header("x-amz-server-side-encryption").orEmpty().contains("kms", ignoreCase = true) ||
        header("x-amz-server-side-encryption-customer-algorithm") != null
    val tag = header("ETag")?.trim().orEmpty()
    if (fromS3 && !keyEncrypted && !tag.startsWith("W/")) {
        hex(tag.trim('"'))?.takeIf { it.size == 16 }?.let { return ExpectedBodyDigest("MD5", it) }
    }
    return null
}

private fun digestAlgorithm(name: String): String? = when (name.lowercase()) {
    "sha-512" -> "SHA-512"
    "sha-256" -> "SHA-256"
    "sha", "sha-1" -> "SHA-1"
    "md5" -> "MD5"
    else -> null
}

@OptIn(ExperimentalEncodingApi::class)
private fun base64(text: String): ByteArray? =
    runCatching { Base64.decode(text.trim()) }.getOrNull()?.takeIf { it.isNotEmpty() }

private fun hex(text: String): ByteArray? {
    if (text.isEmpty() || text.length % 2 != 0 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
