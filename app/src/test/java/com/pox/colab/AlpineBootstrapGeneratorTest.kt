package com.pox.colab

import com.pox.colab.engine.AlpineBootstrapGenerator
import com.pox.colab.model.ColabVM
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.SSHSessionConfig
import com.pox.colab.model.TunnelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineBootstrapGeneratorTest {

    @Test
    fun generateBootstrapScript_containsEssentialComponents() {
        val vm = ColabVM(
            name = "Test-GPU-VM",
            accountId = "test-account",
            hardwareAccelerator = HardwareAccelerator.T4,
            tunnelProvider = TunnelProvider.BORE,
            sshConfig = SSHSessionConfig(
                password = "secret-pass-123",
                resilientProtocol = ResilientProtocol.TMUX_SSH
            )
        )

        val script = AlpineBootstrapGenerator.generateBootstrapScript(
            vm = vm,
            accountTokenOrCookie = "ya29.test_oauth_access_token_abc123"
        )

        // Verifies shell setup
        assertTrue(script.contains("set -e"))
        assertTrue(script.contains("secret-pass-123"))

        // Verifies Alpine rootfs setup
        assertTrue(script.contains("alpine-minirootfs"))
        assertTrue(script.contains("chroot /content/alpine_rootfs"))

        // Verifies Tunnel setup (Bore)
        assertTrue(script.contains("bore local 22"))

        // Verifies OAuth credentials setup
        assertTrue(script.contains("ya29.test_oauth_access_token_abc123"))
        assertTrue(script.contains("COLAB_AUTH_TOKEN"))

        // Verifies agent.py generation and execution
        assertTrue(script.contains("cat << 'AGENT_PY' > /content/agent.py"))
        assertTrue(script.contains("python3 /content/agent.py"))
        assertTrue(script.contains("uptime_seconds"))
        assertTrue(script.contains("gpu_usage_percent"))
    }

    @Test
    fun generateBootstrapScript_extractsTokenFromJsonBlob() {
        val jsonTokenBlob = """{"token":"ya29.extracted_token_from_json","expiry":"2026-12-01T12:00:00Z"}"""
        val vm = ColabVM(
            name = "Test-VM",
            accountId = "test-account",
            hardwareAccelerator = HardwareAccelerator.NONE,
            tunnelProvider = TunnelProvider.CLOUDFLARE,
            sshConfig = SSHSessionConfig(resilientProtocol = ResilientProtocol.TMUX_SSH)
        )

        val script = AlpineBootstrapGenerator.generateBootstrapScript(vm, jsonTokenBlob)

        // Script must extract the clean token, NOT dump the raw json document into access_token field
        assertTrue(script.contains("ya29.extracted_token_from_json"))
        assertFalse(script.contains("\"access_token\": \"{\\\"token\\\""))
    }
}
