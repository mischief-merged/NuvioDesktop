package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadHostsTest {
    @Test
    fun public_hosts_are_accepted() {
        listOf(
            "https://example.com/a.mp4",
            "http://cdn.example.com:8080/a.mp4?x=1",
            "https://user:secret@example.com/a.mp4",
            "https://93.184.216.34/a.mp4",
            "https://172.32.0.1/a.mp4",
            "https://100.128.0.1/a.mp4",
            "https://[2606:4700::1111]/a.mp4",
            "https://10.example.com/a.mp4",
        ).forEach { assertTrue(it.hasPublicDownloadHost(), it) }
    }

    @Test
    fun this_computer_is_refused() {
        listOf(
            "http://localhost/a.mp4",
            "http://LOCALHOST:8080/a.mp4",
            "http://localhost./a.mp4",
            "http://media.localhost/a.mp4",
            "http://127.0.0.1:11470/a.mp4",
            "http://127.8.9.10/a.mp4",
            "http://0.0.0.0/a.mp4",
            "http://[::1]:8080/a.mp4",
            "http://[::]/a.mp4",
            "http://user@127.0.0.1/a.mp4",
            "http://example.com@127.0.0.1/a.mp4",
        ).forEach { assertFalse(it.hasPublicDownloadHost(), it) }
    }

    @Test
    fun private_and_link_local_networks_are_refused() {
        listOf(
            "http://10.0.0.5/a.mp4",
            "http://172.16.0.1/a.mp4",
            "http://172.31.255.255/a.mp4",
            "http://192.168.1.20:8096/a.mp4",
            "http://169.254.169.254/latest",
            "http://100.64.0.1/a.mp4",
            "http://nas.local/a.mp4",
            "http://[fe80::1%25en0]/a.mp4",
            "http://[fd12:3456::1]/a.mp4",
            "http://[fc00::1]/a.mp4",
            "http://[::ffff:192.168.1.20]/a.mp4",
        ).forEach { assertFalse(it.hasPublicDownloadHost(), it) }
    }

    @Test
    fun a_url_without_a_host_is_refused() {
        listOf("", "example.com/a.mp4", "http:///a.mp4", "file:///a.mp4").forEach {
            assertFalse(it.hasPublicDownloadHost(), it)
        }
    }
}
