package com.nuvio.app.features.downloads

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadDigestsTest {
    private val body = "the body of a download".encodeToByteArray()

    private fun digest(algorithm: String) = MessageDigest.getInstance(algorithm).digest(body)

    private fun base64(algorithm: String) = Base64.getEncoder().encodeToString(digest(algorithm))

    private fun hex(algorithm: String) = digest(algorithm).joinToString("") { "%02x".format(it) }

    private fun expected(vararg headers: Pair<String, String>): ExpectedBodyDigest? {
        val byName = headers.associate { it.first.lowercase() to it.second }
        return expectedBodyDigest { name -> byName[name.lowercase()] }
    }

    private fun assertDigest(algorithm: String, actual: ExpectedBodyDigest?) {
        assertEquals(algorithm, actual?.algorithm)
        assertContentEquals(digest(algorithm), actual?.value)
    }

    @Test
    fun content_md5_is_an_md5_of_the_body() {
        assertDigest("MD5", expected("Content-MD5" to base64("MD5")))
    }

    @Test
    fun a_representation_digest_is_read_in_both_of_its_spellings() {
        assertDigest("SHA-256", expected("Repr-Digest" to "sha-256=:${base64("SHA-256")}:"))
        assertDigest("SHA-512", expected("Digest" to "sha-512=:${base64("SHA-512")}:"))
        assertDigest("SHA-256", expected("Digest" to "SHA-256=${base64("SHA-256")}"))
        assertDigest("MD5", expected("Digest" to "unixsum=1234, md5=${base64("MD5")}"))
    }

    @Test
    fun the_checksums_of_the_large_object_stores_are_read() {
        assertDigest("SHA-256", expected("x-amz-checksum-sha256" to base64("SHA-256")))
        assertDigest("SHA-1", expected("x-amz-checksum-sha1" to base64("SHA-1")))
        assertDigest("MD5", expected("x-goog-hash" to "crc32c=AAAAAA==, md5=${base64("MD5")}"))
    }

    @Test
    fun the_strongest_statement_wins() {
        assertDigest("SHA-256", expected("Content-MD5" to base64("MD5"), "Repr-Digest" to "sha-256=:${base64("SHA-256")}:"))
    }

    @Test
    fun an_etag_is_an_md5_only_from_s3_and_only_in_its_plain_form() {
        assertDigest("MD5", expected("ETag" to "\"${hex("MD5")}\"", "x-amz-request-id" to "ABC"))
        assertDigest("MD5", expected("ETag" to "\"${hex("MD5")}\"", "Server" to "AmazonS3"))
    }

    @Test
    fun the_etag_of_a_multipart_upload_a_weak_etag_and_an_etag_of_another_server_are_ignored() {
        assertNull(expected("ETag" to "\"${hex("MD5")}-12\"", "x-amz-request-id" to "ABC"))
        assertNull(expected("ETag" to "W/\"${hex("MD5")}\"", "x-amz-request-id" to "ABC"))
        assertNull(expected("ETag" to "\"${hex("MD5")}\""))
        assertNull(expected("ETag" to "\"5f3a-61c2d\"", "Server" to "nginx"))
        assertNull(expected("ETag" to "\"${hex("MD5")}\"", "x-amz-request-id" to "ABC", "x-amz-server-side-encryption" to "aws:kms"))
    }

    @Test
    fun headers_without_a_usable_digest_give_none() {
        assertNull(expected())
        assertNull(expected("Content-MD5" to "not base64 !!"))
        assertNull(expected("Content-MD5" to "AAAA"))
        assertNull(expected("Digest" to "crc32c=:AAAAAA==:"))
        assertNull(expected("x-goog-hash" to "crc32c=AAAAAA=="))
    }
}
