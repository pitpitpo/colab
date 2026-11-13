package com.pox.colab

import com.pox.colab.data.VMRepository
import org.junit.Assert.assertEquals
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
}
