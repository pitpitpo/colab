package com.pox.colab

import com.pox.colab.engine.ColabApiEngine
import com.pox.colab.engine.ColabRuntime
import com.pox.colab.engine.HttpClientProvider
import com.pox.colab.engine.ResilientSSHManager
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.util.CryptoUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class EnginePerformanceAndReliabilityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `isTokenExpiringSoon handles fresh, near-expiry, expired, epoch, and invalid values`() {
        val freshIso = Instant.now().plusSeconds(1800).truncatedTo(ChronoUnit.SECONDS).toString()
        val nearExpiryIso = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.SECONDS).toString()
        val expiredIso = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS).toString()
        val freshEpochSec = (Instant.now().epochSecond + 900).toString()
        val freshEpochMs = ((Instant.now().epochSecond + 900) * 1000L).toString()

        assertFalse(ColabApiEngine.isTokenExpiringSoon(freshIso))
        assertTrue(ColabApiEngine.isTokenExpiringSoon(nearExpiryIso))
        assertTrue(ColabApiEngine.isTokenExpiringSoon(expiredIso))
        assertFalse(ColabApiEngine.isTokenExpiringSoon(freshEpochSec))
        assertFalse(ColabApiEngine.isTokenExpiringSoon(freshEpochMs))
        assertTrue(ColabApiEngine.isTokenExpiringSoon(""))
        assertTrue(ColabApiEngine.isTokenExpiringSoon("not-a-timestamp"))
    }

    @Test
    fun `refreshOAuthTokenIfNeeded skips HTTP round-trip when cached token is fresh`() = runBlocking {
        val futureExpiry = Instant.now().plusSeconds(3000).truncatedTo(ChronoUnit.SECONDS).toString()
        val tokenBlob = JSONObject().apply {
            put("token", "cached-fresh-token")
            put("refresh_token", "ref-123")
            put("client_id", "cid-123")
            put("token_uri", server.url("/token").toString())
            put("expiry", futureExpiry)
        }.toString()

        val account = ColabAccount(
            email = "user@example.com",
            displayName = "User",
            tokenOrCookie = "cached-fresh-token",
            oauthToken = tokenBlob
        )

        val token = ColabApiEngine.refreshOAuthTokenIfNeeded(account, client = OkHttpClient())
        assertEquals("cached-fresh-token", token)
        assertEquals("Fresh token must not make an unnecessary OAuth HTTP request", 0, server.requestCount)
    }

    @Test
    fun `refreshOAuthTokenIfNeeded refreshes expired token and persists new RFC3339 expiry`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"refreshed-token-xyz","expires_in":3600}""")
        )
        val pastExpiry = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.SECONDS).toString()
        val tokenBlob = JSONObject().apply {
            put("token", "stale-token")
            put("refresh_token", "ref-123")
            put("client_id", "cid-123")
            put("token_uri", server.url("/token").toString())
            put("expiry", pastExpiry)
        }.toString()

        val account = ColabAccount(
            email = "user@example.com",
            displayName = "User",
            tokenOrCookie = "stale-token",
            oauthToken = tokenBlob
        )

        val refreshed = ColabApiEngine.refreshOAuthTokenIfNeeded(account, client = OkHttpClient())
        assertEquals("refreshed-token-xyz", refreshed)
        assertEquals(1, server.requestCount)
        assertEquals("refreshed-token-xyz", account.tokenOrCookie)

        val updatedJson = JSONObject(account.oauthToken)
        assertEquals("refreshed-token-xyz", updatedJson.getString("token"))
        assertFalse("Updated expiry should now be fresh", ColabApiEngine.isTokenExpiringSoon(updatedJson.getString("expiry")))

        // Second call immediately after must skip HTTP refresh
        val second = ColabApiEngine.refreshOAuthTokenIfNeeded(account, client = OkHttpClient())
        assertEquals("refreshed-token-xyz", second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `encodeContentPath percent-encodes special characters per segment while keeping slashes`() {
        assertEquals("", ColabApiEngine.encodeContentPath("/"))
        assertEquals("content/my%20notes/report%20%231%20%25.txt", ColabApiEngine.encodeContentPath("/content/my notes/report #1 %.txt"))
        assertEquals("tmp/colab_tunnel_endpoint.txt", ColabApiEngine.encodeContentPath("///tmp//colab_tunnel_endpoint.txt/"))
    }

    @Test
    fun `terminateRuntime evicts cached kernel ID and sends runtime proxy headers on proxy-only fallback`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val engine = ColabApiEngine(httpClientProvider = { OkHttpClient() })
        val runtime = ColabRuntime(
            sessionId = "sess-1",
            endpointUrl = server.url("/runtime/1").toString().trimEnd('/'),
            datalabBackendUrl = "",
            proxyToken = "proxy-secret-tok"
        )

        val result = engine.terminateRuntime("", runtime)
        assertTrue(result.isSuccess)
        assertNull(engine.getCachedKernelId(runtime.endpointUrl))

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("proxy-secret-tok", req.getHeader("X-Colab-Runtime-Proxy-Token"))
        assertEquals("colab-cli", req.getHeader("X-Colab-Client-Agent"))
    }

    @Test
    fun `ResilientSSHManager reassembles split TCP lines and bounds ring buffer to MAX_TERMINAL_LINES`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = ColabVM(name = "TestNode", accountId = "acc-1")
            val manager = ResilientSSHManager(vm = vm, scope = scope)
            manager.clearOutput()

            // Split lines across chunk boundaries
            manager.appendRawOutput("hel")
            manager.appendRawOutput("lo world\r\nsecond ")
            manager.appendRawOutput("line\npartial_prompt$ ")

            // Wait for 16ms frame-paced flush
            delay(80L)
            val lines = manager.terminalOutput.value
            assertEquals(3, lines.size)
            assertEquals("hello world", lines[0].text)
            assertEquals("second line", lines[1].text)
            assertEquals("partial_prompt$ ", lines[2].text)

            // Feed 2200 more lines and confirm ring buffer stays strictly bounded
            val bulk = buildString {
                for (i in 1..2200) {
                    append("line_").append(i).append('\n')
                }
            }
            manager.appendRawOutput(bulk)
            delay(80L)

            val capped = manager.terminalOutput.value
            assertEquals(ResilientSSHManager.MAX_TERMINAL_LINES, capped.size)
            assertEquals("line_2200", capped.last().text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `CryptoUtil supports concurrent AES-GCM encrypt and decrypt with unique random IVs`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..40).map { i ->
                Callable {
                    val plaintext = "secret-oauth-payload-$i-${"x".repeat(i * 3)}"
                    val enc1 = CryptoUtil.encrypt(plaintext)
                    val enc2 = CryptoUtil.encrypt(plaintext)
                    assertNotEquals("Each encryption must use a fresh random IV", enc1, enc2)
                    assertEquals(plaintext, CryptoUtil.decrypt(enc1))
                    assertEquals(plaintext, CryptoUtil.decrypt(enc2))
                    true
                }
            }
            val results = executor.invokeAll(tasks)
            results.forEach { assertTrue(it.get()) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `HttpClientProvider shares underlying ConnectionPool and Dispatcher across profiles`() {
        val api = HttpClientProvider.apiClient
        val auth = HttpClientProvider.authClient
        val fast = HttpClientProvider.fastClient
        val telemetry = HttpClientProvider.telemetryClient

        assertSame(api.connectionPool, auth.connectionPool)
        assertSame(api.connectionPool, fast.connectionPool)
        assertSame(api.connectionPool, telemetry.connectionPool)
        assertSame(api.dispatcher, auth.dispatcher)
        assertSame(api.dispatcher, fast.dispatcher)
        assertSame(api.dispatcher, telemetry.dispatcher)
    }
}
