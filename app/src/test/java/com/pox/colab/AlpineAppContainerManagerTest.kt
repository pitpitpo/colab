package com.pox.colab

import com.pox.colab.engine.AlpineAppContainerManager
import com.pox.colab.engine.CliExecutionResult
import com.pox.colab.engine.ColabAuthEngine
import com.pox.colab.engine.CpuArchitecture
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

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
    // 4. CPU Architecture & PRoot Runner Tests
    // ==========================================

    @Test
    fun cpuArchitecture_detectsAndMapsCorrectAlpineArch() {
        assertEquals("arm64-v8a", CpuArchitecture.ARM64.androidAbi)
        assertEquals("aarch64", CpuArchitecture.ARM64.alpineArch)

        assertEquals("x86_64", CpuArchitecture.X86_64.androidAbi)
        assertEquals("x86_64", CpuArchitecture.X86_64.alpineArch)

        assertEquals("armeabi-v7a", CpuArchitecture.ARMV7.androidAbi)
        assertEquals("armv7", CpuArchitecture.ARMV7.alpineArch)

        assertEquals("x86", CpuArchitecture.X86.androidAbi)
        assertEquals("x86", CpuArchitecture.X86.alpineArch)

        val detected = CpuArchitecture.detect()
        assertNotNull(detected)
        assertTrue(detected in CpuArchitecture.values())
    }

    @Test
    fun buildProotCommand_constructsCorrectPRootArguments() {
        val command = listOf("python3", "-m", "colab_cli.cli", "status")
        val prootCmd = manager.buildProotCommand(
            command = command,
            workingDir = "/instances/acc-test"
        )

        assertEquals("proot", prootCmd[0])
        assertEquals("-0", prootCmd[1])
        assertEquals("-r", prootCmd[2])
        assertEquals(manager.rootfsDir.absolutePath, prootCmd[3])
        assertEquals("-b", prootCmd[4])
        assertEquals("/dev", prootCmd[5])
        assertEquals("-b", prootCmd[6])
        assertEquals("/proc", prootCmd[7])
        assertEquals("-b", prootCmd[8])
        assertEquals("/sys", prootCmd[9])
        assertEquals("-w", prootCmd[10])
        assertEquals("/instances/acc-test", prootCmd[11])
        assertEquals("python3", prootCmd[12])
        assertEquals("-m", prootCmd[13])
        assertEquals("colab_cli.cli", prootCmd[14])
        assertEquals("status", prootCmd[15])
    }

    @Test
    fun getMinirootfsDownloadUrl_generatesAuthenticAlpineUrl() {
        val urlArm64 = manager.getMinirootfsDownloadUrl(CpuArchitecture.ARM64, version = "3.19.1")
        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            urlArm64
        )

        val urlX86 = manager.getMinirootfsDownloadUrl(CpuArchitecture.X86_64, version = "3.19.1")
        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-3.19.1-x86_64.tar.gz",
            urlX86
        )
    }

    @Test
    fun bootstrapAlpineEnvironment_initializesRootfsDirectoryStructureAndEliminatesEchoStubs() = runBlocking {
        val success = manager.bootstrapAlpineEnvironment()
        assertTrue(success)
        assertTrue(manager.isContainerRunning.value)

        // Verify rootfs directory structure under containerRoot/rootfs/
        assertTrue("rootfs directory must exist", manager.rootfsDir.exists())
        assertTrue("rootfs/bin must exist", manager.rootfsBinDir.exists())
        assertTrue("rootfs/etc must exist", manager.rootfsEtcDir.exists())
        assertTrue("rootfs/lib must exist", manager.rootfsLibDir.exists())
        assertTrue("rootfs/usr must exist", manager.rootfsUsrDir.exists())
        assertTrue("rootfs/tmp must exist", manager.rootfsTmpDir.exists())
        assertTrue("rootfs/instances must exist", manager.instancesDir.exists())
        assertTrue("alpine-release marker must exist", File(manager.rootfsEtcDir, "alpine-release").exists())
        assertTrue(manager.isRootfsInstalled())

        // Verify fake echo stubs have been completely eliminated
        assertFalse("fake apk stub must not exist", File(manager.binDir, "apk").exists())
        assertFalse("fake pip stub must not exist", File(manager.binDir, "pip").exists())
        assertFalse("fake colab-cli stub must not exist", File(manager.binDir, "colab-cli").exists())
        assertFalse("fake colab-multi-mgr stub must not exist", File(manager.binDir, "colab-multi-mgr").exists())
    }

    @Test
    fun extractTarGz_extractsFilesAndDirectoriesCorrectly() {
        // Construct a minimal in-memory tar.gz archive
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            // Entry 1: Directory "bin/"
            val dirHeader = ByteArray(512)
            "bin/".toByteArray(StandardCharsets.UTF_8).copyInto(dirHeader, 0)
            dirHeader[156] = '5'.code.toByte() // directory
            gzos.write(dirHeader)

            // Entry 2: File "bin/test.sh" with content "echo hello"
            val fileContent = "#!/bin/sh\necho hello\n".toByteArray(StandardCharsets.UTF_8)
            val fileHeader = ByteArray(512)
            "bin/test.sh".toByteArray(StandardCharsets.UTF_8).copyInto(fileHeader, 0)
            // Octal size in bytes 124..134
            val octalSize = String.format("%011o", fileContent.size)
            octalSize.toByteArray(StandardCharsets.UTF_8).copyInto(fileHeader, 124)
            fileHeader[156] = '0'.code.toByte() // regular file
            gzos.write(fileHeader)
            gzos.write(fileContent)
            // Padding to 512 bytes
            val pad = (512 - (fileContent.size % 512)) % 512
            if (pad > 0) gzos.write(ByteArray(pad))

            // Two 512-byte zero blocks ending archive
            gzos.write(ByteArray(1024))
        }

        val testTarGz = File(tempRoot, "test-archive.tar.gz")
        testTarGz.writeBytes(baos.toByteArray())

        val targetDir = File(tempRoot, "extracted_rootfs")
        val result = manager.extractTarGz(testTarGz, targetDir)
        assertTrue(result)

        val extractedFile = File(targetDir, "bin/test.sh")
        assertTrue(extractedFile.exists())
        assertEquals("#!/bin/sh\necho hello\n", extractedFile.readText())
        assertTrue(extractedFile.canExecute())
    }

    // ==========================================
    // 5. CLI Execution & Dispatch Tests
    // ==========================================

    @Test
    fun executeColabCli_exportsCorrectEnvironmentVariablesAndPRootCommand() = runBlocking {
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

        // Verify PRoot command structure
        assertNotNull(capturedCommand)
        assertEquals("proot", capturedCommand?.get(0))
        assertTrue(capturedCommand?.contains("-0") == true)
        assertTrue(capturedCommand?.contains("-r") == true)
        assertTrue(capturedCommand?.contains(manager.rootfsDir.absolutePath) == true)
        assertTrue(capturedCommand?.contains("-b") == true)
        assertTrue(capturedCommand?.contains("/dev") == true)
        assertTrue(capturedCommand?.contains("/proc") == true)
        assertTrue(capturedCommand?.contains("/sys") == true)
        assertTrue(capturedCommand?.contains("-w") == true)
        assertTrue(capturedCommand?.contains("/instances/$accountId") == true)
        assertTrue(capturedCommand?.contains("python3") == true)
        assertTrue(capturedCommand?.contains("colab_cli.cli") == true)
        assertTrue(capturedCommand?.contains("status") == true)
        assertTrue(capturedCommand?.contains("--json") == true)

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
            "PATH must contain rootfs bin directory",
            capturedEnv?.get("PATH")?.contains(manager.rootfsBinDir.absolutePath) == true
        )
        assertEquals(manager.rootfsDir.absolutePath, capturedEnv?.get("ALPINE_ROOT"))
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
        assertTrue(invokedCommand?.contains("status") == true)
        assertTrue(invokedCommand?.contains("python3") == true)
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
        assertTrue(invokedCommand?.contains("runtime") == true)
        assertTrue(invokedCommand?.contains("list") == true)
    }

    @Test
    fun executeColabCli_handlesProcessFailureGracefully() = runBlocking {
        manager.processExecutor = { _, _, _ ->
            CliExecutionResult(exitCode = 127, stdout = "", stderr = "proot: command not found")
        }

        val result = manager.executeColabCli("acc-fail", "unknown-cmd")
        assertEquals(127, result.exitCode)
        assertEquals("proot: command not found", result.stderr)
    }
}
