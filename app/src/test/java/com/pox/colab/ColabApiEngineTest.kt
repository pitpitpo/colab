package com.pox.colab

import com.pox.colab.engine.CellExecutionResult
import com.pox.colab.engine.ColabAccelerator
import com.pox.colab.engine.ColabApiEngine
import com.pox.colab.engine.ColabRuntime
import com.pox.colab.engine.toColabAccelerator
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.HardwareAccelerator
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ColabApiEngine].
 *
 * All tests exercise only the pure parsing/helper methods — no live network calls are made,
 * keeping the suite deterministic and fast on any CI runner without credentials.
 *
 * Network-integration paths (allocateRuntime, runCode, terminateRuntime) are covered
 * by the authenticated CI smoke-test pipeline that runs against the real Colab backend
 * using repository secrets.
 */
class ColabApiEngineTest {

    private lateinit var engine: ColabApiEngine

    @Before
    fun setUp() {
        engine = ColabApiEngine()
    }

    // ---------------------------------------------------------------------------
    // 1. XSSI prefix stripping
    // ---------------------------------------------------------------------------

    @Test
    fun stripXssiPrefix_removesCanonicalPrefix() {
        val body = ")]}'\n{\"key\":\"value\"}"
        val stripped = engine.stripXssiPrefix(body)
        assertEquals("{\"key\":\"value\"}", stripped)
    }

    @Test
    fun stripXssiPrefix_removesAltPrefix() {
        // Some Colab endpoints omit the \n
        val body = ")]}'{\"key\":\"value\"}"
        val stripped = engine.stripXssiPrefix(body)
        assertEquals("{\"key\":\"value\"}", stripped)
    }

    @Test
    fun stripXssiPrefix_leavesNonPrefixedBodyUnchanged() {
        val body = "{\"key\":\"value\"}"
        assertEquals(body, engine.stripXssiPrefix(body))
    }

    @Test
    fun stripXssiPrefix_handlesEmptyString() {
        assertEquals("", engine.stripXssiPrefix(""))
    }

    @Test
    fun stripXssiPrefix_handlesBodyWithOnlyCanonicalPrefix() {
        assertEquals("", engine.stripXssiPrefix(")]}'\n"))
    }

    @Test
    fun stripXssiPrefix_handlesBodyWithOnlyAltPrefix() {
        // ")]}'" with no newline
        assertEquals("", engine.stripXssiPrefix(")]}' ").trimStart())
    }

    // ---------------------------------------------------------------------------
    // 2. parseAssignResponse — parses allocation response from Colab backend
    // ---------------------------------------------------------------------------

    @Test
    fun parseAssignResponse_parsesTopLevelSessionIdAndEndpointUrl() {
        val json = """
            {
              "session_id": "sess-xyz789",
              "endpoint_url": "https://colab.research.google.com/tun/m/sess-xyz789",
              "datalab_backend_url": "https://colab-back.internal/sess-xyz789"
            }
        """.trimIndent()

        val runtime = engine.parseAssignResponse(json)

        assertEquals("sess-xyz789", runtime.sessionId)
        assertEquals("https://colab.research.google.com/tun/m/sess-xyz789", runtime.endpointUrl)
        assertEquals("https://colab-back.internal/sess-xyz789", runtime.datalabBackendUrl)
    }

    @Test
    fun parseAssignResponse_parsesNestedAssignResponseObject() {
        val json = """
            {
              "assign_response": {
                "session_id": "sess-nested-001",
                "endpoint_url": "https://colab.research.google.com/tun/m/sess-nested-001",
                "datalab_backend_url": ""
              }
            }
        """.trimIndent()

        val runtime = engine.parseAssignResponse(json)
        assertEquals("sess-nested-001", runtime.sessionId)
        assertTrue(
            "Expected endpoint URL to contain session ID, got: ${runtime.endpointUrl}",
            runtime.endpointUrl.contains("sess-nested-001")
        )
    }

    @Test
    fun parseAssignResponse_fallsBackToColabBaseUrlWhenEndpointUrlMissing() {
        val json = """{"session_id":"sess-fallback"}"""
        val runtime = engine.parseAssignResponse(json)
        assertEquals("sess-fallback", runtime.sessionId)
        // Endpoint URL must be constructed from COLAB_BASE/tun/m/<session_id>
        assertTrue(
            "Expected endpoint URL to contain session ID, got: ${runtime.endpointUrl}",
            runtime.endpointUrl.contains("sess-fallback")
        )
        assertTrue(
            "Expected endpoint URL to reference colab.research.google.com, got: ${runtime.endpointUrl}",
            runtime.endpointUrl.contains("colab.research.google.com")
        )
    }

    @Test
    fun parseAssignResponse_returnsSafeDefaultsForMalformedBody() {
        val runtime = engine.parseAssignResponse("not-json-at-all")
        assertEquals("", runtime.sessionId)
        assertEquals("", runtime.endpointUrl)
        assertEquals("", runtime.datalabBackendUrl)
    }

    @Test
    fun parseAssignResponse_handlesXssiPrefixedBody() {
        val raw = ")]}'\n{\"session_id\":\"sess-xssi\",\"endpoint_url\":\"https://colab.research.google.com/tun/m/sess-xssi\",\"datalab_backend_url\":\"\"}"
        val stripped = engine.stripXssiPrefix(raw)
        val runtime = engine.parseAssignResponse(stripped, raw)
        assertEquals("sess-xssi", runtime.sessionId)
        assertEquals(raw, runtime.rawResponse)
    }

    @Test
    fun parseAssignResponse_isDeterministic() {
        val json = """{"session_id":"sess-det","endpoint_url":"https://colab.research.google.com/tun/m/sess-det","datalab_backend_url":""}"""
        val r1 = engine.parseAssignResponse(json)
        val r2 = engine.parseAssignResponse(json)
        assertEquals(r1.sessionId, r2.sessionId)
        assertEquals(r1.endpointUrl, r2.endpointUrl)
        assertEquals(r1.datalabBackendUrl, r2.datalabBackendUrl)
    }

    // ---------------------------------------------------------------------------
    // 3. parseCellExecutionResult — parses execute-endpoint responses
    // ---------------------------------------------------------------------------

    @Test
    fun parseCellExecutionResult_parsesSuccessWithStdout() {
        val json = """{"success":true,"stdout":"Tunnel online: https://abc.trycloudflare.com:22\n","stderr":""}"""
        val result = engine.parseCellExecutionResult(json, 200)
        assertTrue(result.success)
        assertTrue(result.stdout.contains("Tunnel online"))
        assertEquals("", result.stderr)
        assertEquals(200, result.httpStatus)
    }

    @Test
    fun parseCellExecutionResult_parsesFailureWithStderr() {
        val json = """{"success":false,"stdout":"","stderr":"ModuleNotFoundError: No module named 'tmux'"}"""
        val result = engine.parseCellExecutionResult(json, 200)
        assertFalse(result.success)
        assertTrue(result.stderr.contains("ModuleNotFoundError"))
    }

    @Test
    fun parseCellExecutionResult_treatsHttp200AsSuccessWhenJsonLacksSuccessField() {
        val json = """{"output":"hello world"}"""
        val result = engine.parseCellExecutionResult(json, 200)
        assertTrue(result.success)
    }

    @Test
    fun parseCellExecutionResult_treatsHttp500AsFailureWhenJsonLacksSuccessField() {
        val json = """{"error":"internal server error"}"""
        val result = engine.parseCellExecutionResult(json, 500)
        assertFalse(result.success)
        assertEquals(500, result.httpStatus)
    }

    @Test
    fun parseCellExecutionResult_handlesStdoutAsJsonArray() {
        val json = """{"success":true,"stdout":["line1","line2","line3"],"stderr":[]}"""
        val result = engine.parseCellExecutionResult(json, 200)
        assertTrue(result.success)
        assertTrue(result.stdout.contains("line1"))
        assertTrue(result.stdout.contains("line2"))
        assertTrue(result.stdout.contains("line3"))
    }

    @Test
    fun parseCellExecutionResult_handlesXssiPrefixedBody() {
        val raw = ")]}'\n{\"success\":true,\"stdout\":\"ok\",\"stderr\":\"\"}"
        val stripped = engine.stripXssiPrefix(raw)
        val result = engine.parseCellExecutionResult(stripped, 200)
        assertTrue(result.success)
        assertEquals("ok", result.stdout)
    }

    @Test
    fun parseCellExecutionResult_returnsMalformedBodyAsStdoutOnParseError() {
        // Should not throw; plain text becomes stdout
        val result = engine.parseCellExecutionResult("plain text output not JSON", 200)
        assertTrue(result.success) // HTTP 200 → success when body unparseable
    }

    @Test
    fun parseCellExecutionResult_bootstrapTunnelAnnouncement() {
        // Simulate what the real Colab bootstrap cell outputs
        val json = """
            {
              "success": true,
              "stdout": "Bootstrap complete.\nTunnel: https://my-tunnel.trycloudflare.com:22\nSSH ready on port 22.\n",
              "stderr": ""
            }
        """.trimIndent()
        val result = engine.parseCellExecutionResult(json, 200)
        assertTrue(result.success)
        assertTrue(result.stdout.contains("https://my-tunnel.trycloudflare.com:22"))
        assertTrue(result.stdout.contains("SSH ready"))
    }

    // ---------------------------------------------------------------------------
    // 4. parseRuntimeStatus — parses status-check responses
    // ---------------------------------------------------------------------------

    @Test
    fun parseRuntimeStatus_parsesRunningState() {
        val json = """{"state":"RUNNING"}"""
        val status = engine.parseRuntimeStatus(json, "sess-1", httpOk = true)
        assertTrue(status.isAlive)
        assertEquals("RUNNING", status.state)
        assertEquals("sess-1", status.sessionId)
    }

    @Test
    fun parseRuntimeStatus_parsesStoppedState() {
        val json = """{"state":"STOPPED"}"""
        val status = engine.parseRuntimeStatus(json, "sess-2", httpOk = false)
        assertFalse(status.isAlive)
        assertEquals("STOPPED", status.state)
    }

    @Test
    fun parseRuntimeStatus_parsesTerminatedState() {
        val json = """{"state":"TERMINATED"}"""
        val status = engine.parseRuntimeStatus(json, "sess-3", httpOk = true)
        // TERMINATED → not alive even if HTTP 200
        assertFalse(status.isAlive)
        assertEquals("TERMINATED", status.state)
    }

    @Test
    fun parseRuntimeStatus_defaultsToRunningWhenEmptyBodyAndHttp200() {
        val status = engine.parseRuntimeStatus("{}", "sess-4", httpOk = true)
        // Empty JSON → state defaults to "RUNNING" when httpOk=true
        assertTrue(status.isAlive)
    }

    @Test
    fun parseRuntimeStatus_defaultsToStoppedWhenHttp404() {
        val status = engine.parseRuntimeStatus("{}", "sess-5", httpOk = false)
        assertFalse(status.isAlive)
    }

    @Test
    fun parseRuntimeStatus_handlesMalformedJson() {
        val status = engine.parseRuntimeStatus("not-json", "sess-6", httpOk = true)
        // Should not throw; must still return a consistent status
        assertEquals("sess-6", status.sessionId)
        assertTrue(status.isAlive) // HTTP 200 → alive by default
    }

    // ---------------------------------------------------------------------------
    // 5. ColabAccelerator API values — must match Colab backend expectations
    // ---------------------------------------------------------------------------

    @Test
    fun colabAccelerator_apiValuesMatchColabBackendExpectations() {
        assertEquals("NONE", ColabAccelerator.NONE.apiValue)
        assertEquals("T4", ColabAccelerator.T4.apiValue)
        assertEquals("V100", ColabAccelerator.V100.apiValue)
        assertEquals("A100", ColabAccelerator.A100.apiValue)
    }

    @Test
    fun colabAccelerator_enumHasExactlyFourValues() {
        assertEquals(4, ColabAccelerator.values().size)
    }

    // ---------------------------------------------------------------------------
    // 6. ColabRuntime data integrity
    // ---------------------------------------------------------------------------

    @Test
    fun colabRuntime_endpointUrlTrimsCorrectlyForExecuteUrl() {
        val runtime = ColabRuntime(
            sessionId = "s1",
            endpointUrl = "https://colab.research.google.com/tun/m/s1/",
            datalabBackendUrl = ""
        )
        val executeUrl = "${runtime.endpointUrl.trimEnd('/')}/execute"
        assertEquals("https://colab.research.google.com/tun/m/s1/execute", executeUrl)
    }

    @Test
    fun colabRuntime_rawResponsePreservedFromAssignment() {
        val rawBody = ")]}'\n{\"session_id\":\"s-raw\"}"
        val runtime = engine.parseAssignResponse(engine.stripXssiPrefix(rawBody), rawBody)
        assertEquals(rawBody, runtime.rawResponse)
    }

    // ---------------------------------------------------------------------------
    // 7. Colab CLI compatibility & helpers
    // ---------------------------------------------------------------------------

    @Test
    fun uuidToWebSafeBase64_formats44CharacterStringWithUnderscoresAndDots() {
        val testUuid = UUID.fromString("12345678-1234-5678-1234-567812345678")
        val nbh = ColabApiEngine.uuidToWebSafeBase64(testUuid)
        assertEquals(44, nbh.length)
        assertFalse(nbh.contains("-"))
        assertTrue(nbh.startsWith("12345678_1234_5678_1234_567812345678"))
        assertTrue(nbh.endsWith("........"))
    }

    @Test
    fun extractBearerToken_extractsFromRawString() {
        val account = ColabAccount(
            email = "user@gmail.com",
            displayName = "User",
            tokenOrCookie = "ya29.raw_token_value",
            tier = "Free"
        )
        val token = ColabApiEngine.extractBearerToken(account)
        assertEquals("ya29.raw_token_value", token)
    }

    @Test
    fun extractBearerToken_extractsFromJsonBlob() {
        val jsonBlob = "{\"token\":\"ya29.json_blob_token\",\"account\":\"user@gmail.com\"}"
        val account = ColabAccount(
            email = "user@gmail.com",
            displayName = "User",
            tokenOrCookie = jsonBlob,
            tier = "Pro"
        )
        val token = ColabApiEngine.extractBearerToken(account)
        assertEquals("ya29.json_blob_token", token)
    }

    @Test
    fun formatScriptForExecution_prependsCellMagicForBashScript() {
        val bashScript = "#!/usr/bin/env bash\necho 'hello world'\n"
        val formatted = ColabApiEngine.formatScriptForExecution(bashScript)
        assertTrue(formatted.startsWith("%%bash\n"))
        assertTrue(formatted.contains("echo 'hello world'"))
    }

    @Test
    fun formatScriptForExecution_leavesPythonAndMagicUntouched() {
        val pythonScript = "import os\nprint(os.name)"
        val formatted = ColabApiEngine.formatScriptForExecution(pythonScript)
        assertEquals(pythonScript, formatted)

        val alreadyMagic = "%%bash\nls -la"
        assertEquals(alreadyMagic, ColabApiEngine.formatScriptForExecution(alreadyMagic))
    }

    @Test
    fun parseAssignResponse_parsesColabCliRuntimeProxyInfo() {
        val json = """
            {
              "endpoint": "m-s-cli-endpoint",
              "accelerator": "T4",
              "variant": 1,
              "runtimeProxyInfo": {
                "url": "https://colab.research.google.com/tun/m/m-s-cli-endpoint",
                "token": "proxy-token-xyz-123",
                "tokenExpiresInSeconds": 86400
              }
            }
        """.trimIndent()

        val runtime = engine.parseAssignResponse(json)
        assertEquals("m-s-cli-endpoint", runtime.sessionId)
        assertEquals("https://colab.research.google.com/tun/m/m-s-cli-endpoint", runtime.endpointUrl)
        assertEquals("proxy-token-xyz-123", runtime.proxyToken)
        assertEquals(ColabAccelerator.T4, runtime.accelerator)
    }

    @Test
    fun parseCellExecutionResult_parsesJupyterOutputsArray() {
        val jupyterOutput = """
            {
              "success": true,
              "outputs": [
                {
                  "output_type": "stream",
                  "name": "stdout",
                  "text": "Tunnel URL: https://abc.trycloudflare.com"
                },
                {
                  "output_type": "stream",
                  "name": "stderr",
                  "text": "warning: daemon started"
                }
              ]
            }
        """.trimIndent()

        val result = engine.parseCellExecutionResult(jupyterOutput, 200)
        assertTrue(result.success)
        assertEquals("Tunnel URL: https://abc.trycloudflare.com", result.stdout)
        assertEquals("warning: daemon started", result.stderr)
    }

    @Test
    fun hardwareAccelerator_mapsCorrectlyToColabAccelerator() {
        assertEquals(ColabAccelerator.NONE, HardwareAccelerator.NONE.toColabAccelerator())
        assertEquals(ColabAccelerator.T4, HardwareAccelerator.T4.toColabAccelerator())
        assertEquals(ColabAccelerator.V100, HardwareAccelerator.V100.toColabAccelerator())
        assertEquals(ColabAccelerator.A100, HardwareAccelerator.A100.toColabAccelerator())
    }

    @Test
    fun parseContentsList_parsesJupyterDirectoryAndFileEntries() {
        val json = """
            {
              "name": "content",
              "path": "content",
              "type": "directory",
              "content": [
                {
                  "name": "agent.py",
                  "path": "content/agent.py",
                  "type": "file",
                  "size": 3659,
                  "last_modified": "2026-11-14T05:38:00Z"
                },
                {
                  "name": "sample_data",
                  "path": "content/sample_data",
                  "type": "directory",
                  "size": null,
                  "last_modified": "2026-11-12T16:40:00Z"
                }
              ]
            }
        """.trimIndent()

        val files = engine.parseContentsList(json)
        assertEquals(2, files.size)

        // Directories sorted before files
        val dir = files[0]
        assertEquals("sample_data", dir.name)
        assertEquals("/content/sample_data", dir.path)
        assertTrue(dir.isDirectory)

        val file = files[1]
        assertEquals("agent.py", file.name)
        assertEquals("/content/agent.py", file.path)
        assertFalse(file.isDirectory)
        assertEquals(3659L, file.sizeBytes)
        assertEquals("2026-11-14T05:38:00Z", file.modifiedTime)
    }

    @Test
    fun parseCcuInfo_parsesRealColabCcuResponse() {
        val json = """
            )]}'
            {
              "currentBalance": 0,
              "consumptionRateHourly": 0.16,
              "assignmentsCount": 2,
              "eligibleGpus": ["T4"],
              "ineligibleGpus": ["H100", "G4", "A100", "L4"],
              "freeCcuQuotaInfo": {
                "remainingTokens": "5142",
                "nextRefillTimestampSec": 1794684556
              },
              "eligibleTpus": ["V5E1"],
              "ineligibleTpus": ["V6E1"]
            }
        """.trimIndent()

        val ccu = engine.parseCcuInfo(json)
        assertEquals(0.0, ccu.currentBalance, 0.001)
        assertEquals(0.16, ccu.consumptionRateHourly, 0.001)
        assertEquals(2, ccu.assignmentsCount)
        assertEquals(listOf("T4"), ccu.eligibleGpus)
        assertEquals(listOf("V5E1"), ccu.eligibleTpus)
        assertEquals("5142", ccu.remainingTokens)
        assertEquals(1794684556L, ccu.nextRefillTimestampSec)
    }
}
