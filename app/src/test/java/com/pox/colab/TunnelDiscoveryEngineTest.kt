package com.pox.colab

import com.pox.colab.engine.TunnelDiscoveryEngine
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class TunnelDiscoveryEngineTest {

    @Test
    fun getDiscoveryTopic_generatesCleanSanitizedTopic() {
        val vmId = "123e4567-e89b-12d3-a456-426614174000"
        val topic = TunnelDiscoveryEngine.getDiscoveryTopic(vmId)
        assertEquals("colab_vm_123e4567e89b12d3a456426614174000", topic)
    }

    @Test
    fun getDiscoveryUrl_pointsToRawPollEndpoint() {
        val vmId = "test-vm-id-123"
        val url = TunnelDiscoveryEngine.getDiscoveryUrl(vmId)
        assertEquals("https://ntfy.sh/colab_vm_testvmid123/raw?poll=1", url)
    }

    @Test
    fun pollDiscoveredEndpoint_parsesCleanEndpointSuccessfully() = runBlocking {
        val mockClient = Mockito.mock(OkHttpClient::class.java)
        val mockCall = Mockito.mock(Call::class.java)

        val responseBody = "bore.pub:39281\n".toResponseBody()
        val response = Response.Builder()
            .request(Request.Builder().url("https://ntfy.sh/test/raw?poll=1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()

        Mockito.`when`(mockClient.newCall(Mockito.any())).thenReturn(mockCall)
        Mockito.`when`(mockCall.execute()).thenReturn(response)

        val origClient = TunnelDiscoveryEngine.httpClient
        try {
            TunnelDiscoveryEngine.httpClient = mockClient
            val endpoint = TunnelDiscoveryEngine.pollDiscoveredEndpoint("test-vm-1")
            assertNotNull(endpoint)
            assertEquals("bore.pub:39281", endpoint)
        } finally {
            TunnelDiscoveryEngine.httpClient = origClient
        }
    }

    @Test
    fun pollDiscoveredEndpoint_picksLatestLineFromMultiMessageFeed() = runBlocking {
        val mockClient = Mockito.mock(OkHttpClient::class.java)
        val mockCall = Mockito.mock(Call::class.java)

        val responseBody = "old.bore.pub:1111\n\nbore.pub:45892\n".toResponseBody()
        val response = Response.Builder()
            .request(Request.Builder().url("https://ntfy.sh/test/raw?poll=1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()

        Mockito.`when`(mockClient.newCall(Mockito.any())).thenReturn(mockCall)
        Mockito.`when`(mockCall.execute()).thenReturn(response)

        val origClient = TunnelDiscoveryEngine.httpClient
        try {
            TunnelDiscoveryEngine.httpClient = mockClient
            val endpoint = TunnelDiscoveryEngine.pollDiscoveredEndpoint("test-vm-2")
            assertNotNull(endpoint)
            assertEquals("bore.pub:45892", endpoint)
        } finally {
            TunnelDiscoveryEngine.httpClient = origClient
        }
    }

    @Test
    fun pollDiscoveredEndpoint_returnsNullOnBlankOrErrorResponse() = runBlocking {
        val mockClient = Mockito.mock(OkHttpClient::class.java)
        val mockCall = Mockito.mock(Call::class.java)

        val response = Response.Builder()
            .request(Request.Builder().url("https://ntfy.sh/test/raw?poll=1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("".toResponseBody())
            .build()

        Mockito.`when`(mockClient.newCall(Mockito.any())).thenReturn(mockCall)
        Mockito.`when`(mockCall.execute()).thenReturn(response)

        val origClient = TunnelDiscoveryEngine.httpClient
        try {
            TunnelDiscoveryEngine.httpClient = mockClient
            val endpoint = TunnelDiscoveryEngine.pollDiscoveredEndpoint("test-vm-3")
            assertNull(endpoint)
        } finally {
            TunnelDiscoveryEngine.httpClient = origClient
        }
    }
}

