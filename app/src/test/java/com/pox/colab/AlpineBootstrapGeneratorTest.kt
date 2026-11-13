package com.pox.colab

import com.pox.colab.engine.AlpineBootstrapGenerator
import com.pox.colab.engine.ColabBootstrapGenerator
import com.pox.colab.model.ColabVM
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.SSHSessionConfig
import com.pox.colab.model.TunnelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineBootstrapGeneratorTest {

    @Test
    fun generateBootstrapScript_configuresNativeUbuntuEnvironment() {
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
        val colabScript = ColabBootstrapGenerator.generateBootstrapScript(
            vm = vm,
            accountTokenOrCookie = "ya29.test_oauth_access_token_abc123"
        )

        // Verifies backwards compatibility delegation
        assertEquals(colabScript, script)

        // Verifies shell setup
        assertTrue(script.contains("set -e"))
        assertTrue(script.contains("secret-pass-123"))

        // Verifies Native Ubuntu APT packages (NO Alpine)
        assertTrue(script.contains("apt-get update"))
        assertTrue(script.contains("apt-get install"))
        assertTrue(script.contains("openssh-server"))
        assertTrue(script.contains("tmux"))
        assertTrue(script.contains("curl"))
        assertTrue(script.contains("git"))
        assertTrue(script.contains("rsync"))
        assertTrue(script.contains("python3"))

        // Verifies SSH configuration
        assertTrue(script.contains("/etc/ssh/sshd_config"))
        assertTrue(script.contains("PermitRootLogin"))
        assertTrue(script.contains("PasswordAuthentication"))
        assertTrue(script.contains("ClientAliveInterval"))
        assertTrue(script.contains("/usr/sbin/sshd"))

        // Verifies persistent tmux session
        assertTrue(script.contains("tmux new-session"))
        assertTrue(script.contains("colab_session"))

        // Verifies Tunnel setup (Bore) & Autonomous Discovery Broadcast
        assertTrue(script.contains("bore local 22"))
        assertTrue(script.contains("announce_endpoint"))
        assertTrue(script.contains("DISCOVERY_URL"))
        assertTrue(script.contains("colab_vm_"))
        assertTrue(script.contains("/tmp/colab_tunnel_endpoint.txt"))

        // Verifies host credentials setup (in ~/.colab/credentials.json, NOT in alpine chroot)
        assertTrue(script.contains("~/.colab/credentials.json"))
        assertTrue(script.contains("ya29.test_oauth_access_token_abc123"))
        assertTrue(script.contains("COLAB_AUTH_TOKEN"))

        // Verifies agent.py telemetry daemon generation and direct file output
        assertTrue(script.contains("cat << 'AGENT_PY' > /content/agent.py"))
        assertTrue(script.contains("python3 /content/agent.py"))
        assertTrue(script.contains("/tmp/colab_metrics.json"))
        assertTrue(script.contains("uptime_seconds"))
        assertTrue(script.contains("gpu_usage_percent"))

        // CRITICAL: Verifies that NO Alpine minirootfs, tar.gz, apk, or chroot exist in the script!
        assertFalse(script.contains("alpine-minirootfs"))
        assertFalse(script.contains("alpine.tar.gz"))
        assertFalse(script.contains("alpine_rootfs"))
        assertFalse(script.contains("chroot"))
        assertFalse(script.contains("apk add"))
        assertFalse(script.contains("apk update"))
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

        // Script must extract the clean token and write it directly to ~/.colab/credentials.json
        assertTrue(script.contains("ya29.extracted_token_from_json"))
        assertFalse(script.contains("\"access_token\": \"{\\\"token\\\""))
        assertTrue(script.contains("~/.colab/credentials.json"))
        assertFalse(script.contains("chroot"))
    }

    @Test
    fun generateBootstrapScript_tunnelProviders() {
        val baseVM = ColabVM(
            name = "Tunnel-VM",
            accountId = "acc-1",
            hardwareAccelerator = HardwareAccelerator.NONE,
            tunnelProvider = TunnelProvider.CLOUDFLARE
        )

        val cloudflareScript = ColabBootstrapGenerator.generateBootstrapScript(baseVM)
        assertTrue(cloudflareScript.contains("cloudflared"))
        assertTrue(cloudflareScript.contains("trycloudflare"))
        assertTrue(cloudflareScript.contains("trycloudflare.com"))

        val ngrokScript = ColabBootstrapGenerator.generateBootstrapScript(baseVM.copy(tunnelProvider = TunnelProvider.NGROK))
        assertTrue(ngrokScript.contains("ngrok tcp 22"))

        val customScript = ColabBootstrapGenerator.generateBootstrapScript(baseVM.copy(tunnelProvider = TunnelProvider.CUSTOM))
        assertTrue(customScript.contains("custom tunnel"))
    }
}
