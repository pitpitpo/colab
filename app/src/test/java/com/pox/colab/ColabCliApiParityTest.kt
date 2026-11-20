package com.pox.colab

import com.pox.colab.engine.ColabAccelerator
import com.pox.colab.engine.ColabApiEngine
import com.pox.colab.engine.ColabRuntime
import com.pox.colab.engine.ColabVariant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * End-to-end MockWebServer verification suite ensuring ColabApiEngine matches
 * google-colab-cli / colab-vscode REST and WebSocket protocol semantics.
 */
class ColabCliApiParityTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: ColabApiEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("").toString().trimEnd('/')
        engine = ColabApiEngine(
            httpClientProvider = { OkHttpClient() },
            colabBaseUrl = baseUrl
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `allocateRuntime sends variant=GPU, accelerator, shape=hm, XSRF handshake, and parses proxy TTL`() = runBlocking {
        // Step 1: GET /tun/m/assign returns XSRF token
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(")]}'\n{\"token\":\"xsrf-assign-123\"}")
        )
        // Step 2: POST /tun/m/assign returns provisioned runtime with proxy info & TTL
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """)]}'
                    {
                      "endpoint": "m-s-gpu1234",
                      "accelerator": "A100",
                      "variant": "GPU",
                      "machineShape": "hm",
                      "runtimeProxyInfo": {
                        "url": "https://8080-m-s-gpu1234.prod.colab.dev",
                        "token": "proxy-tok-xyz",
                        "tokenTtl": "3600s"
                      }
                    }
                    """.trimIndent()
                )
        )

        val jsonTokenBlob = JSONObject().put("token", "ya29.real-access-token").toString()
        val result = engine.allocateRuntime(
            bearerToken = jsonTokenBlob,
            accelerator = ColabAccelerator.A100,
            authUser = 1,
            highMemory = true
        )

        assertTrue("Allocation should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val runtime = result.getOrThrow()
        assertEquals("m-s-gpu1234", runtime.sessionId)
        assertEquals("m-s-gpu1234", runtime.endpointId)
        assertEquals("https://8080-m-s-gpu1234.prod.colab.dev", runtime.endpointUrl)
        assertEquals("proxy-tok-xyz", runtime.proxyToken)
        assertEquals(ColabAccelerator.A100, runtime.accelerator)
        assertEquals("GPU", runtime.variant)
        assertEquals("hm", runtime.machineShape)
        assertTrue("Proxy token expiry should be populated in the future", runtime.proxyTokenExpiresAtMs > System.currentTimeMillis() + 3_000_000L)
        assertFalse("Fresh hydrated runtime should not need proxy resolution", runtime.needsProxyResolution())

        val req1 = server.takeRequest()
        assertEquals("GET", req1.method)
        assertTrue("Path should contain /tun/m/assign", req1.path!!.startsWith("/tun/m/assign?"))
        assertTrue("Query must include variant=GPU", req1.path!!.contains("variant=GPU"))
        assertTrue("Query must include accelerator=A100", req1.path!!.contains("accelerator=A100"))
        assertTrue("Query must include shape=hm", req1.path!!.contains("shape=hm"))
        assertTrue("Query must include authuser=1", req1.path!!.contains("authuser=1"))
        assertEquals("Bearer ya29.real-access-token", req1.getHeader("Authorization"))
        assertEquals("colab-cli", req1.getHeader("X-Colab-Client-Agent"))

        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("xsrf-assign-123", req2.getHeader("X-Goog-Colab-Token"))
        assertEquals("Bearer ya29.real-access-token", req2.getHeader("Authorization"))
        assertEquals("colab-cli", req2.getHeader("X-Colab-Client-Agent"))
    }

    @Test
    fun `allocateRuntime supports TPU variant and acceleratorOverride`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """)]}'
                    {
                      "endpoint": "m-s-tpu99",
                      "variant": "TPU",
                      "runtimeProxyInfo": {
                        "url": "https://8080-m-s-tpu99.prod.colab.dev",
                        "token": "tpu-tok",
                        "tokenExpiresInSeconds": 3600
                      }
                    }
                    """.trimIndent()
                )
        )

        val result = engine.allocateRuntime(
            bearerToken = "ya29.tpu-token",
            accelerator = ColabAccelerator.NONE,
            variant = ColabVariant.TPU,
            acceleratorOverride = "V5E1"
        )

        assertTrue(result.isSuccess)
        assertEquals(1, server.requestCount) // Already assigned on GET
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("variant=TPU"))
        assertTrue(req.path!!.contains("accelerator=V5E1"))
    }

    @Test
    fun `allocateRuntime surfaces HTTP 412 too many runtimes and OUTCOME_QUOTA_DENIED clearly`() = runBlocking {
        // Case 1: HTTP 412
        server.enqueue(MockResponse().setResponseCode(412).setBody("Too many assignments"))
        val res412 = engine.allocateRuntime("ya29.tok", ColabAccelerator.T4)
        assertTrue(res412.isFailure)
        assertTrue(res412.exceptionOrNull()!!.message!!.contains("412"))

        // Case 2: OUTCOME_QUOTA_DENIED on POST
        server.enqueue(MockResponse().setResponseCode(200).setBody(")]}'\n{\"token\":\"xsrf-q\"}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(")]}'\n{\"outcome\":\"OUTCOME_QUOTA_DENIED\"}")
        )
        val resQuota = engine.allocateRuntime("ya29.tok", ColabAccelerator.A100)
        assertTrue(resQuota.isFailure)
        assertTrue(resQuota.exceptionOrNull()!!.message!!.contains("QUOTA_DENIED"))
    }

    @Test
    fun `refreshRuntimeProxyToken hydrates proxy URL and token and ensureRuntimeProxyReady skips when fresh`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """)]}'
                    {
                      "token": "fresh-proxy-token-abc",
                      "url": "https://8080-m-s-hydrate1.prod.colab.dev",
                      "tokenTtl": "3600s"
                    }
                    """.trimIndent()
                )
        )

        val unhydrated = ColabRuntime(
            sessionId = "m-s-hydrate1",
            endpointUrl = "https://colab.research.google.com/tun/m/m-s-hydrate1",
            datalabBackendUrl = "",
            proxyToken = ""
        )
        assertTrue(unhydrated.needsProxyResolution())

        val hydratedRes = engine.ensureRuntimeProxyReady("ya29.oauth-tok", unhydrated)
        assertTrue(hydratedRes.isSuccess)
        val hydrated = hydratedRes.getOrThrow()
        assertEquals("https://8080-m-s-hydrate1.prod.colab.dev", hydrated.endpointUrl)
        assertEquals("fresh-proxy-token-abc", hydrated.proxyToken)
        assertFalse(hydrated.needsProxyResolution())
        assertEquals(1, server.requestCount)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/tun/m/runtime-proxy-token?endpoint=m-s-hydrate1&port=8080&authuser=0", req.path)
        assertEquals("Google", req.getHeader("X-Colab-Tunnel"))
        assertEquals("colab-cli", req.getHeader("X-Colab-Client-Agent"))
        assertEquals("Bearer ya29.oauth-tok", req.getHeader("Authorization"))

        // Second call immediately after must perform zero network requests
        val cachedRes = engine.ensureRuntimeProxyReady("ya29.oauth-tok", hydrated)
        assertTrue(cachedRes.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `listAssignments handles both array and object payloads and hydrates proxy tokens when requested`() = runBlocking {
        // Response 1: Raw JSON array from /tun/m/assignments (no runtimeProxyInfo)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """)]}'
                    [
                      {
                        "endpoint": "m-s-list99",
                        "accelerator": "T4",
                        "variant": "GPU"
                      }
                    ]
                    """.trimIndent()
                )
        )
        // Response 2: Hydration via /tun/m/runtime-proxy-token
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """)]}'
                    {
                      "token": "hydrated-tok-99",
                      "url": "https://8080-m-s-list99.prod.colab.dev",
                      "tokenTtl": "3600s"
                    }
                    """.trimIndent()
                )
        )

        val result = engine.listAssignments("ya29.list-tok", authUser = 0, hydrateProxyTokens = true)
        assertTrue(result.isSuccess)
        val runtimes = result.getOrThrow()
        assertEquals(1, runtimes.size)
        assertEquals("m-s-list99", runtimes[0].sessionId)
        assertEquals("https://8080-m-s-list99.prod.colab.dev", runtimes[0].endpointUrl)
        assertEquals("hydrated-tok-99", runtimes[0].proxyToken)

        val assignReq = server.takeRequest()
        assertEquals("/tun/m/assignments?authuser=0", assignReq.path)
        assertEquals("colab-cli", assignReq.getHeader("X-Colab-Client-Agent"))

        val proxyReq = server.takeRequest()
        assertEquals("/tun/m/runtime-proxy-token?endpoint=m-s-list99&port=8080&authuser=0", proxyReq.path)
    }

    @Test
    fun `terminateRuntime performs authentic 2-step XSRF unassign and evicts kernel cache`() = runBlocking {
        // Seed kernel cache via createKernel
        val runtimeEndpoint = server.url("/proxy").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"id":"kern-cached-1","name":"python3","execution_state":"idle"}""")
        )
        val runtime = ColabRuntime(
            sessionId = "m-s-term77",
            endpointUrl = runtimeEndpoint,
            datalabBackendUrl = "",
            proxyToken = "proxy-tok"
        )
        assertTrue(engine.createKernel(runtime).isSuccess)
        assertEquals("kern-cached-1", engine.getCachedKernelId(runtimeEndpoint))

        // Step 1 & 2 of /tun/m/unassign/m-s-term77
        server.enqueue(MockResponse().setResponseCode(200).setBody(")]}'\n{\"token\":\"xsrf-unassign-77\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(")]}'\n{}"))

        val termRes = engine.terminateRuntime("ya29.term-oauth", runtime)
        assertTrue("terminateRuntime should succeed: ${termRes.exceptionOrNull()}", termRes.isSuccess)
        assertNull("Kernel cache must be evicted", engine.getCachedKernelId(runtimeEndpoint))

        server.takeRequest() // createKernel
        val getUnassign = server.takeRequest()
        assertEquals("GET", getUnassign.method)
        assertEquals("/tun/m/unassign/m-s-term77?authuser=0", getUnassign.path)
        assertEquals("Bearer ya29.term-oauth", getUnassign.getHeader("Authorization"))
        assertEquals("colab-cli", getUnassign.getHeader("X-Colab-Client-Agent"))

        val postUnassign = server.takeRequest()
        assertEquals("POST", postUnassign.method)
        assertEquals("/tun/m/unassign/m-s-term77?authuser=0", postUnassign.path)
        assertEquals("xsrf-unassign-77", postUnassign.getHeader("X-Goog-Colab-Token"))
    }

    @Test
    fun `keepAlive sends GET with X-Colab-Tunnel Google header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(")]}'\n{}"))

        val runtime = ColabRuntime(
            sessionId = "m-s-alive55",
            endpointUrl = "https://8080-m-s-alive55.prod.colab.dev",
            datalabBackendUrl = "",
            proxyToken = "ptok"
        )

        val res = engine.keepAlive("ya29.alive-tok", runtime)
        assertTrue(res.isSuccess)
        assertEquals(true, res.getOrThrow())

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/tun/m/m-s-alive55/keep-alive/?authuser=0", req.path)
        assertEquals("Google", req.getHeader("X-Colab-Tunnel"))
        assertEquals("colab-cli", req.getHeader("X-Colab-Client-Agent"))
        assertEquals("Bearer ya29.alive-tok", req.getHeader("Authorization"))
    }

    @Test
    fun `checkRuntimeStatus uses api-status primarily and falls back to status on 404`() = runBlocking {
        val runtime = ColabRuntime(
            sessionId = "m-s-stat1",
            endpointUrl = server.url("/rt").toString().trimEnd('/'),
            datalabBackendUrl = "",
            proxyToken = "ptok-1"
        )

        // Primary: /api/status succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"started":"2026-11-19T10:00:00Z","last_activity":"2026-11-19T10:05:00Z","connections":2,"kernels":1}""")
        )
        val primary = engine.checkRuntimeStatus("", runtime).getOrThrow()
        assertTrue(primary.isAlive)
        assertEquals("RUNNING", primary.state)
        assertEquals(1, primary.kernels)
        assertEquals(2, primary.connections)
        assertEquals("2026-11-19T10:05:00Z", primary.lastActivity)
        assertEquals("/rt/api/status", server.takeRequest().path)

        // Fallback: /api/status returns 404 -> falls back to /status
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"RUNNING"}"""))
        val fallback = engine.checkRuntimeStatus("", runtime).getOrThrow()
        assertTrue(fallback.isAlive)
        assertEquals("RUNNING", fallback.state)
        assertEquals("/rt/api/status", server.takeRequest().path)
        assertEquals("/rt/status", server.takeRequest().path)
    }

    @Test
    fun `kernel and terminal lifecycle endpoints match Jupyter REST API`() = runBlocking {
        val runtime = ColabRuntime(
            sessionId = "m-s-jup1",
            endpointUrl = server.url("/jup").toString().trimEnd('/'),
            datalabBackendUrl = "",
            proxyToken = "jup-tok"
        )

        // listKernels
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"k-1","name":"python3","execution_state":"idle","connections":1}]"""))
        val kernels = engine.listKernels(runtime).getOrThrow()
        assertEquals(1, kernels.size)
        assertEquals("k-1", kernels[0].id)
        assertEquals("idle", kernels[0].executionState)

        // createKernel
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"k-2","name":"python3","execution_state":"starting"}"""))
        val created = engine.createKernel(runtime).getOrThrow()
        assertEquals("k-2", created.id)
        assertEquals("k-2", engine.getCachedKernelId(runtime.endpointUrl))

        // interruptKernel (uses cached k-2)
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(engine.interruptKernel(runtime).isSuccess)

        // restartKernel (uses cached k-2)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"k-2"}"""))
        assertTrue(engine.restartKernel(runtime).isSuccess)

        // deleteKernel (evicts cache)
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(engine.deleteKernel(runtime, "k-2").isSuccess)
        assertNull(engine.getCachedKernelId(runtime.endpointUrl))

        // listTerminals, createTerminal, deleteTerminal
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"name":"1","last_activity":"2026-11-19T12:00:00Z"}]"""))
        val terms = engine.listTerminals(runtime).getOrThrow()
        assertEquals(1, terms.size)
        assertEquals("1", terms[0].name)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"2","last_activity":"2026-11-19T12:01:00Z"}"""))
        val newTerm = engine.createTerminal(runtime).getOrThrow()
        assertEquals("2", newTerm.name)

        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(engine.deleteTerminal(runtime, "2").isSuccess)

        assertEquals("GET", server.takeRequest().method)
        assertEquals("/jup/api/kernels", server.takeRequest().path)
        assertEquals("/jup/api/kernels/k-2/interrupt", server.takeRequest().path)
        assertEquals("/jup/api/kernels/k-2/restart", server.takeRequest().path)
        val delKReq = server.takeRequest()
        assertEquals("DELETE", delKReq.method)
        assertEquals("/jup/api/kernels/k-2", delKReq.path)
        assertEquals("/jup/api/terminals", server.takeRequest().path)
        assertEquals("POST", server.takeRequest().method)
        val delTReq = server.takeRequest()
        assertEquals("DELETE", delTReq.method)
        assertEquals("/jup/api/terminals/2", delTReq.path)
    }

    @Test
    fun `writeFileBytes and readFileBytes preserve raw binary data and readFile decodes base64 text`() = runBlocking {
        val runtime = ColabRuntime(
            sessionId = "m-s-fs1",
            endpointUrl = server.url("/fs").toString().trimEnd('/'),
            datalabBackendUrl = "",
            proxyToken = "fs-tok"
        )
        val binaryData = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x01, 0x7F, 0xFF.toByte(), 0xFE.toByte()
        )
        val expectedB64 = Base64.getEncoder().encodeToString(binaryData)

        // writeFileBytes
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"path":"content/model.bin"}"""))
        assertTrue(engine.writeFileBytes(runtime, "/content/model.bin", binaryData).isSuccess)
        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        assertEquals("/fs/api/contents/content/model.bin", putReq.path)
        val putJson = JSONObject(putReq.body.readUtf8())
        assertEquals("base64", putJson.getString("format"))
        assertEquals(expectedB64, putJson.getString("content"))

        // readFileBytes
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"type":"file","format":"base64","content":"$expectedB64"}""")
        )
        val downloaded = engine.readFileBytes(runtime, "/content/model.bin").getOrThrow()
        assertArrayEquals(binaryData, downloaded)
        val getBytesReq = server.takeRequest()
        assertTrue(getBytesReq.path!!.contains("format=base64"))

        // readFile decoding a base64-encoded UTF-8 file transparently
        val utf8Text = "hello colab cli \u2713"
        val utf8B64 = Base64.getEncoder().encodeToString(utf8Text.toByteArray(Charsets.UTF_8))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"type":"file","format":"base64","content":"$utf8B64"}""")
        )
        assertEquals(utf8Text, engine.readFile(runtime, "/content/note.txt").getOrThrow())
    }

    @Test
    fun `runCode and executeShellCommand stream WebSocket output and auto-retry stale cached kernel`() = runBlocking {
        val runtime = ColabRuntime(
            sessionId = "m-s-ws1",
            endpointUrl = server.url("/wsrt").toString().trimEnd('/'),
            datalabBackendUrl = "",
            proxyToken = "ws-tok"
        )

        // Seed stale kernel ID in cache via createKernel
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"stale-kern","name":"python3"}"""))
        assertTrue(engine.createKernel(runtime).isSuccess)
        assertEquals("stale-kern", engine.getCachedKernelId(runtime.endpointUrl))

        // Attempt 1 against stale-kern fails WebSocket upgrade with 404
        server.enqueue(MockResponse().setResponseCode(404).setBody("Kernel does not exist"))

        // Auto-retry discovers fresh kernel via GET /api/kernels
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"id":"fresh-kern","name":"python3","execution_state":"idle"}]""")
        )

        // Attempt 2 upgrades WebSocket on fresh-kern and replies with Jupyter v5.3 frames
        var receivedCode: String? = null
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val req = JSONObject(text)
                    val msgId = req.getJSONObject("header").getString("msg_id")
                    receivedCode = req.getJSONObject("content").getString("code")

                    webSocket.send(
                        JSONObject()
                            .put("msg_type", "stream")
                            .put("parent_header", JSONObject().put("msg_id", msgId))
                            .put("content", JSONObject().put("name", "stdout").put("text", "gpu-ok\n"))
                            .toString()
                    )
                    webSocket.send(
                        JSONObject()
                            .put("msg_type", "display_data")
                            .put("parent_header", JSONObject().put("msg_id", msgId))
                            .put("content", JSONObject().put("data", JSONObject().put("text/plain", "Tesla T4")))
                            .toString()
                    )
                    webSocket.send(
                        JSONObject()
                            .put("msg_type", "status")
                            .put("parent_header", JSONObject().put("msg_id", msgId))
                            .put("content", JSONObject().put("execution_state", "idle"))
                            .toString()
                    )
                    webSocket.close(1000, "Done")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            })
        )

        val execRes = engine.executeShellCommand("", runtime, "nvidia-smi -L", timeoutMs = 10_000L).getOrThrow()
        assertTrue("Expected execution to succeed after stale-kernel retry: $execRes", execRes.success)
        assertEquals("gpu-ok\nTesla T4", execRes.stdout)
        assertEquals("", execRes.stderr)
        assertNotNull(receivedCode)
        assertTrue("executeShellCommand must wrap command in %%bash", receivedCode!!.startsWith("%%bash\n"))
        assertEquals("fresh-kern", engine.getCachedKernelId(runtime.endpointUrl))
    }

    @Test
    fun `getCcuInfo unpacks JSON OAuth blob and strips XSSI prefix`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """)]}'
                    {
                      "currentBalance": 92.5,
                      "consumptionRateHourly": 1.96,
                      "assignmentsCount": 1,
                      "eligibleGpus": ["T4", "L4", "A100"],
                      "eligibleTpus": ["V5E1"]
                    }
                    """.trimIndent()
                )
        )

        val blob = JSONObject().put("access_token", "ya29.ccu-tok").toString()
        val info = engine.getCcuInfo(blob).getOrThrow()
        assertEquals(92.5, info.currentBalance, 0.001)
        assertEquals(1.96, info.consumptionRateHourly, 0.001)
        assertEquals(1, info.assignmentsCount)
        assertEquals(listOf("T4", "L4", "A100"), info.eligibleGpus)
        assertEquals(listOf("V5E1"), info.eligibleTpus)

        val req = server.takeRequest()
        assertEquals("/tun/m/ccu-info?authuser=0", req.path)
        assertEquals("Bearer ya29.ccu-tok", req.getHeader("Authorization"))
        assertEquals("colab-cli", req.getHeader("X-Colab-Client-Agent"))
    }
}
