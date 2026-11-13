package com.pox.colab

import com.pox.colab.engine.AlpineAppContainerManager
import com.pox.colab.engine.CliExecutionResult
import com.pox.colab.engine.ColabAuthEngine
import com.pox.colab.model.ColabAccount
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AlpineAppContainerManagerTest {

    private lateinit var tempRoot: File
    private lateinit var manager: AlpineAppContainerManager

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("alpine_test_").toFile()
        manager = AlpineAppContainerManager.createForTesting(tempRoot, autoBootstrap = false)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
        AlpineAppContainerManager.resetInstanceForTesting()
    }

    // ==========================================
    // 1. Token Formatting Tests
    // ==========================================

    @Test
    fun formatAuthenticTokenJson_generatesStandardColabCliOAuthStructure() {
        val tokenJsonStr = manager.formatAuthenticTokenJson(
            token = "ya29.authentic_test_token_123",
            email = "user@example.com",
            refreshToken = "1//refresh_token_xyz"
        )

        val json = JSONObject(tokenJsonStr)
        assertEquals("ya29.authentic_test_token_123", json.getString("token"))
        assertEquals("1//refresh_token_xyz", json.getString("refresh_token"))
        assertEquals("https://oauth2.googleapis.com/token", json.getString("token_uri"))
        assertEquals(ColabAuthEngine.CLIENT_ID, json.getString("client_id"))
        assertEquals(ColabAuthEngine.CLIENT_SECRET, json.getString("client_secret"))
        assertEquals("googleapis.com", json.getString("universe_domain"))
        assertEquals("user@example.com", json.getString("account"))

        // Scopes verification
        val scopesArray = json.getJSONArray("scopes")
        val scopesList = (0 until scopesArray.length()).map { scopesArray.getString(it) }
        assertTrue(scopesList.contains("openid"))
        assertTrue(scopesList.contains("https://www.googleapis.com/auth/colaboratory"))

        // Authentic RFC3339 Expiry format verification (e.g. 2026-11-13T12:00:00Z)
        val expiry = json.getString("expiry")
        assertTrue(
            "Expiry must follow RFC3339 format, got: $expiry",
            expiry.matches(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"""))
        )
    }

    @Test
    fun createCliInstanceForAccount_extractsTokenFromRawString() = runBlocking {
        val account = ColabAccount(
            id = "acc-raw-token",
            email = "raw@example.com",
            displayName = "Raw User",
            tokenOrCookie = "ya29.raw_token_xyz",
            tier = "Free",
            isEnabled = true
        )

        val instancePath = manager.createCliInstanceForAccount(account)
        val accountDir = File(instancePath)
        assertTrue(accountDir.exists())

        // Verify credentials.json
        val credsFile = File(accountDir, "credentials.json")
        assertTrue(credsFile.exists())
        val credsJson = JSONObject(credsFile.readText())
        assertEquals("ya29.raw_token_xyz", credsJson.getString("access_token"))
        assertEquals("Bearer", credsJson.getString("token_type"))

        // Verify token.json
        val tokenFile = File(accountDir, "token.json")
        assertTrue(tokenFile.exists())
        val tokenJson = JSONObject(tokenFile.readText())
        assertEquals("ya29.raw_token_xyz", tokenJson.getString("token"))
        assertEquals("raw@example.com", tokenJson.getString("account"))
        assertTrue(tokenJson.getString("expiry").endsWith("Z"))
    }

    @Test
    fun createCliInstanceForAccount_extractsTokenFromJsonBlob() = runBlocking {
        val jsonTokenBlob = """
            {
                "token": "ya29.clean_extracted_token_abc",
                "refresh_token": "1//custom_refresh_token",
                "access_token": "ya29.clean_extracted_token_abc"
            }
        """.trimIndent()

        val account = ColabAccount(
            id = "acc-json-token",
            email = "json@example.com",
            displayName = "JSON User",
            tokenOrCookie = jsonTokenBlob,
            tier = "Pro",
            isEnabled = true
        )

        val instancePath = manager.createCliInstanceForAccount(account)
        val accountDir = File(instancePath)

        val credsFile = File(accountDir, "credentials.json")
        val credsJson = JSONObject(credsFile.readText())
        // Must extract pure token without serialized JSON braces
        assertEquals("ya29.clean_extracted_token_abc", credsJson.getString("access_token"))

        val tokenFile = File(accountDir, "token.json")
        val tokenJson = JSONObject(tokenFile.readText())
        assertEquals("ya29.clean_extracted_token_abc", tokenJson.getString("token"))
        assertEquals("1//custom_refresh_token", tokenJson.getString("refresh_token"))
    }

    @Test
    fun createCliInstanceForAccount_convertsNumericExpiryToRfc3339() = runBlocking {
        val oauthTokenWithNumericExpiry = """
            {
                "token": "ya29.test_oauth_token",
                "expiry": "1735689600000",
                "refresh_token": "1//refresh_xyz"
            }
        """.trimIndent()

        val account = ColabAccount(
            id = "acc-numeric-expiry",
            email = "expiry@example.com",
            displayName = "Expiry User",
            tokenOrCookie = "ya29.test_oauth_token",
            oauthToken = oauthTokenWithNumericExpiry,
            tier = "Pro",
            isEnabled = true
        )

        manager.createCliInstanceForAccount(account)
        val tokenFile = File(manager.instancesDir, "acc-numeric-expiry/token.json")
        val tokenJson = JSONObject(tokenFile.readText())

        val expiry = tokenJson.getString("expiry")
        assertTrue(
            "Numeric timestamp must be converted to RFC3339, got: $expiry",
            expiry.matches(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"""))
        )
    }

    // ==========================================
    // 2. Config Structure Tests
    // ==========================================

    @Test
    fun createCliInstanceForAccount_writesCorrectInstanceConfig() = runBlocking {
        val account = ColabAccount(
            id = "acc-config-test",
            email = "test@example.com",
            displayName = "Config Test",
            tokenOrCookie = "ya29.dummy_token",
            tier = "Free",
            isEnabled = true
        )

        manager.createCliInstanceForAccount(account)
        val configFile = File(manager.instancesDir, "acc-config-test/instance_config.json")
        assertTrue(configFile.exists())

        val configJson = JSONObject(configFile.readText())
        assertEquals("acc-config-test", configJson.getString("accountId"))
        assertEquals("test@example.com", configJson.getString("email"))
        assertEquals("Config Test", configJson.getString("displayName"))
        assertEquals("Free", configJson.getString("tier"))
        assertTrue(configJson.getBoolean("enabled"))
        assertTrue(configJson.has("registeredAt"))
    }

    @Test
    fun updateAccountStatus_updatesEnabledAndTierWithUpsert() = runBlocking {
        val accountId = "acc-upsert-test"

        // Update when config does not exist yet (upsert)
        manager.updateAccountStatus(accountId = accountId, isEnabled = true, tier = "Pro")
        val configFile = File(manager.instancesDir, "$accountId/instance_config.json")
        assertTrue(configFile.exists())

        var configJson = JSONObject(configFile.readText())
        assertEquals(accountId, configJson.getString("accountId"))
        assertEquals("Pro", configJson.getString("tier"))
        assertTrue(configJson.getBoolean("enabled"))
        assertTrue(configJson.has("updatedAt"))

        // Update existing config
        manager.updateAccountStatus(accountId = accountId, isEnabled = false, tier = "Free")
        configJson = JSONObject(configFile.readText())
        assertEquals("Free", configJson.getString("tier"))
        assertFalse(configJson.getBoolean("enabled"))
    }

    // ==========================================
    // 3. Directory Isolation Tests
    // ==========================================

    @Test
    fun createCliInstance_maintainsDirectoryIsolationBetweenAccounts() = runBlocking {
        val accountA = ColabAccount(
            id = "account-alpha",
            email = "alpha@example.com",
            displayName = "Alpha User",
            tokenOrCookie = "ya29.token_alpha",
            tier = "Pro",
            isEnabled = true
        )
        val accountB = ColabAccount(
            id = "account-beta",
            email = "beta@example.com",
            displayName = "Beta User",
            tokenOrCookie = "ya29.token_beta",
            tier = "Free",
            isEnabled = false
        )

        val pathA = manager.createCliInstanceForAccount(accountA)
        val pathB = manager.createCliInstanceForAccount(accountB)

        val dirA = File(pathA)
        val dirB = File(pathB)

        // Ensure distinct directories
        assertTrue(dirA.exists())
        assertTrue(dirB.exists())
        assertEquals(File(manager.instancesDir, "account-alpha").absolutePath, dirA.absolutePath)
        assertEquals(File(manager.instancesDir, "account-beta").absolutePath, dirB.absolutePath)

        // Verify independent content
        val tokenJsonA = JSONObject(File(dirA, "token.json").readText())
        val tokenJsonB = JSONObject(File(dirB, "token.json").readText())
        assertEquals("alpha@example.com", tokenJsonA.getString("account"))
        assertEquals("beta@example.com", tokenJsonB.getString("account"))
        assertEquals("ya29.token_alpha", tokenJsonA.getString("token"))
        assertEquals("ya29.token_beta", tokenJsonB.getString("token"))

        // Verify deleting Account A does not affect Account B
        manager.deleteCliInstanceForAccount(accountA.id)
        assertFalse(dirA.exists())
        assertTrue(dirB.exists())
        assertTrue(File(dirB, "token.json").exists())
    }

    // ==========================================
    // 4. Mock CLI Dispatch & CLI Status / Runtimes
    // ==========================================

    @Test
    fun executeColabCli_exportsCorrectEnvironmentVariablesAndWorkingDirectory() = runBlocking {
        val accountId = "acc-dispatch-test"
        var capturedCommand: List<String>? = null
        var capturedWorkingDir: File? = null
        var capturedEnv: Map<String, String>? = null

        manager.processExecutor = { command, workingDir, env ->
            capturedCommand = command
            capturedWorkingDir = workingDir
            capturedEnv = env
            CliExecutionResult(exitCode = 0, stdout = "mock success", stderr = "")
        }

        val result = manager.executeColabCli(accountId, "status", "--json")

        assertEquals(0, result.exitCode)
        assertEquals("mock success", result.stdout)
        assertEquals("", result.stderr)

        // Verify command
        assertNotNull(capturedCommand)
        assertEquals(listOf("colab-cli", "status", "--json"), capturedCommand)

        // Verify working directory
        assertNotNull(capturedWorkingDir)
        val expectedAccountDir = File(manager.instancesDir, accountId)
        assertEquals(expectedAccountDir.absolutePath, capturedWorkingDir?.absolutePath)
        assertTrue(expectedAccountDir.exists())

        // Verify exported environment variables
        assertNotNull(capturedEnv)
        assertEquals(expectedAccountDir.absolutePath, capturedEnv?.get("COLAB_CONFIG_DIR"))
        assertEquals(expectedAccountDir.absolutePath, capturedEnv?.get("HOME"))
        assertTrue(
            "PATH must contain bin directory",
            capturedEnv?.get("PATH")?.contains(manager.binDir.absolutePath) == true
        )
        assertEquals(manager.containerRoot.absolutePath, capturedEnv?.get("ALPINE_ROOT"))
    }

    @Test
    fun getCliStatus_dispatchesStatusCommand() = runBlocking {
        val accountId = "acc-status-test"
        var invokedCommand: List<String>? = null

        manager.processExecutor = { command, _, _ ->
            invokedCommand = command
            CliExecutionResult(exitCode = 0, stdout = """{"status":"idle"}""", stderr = "")
        }

        val result = manager.getCliStatus(accountId)
        assertEquals(0, result.exitCode)
        assertEquals("""{"status":"idle"}""", result.stdout)
        assertEquals(listOf("colab-cli", "status"), invokedCommand)
    }

    @Test
    fun listRuntimes_dispatchesRuntimeListCommand() = runBlocking {
        val accountId = "acc-runtime-test"
        var invokedCommand: List<String>? = null

        manager.processExecutor = { command, _, _ ->
            invokedCommand = command
            CliExecutionResult(exitCode = 0, stdout = """{"runtimes":["vm-1","vm-2"]}""", stderr = "")
        }

        val result = manager.listRuntimes(accountId)
        assertEquals(0, result.exitCode)
        assertEquals("""{"runtimes":["vm-1","vm-2"]}""", result.stdout)
        assertEquals(listOf("colab-cli", "runtime", "list"), invokedCommand)
    }

    @Test
    fun executeColabCli_realProcessExecution_executesMockScriptSuccessfully() = runBlocking {
        // Bootstrap environment creates bin/colab-cli wrapper
        manager.bootstrapAlpineEnvironment()
        assertTrue(File(manager.binDir, "colab-cli").exists())

        // Call without processExecutor test hook (runs real shell process execution)
        val result = manager.executeColabCli("real-exec-account", "status")
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("\"status\": \"idle\""))
        assertTrue(result.stdout.contains("real-exec-account"))
    }

    @Test
    fun executeColabCli_handlesProcessFailureGracefully() = runBlocking {
        // Override binDir or execute a failing mock to ensure error handling
        manager.processExecutor = { _, _, _ ->
            CliExecutionResult(exitCode = 127, stdout = "", stderr = "colab-cli: command not found")
        }

        val result = manager.executeColabCli("acc-fail", "unknown-cmd")
        assertEquals(127, result.exitCode)
        assertEquals("colab-cli: command not found", result.stderr)
    }
}
