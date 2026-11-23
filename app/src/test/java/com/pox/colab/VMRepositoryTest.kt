package com.pox.colab

import com.pox.colab.data.VMRepository
import com.pox.colab.model.RuntimeEngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VMRepositoryTest {

    @Test
    fun parseEndpoint_withPort() {
        val (host, port) = VMRepository.parseEndpoint("bore.pub:38491")
        assertEquals("bore.pub", host)
        assertEquals(38491, port)
    }

    @Test
    fun parseEndpoint_withSchemePrefix() {
        val (host1, port1) = VMRepository.parseEndpoint("tcp://0.tcp.ngrok.io:12345")
        assertEquals("0.tcp.ngrok.io", host1)
        assertEquals(12345, port1)

        val (host2, port2) = VMRepository.parseEndpoint("ssh://192.168.1.50:2222")
        assertEquals("192.168.1.50", host2)
        assertEquals(2222, port2)
    }

    @Test
    fun parseEndpoint_withoutPortDefaultsTo22() {
        val (host, port) = VMRepository.parseEndpoint("mycolab.tunnel.com")
        assertEquals("mycolab.tunnel.com", host)
        assertEquals(22, port)
    }

    @Test
    fun parseEndpoint_emptyOrWhitespace() {
        val (host, port) = VMRepository.parseEndpoint("   ")
        assertEquals("", host)
        assertEquals(22, port)
    }

    @Test
    fun parseEndpoint_boreDynamicPort() {
        val (host, port) = VMRepository.parseEndpoint("bore.pub:1380")
        assertEquals("bore.pub", host)
        assertEquals(1380, port)

        val (hostUpdated, portUpdated) = VMRepository.parseEndpoint("bore.pub:38429")
        assertEquals("bore.pub", hostUpdated)
        assertEquals(38429, portUpdated)
    }

    @Test
    fun parseEndpoint_stripHttpHttpsPrefix() {
        val (host1, port1) = VMRepository.parseEndpoint("https://tunnel.example.org:8443")
        assertEquals("tunnel.example.org", host1)
        assertEquals(8443, port1)

        val (host2, port2) = VMRepository.parseEndpoint("http://tunnel.example.org")
        assertEquals("tunnel.example.org", host2)
        assertEquals(22, port2)
    }

    @Test
    fun isValidTunnelEndpoint_validatesCorrectly() {
        // Valid public TCP / tunnel endpoints
        assertTrue(VMRepository.isValidTunnelEndpoint("bore.pub:38429"))
        assertTrue(VMRepository.isValidTunnelEndpoint("a.pinggy.io:44321"))
        assertTrue(VMRepository.isValidTunnelEndpoint("serveo.net:2222"))
        assertTrue(VMRepository.isValidTunnelEndpoint("0.tcp.ngrok.io:12345"))

        // Cloudflare Quick Tunnels (trycloudflare.com) are HTTP/WS reverse proxies that block raw TCP port 22 on Android
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("https://my-colab.trycloudflare.com"))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("actual-liverpool-speakers-parent.trycloudflare.com"))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("actual-liverpool-speakers-parent.trycloudflare.com:22"))

        // Invalid / internal / REST endpoints that must never be treated as SSH tunnels
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("https://colab.research.google.com/tun/m/gpu-t4-s-12345"))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("colab.research.google.com"))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("localhost"))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("127.0.0.1:22"))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint(null))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint(""))
        org.junit.Assert.assertFalse(VMRepository.isValidTunnelEndpoint("   "))
    }

    @Test
    fun parseDiscoveredEndpoint_extractsFromOutput() {
        val stdoutWithBoreMarker = """
            === [4/5] Starting Tunnel Provider & Auto-Announcing Endpoint ===
            ==========================================================
               TUNNEL READY: bore.pub:42199
            ==========================================================
            [*] Auto-announced endpoint to Android app successfully.
        """.trimIndent()
        assertEquals("bore.pub:42199", VMRepository.parseDiscoveredEndpoint(stdoutWithBoreMarker))

        val stdoutWithPinggyMarker = """
            TUNNEL READY: a.pinggy.io:55123
        """.trimIndent()
        assertEquals("a.pinggy.io:55123", VMRepository.parseDiscoveredEndpoint(stdoutWithPinggyMarker))

        val stdoutWithRawBoreLog = """
            2026/11/15 05:00:00 [INFO] listening at bore.pub:38491
        """.trimIndent()
        assertEquals("bore.pub:38491", VMRepository.parseDiscoveredEndpoint(stdoutWithRawBoreLog))

        val stdoutWithColabRestUrl = """
            Assigned endpoint: https://colab.research.google.com/tun/m/gpu-12345
        """.trimIndent()
        org.junit.Assert.assertNull(VMRepository.parseDiscoveredEndpoint(stdoutWithColabRestUrl))
    }

    @Test
    fun runtimeEngineMode_enumProperties() {
        val nativeMode = RuntimeEngineMode.NATIVE_API
        assertEquals("NATIVE_API", nativeMode.name)
        assertTrue(nativeMode.label.contains("Native API") || nativeMode.label.contains("Zero Bloat"))
        assertTrue(nativeMode.description.isNotBlank())
        assertTrue(nativeMode.badge.isNotBlank())

        val alpineMode = RuntimeEngineMode.ALPINE_PROOT
        assertEquals("ALPINE_PROOT", alpineMode.name)
        assertTrue(alpineMode.label.contains("Alpine") || alpineMode.label.contains("PRoot"))
        assertTrue(alpineMode.description.isNotBlank())
        assertTrue(alpineMode.badge.isNotBlank())

        assertEquals(2, RuntimeEngineMode.values().size)
    }
}
