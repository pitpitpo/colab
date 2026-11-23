package com.pox.colab.ui.vmdetails

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pox.colab.data.VMRepository
import com.pox.colab.engine.ColabApiEngine
import com.pox.colab.engine.TelemetryEngine
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.MonoFontFamily
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.theme.statusWarningColor
import com.pox.colab.theme.vmStatusColor
import com.pox.colab.theme.vmStatusLabel
import com.pox.colab.ui.component.expressive.ExpressiveSectionHeader
import com.pox.colab.ui.component.expressive.ExpressiveShapes
import com.pox.colab.ui.component.expressive.ExpressiveWavyProgressBar
import com.pox.colab.ui.home.StatusIndicatorDot
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun DashboardTab(
    vm: ColabVM,
    repository: VMRepository,
    onConfigureTunnel: () -> Unit,
    onOpenConsole: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val activeEndpoint = remember(vm.tunnelUrl, vm.sshConfig.host, vm.sshConfig.port) {
        if (VMRepository.isValidTunnelEndpoint(vm.tunnelUrl)) {
            vm.tunnelUrl ?: ""
        } else if (vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost" && VMRepository.isValidTunnelEndpoint(vm.sshConfig.host)) {
            "${vm.sshConfig.host}:${vm.sshConfig.port}"
        } else {
            ""
        }
    }
    val (host, port) = remember(activeEndpoint) { VMRepository.parseEndpoint(activeEndpoint) }
    val hasTunnel = remember(activeEndpoint) { VMRepository.isValidTunnelEndpoint(activeEndpoint) }

    LaunchedEffect(vm.id, hasTunnel, vm.status) {
        if (!hasTunnel && (vm.status == VMStatus.STARTING || vm.status == VMStatus.RUNNING || vm.status == VMStatus.RECONNECTING)) {
            repository.startTunnelDiscovery(vm.id)
        }
    }

    val shouldPollTelemetry = hasTunnel ||
        vm.status == VMStatus.RUNNING ||
        vm.status == VMStatus.STARTING ||
        vm.status == VMStatus.RECONNECTING

    LaunchedEffect(vm.id, host, port, shouldPollTelemetry) {
        if (!shouldPollTelemetry) return@LaunchedEffect
        var consecutiveMisses = 0
        var lastKeepAliveMs = 0L
        while (isActive) {
            var gotMetrics = false
            val runtime = repository.ensureFreshRuntime(vm.id)
            if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastKeepAliveMs >= 60_000L) {
                    lastKeepAliveMs = nowMs
                    try {
                        repository.sendKeepAlive(vm.id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
                val curVm = repository.getVMById(vm.id)
                if (curVm != null && !VMRepository.isValidTunnelEndpoint(curVm.tunnelUrl)) {
                    try {
                        val tunnelRes = ColabApiEngine.getInstance().readFile(runtime, "tmp/colab_tunnel_endpoint.txt")
                        val tunnelEp = tunnelRes.getOrNull()?.trim()
                        if (VMRepository.isValidTunnelEndpoint(tunnelEp)) {
                            repository.updateTunnelEndpoint(vm.id, tunnelEp!!)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }

                val metricsResult = ColabApiEngine.getInstance().fetchMetrics(runtime)
                val metrics = metricsResult.getOrNull()
                if (metrics != null && metrics.timestamp > 0L) {
                    gotMetrics = true
                    repository.updateMetrics(vm.id, metrics)
                    val latestVm = repository.getVMById(vm.id)
                    if (VMRepository.isValidTunnelEndpoint(metrics.tunnelEndpoint) && metrics.tunnelEndpoint != latestVm?.tunnelUrl) {
                        repository.updateTunnelEndpoint(vm.id, metrics.tunnelEndpoint!!)
                    }
                }
            } else if (hasTunnel) {
                val httpMetrics = TelemetryEngine.fetchHttpMetrics(host)
                if (httpMetrics != null) {
                    gotMetrics = true
                    repository.updateMetrics(vm.id, httpMetrics)
                }
            }
            if (gotMetrics) {
                consecutiveMisses = 0
                delay(3000L)
            } else {
                consecutiveMisses = (consecutiveMisses + 1).coerceAtMost(4)
                delay(if (consecutiveMisses >= 2) 8000L else 3500L)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ExpressiveShapes.groupedItem(index = 0, count = 2, outer = 28.dp, inner = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = vmStatusColor(vm.status).copy(alpha = 0.16f)
                            ) {
                                StatusIndicatorDot(
                                    status = vm.status,
                                    showLabel = true,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = if (vm.status == VMStatus.RUNNING) {
                                        "Uptime ${formatUptime(vm.metrics.uptimeSeconds)}"
                                    } else {
                                        vmStatusLabel(vm.status)
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ExpressiveShapes.groupedItem(index = 1, count = 2, outer = 28.dp, inner = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = ExpressiveShapes.clover(),
                                        color = if (hasTunnel) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (hasTunnel) Icons.Default.CheckCircle else Icons.Default.Sync,
                                                contentDescription = null,
                                                tint = if (hasTunnel) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                },
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            text = when {
                                                hasTunnel -> "Tunnel Connected"
                                                vm.status == VMStatus.STARTING -> "Provisioning Tunnel"
                                                vm.status == VMStatus.ERROR -> "Provisioning Error"
                                                else -> "Auto-Configuring Tunnel"
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = when {
                                                hasTunnel -> "$host:$port"
                                                vm.status == VMStatus.STARTING -> "Allocating runtime & establishing tunnel..."
                                                vm.status == VMStatus.ERROR -> "Failed to establish tunnel. Tap retry below."
                                                else -> "Ready to launch instance."
                                            },
                                            style = if (hasTunnel) {
                                                MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily)
                                            } else {
                                                MaterialTheme.typography.bodySmall
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                FilledTonalButton(
                                    onClick = onConfigureTunnel,
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                                ) {
                                    Icon(
                                        imageVector = if (hasTunnel) Icons.Default.Edit else Icons.Default.Cable,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (hasTunnel) "Edit" else "Override",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MetadataBadge(
                                    icon = Icons.Default.Router,
                                    text = "Provider: ${vm.tunnelProvider.label}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                MetadataBadge(
                                    icon = Icons.Default.Security,
                                    text = vm.sshConfig.resilientProtocol.label,
                                    tint = statusSuccessColor()
                                )
                            }
                        }
                    }
                }
            }

            if (vm.status == VMStatus.STARTING) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Provisioning Colab Instance...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Allocating Google Colab compute node, bootstrapping environment, and connecting tunnel gateway.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            ExpressiveWavyProgressBar(
                                progress = null,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }

            if (vm.status == VMStatus.ERROR) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Provisioning Failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Unable to connect or start the Google Colab runtime. Verify account authentication or retry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = { repository.spinUpVM(vm.id) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = CircleShape
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry Provisioning", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                ExpressiveSectionHeader(
                    title = "Resource Telemetry",
                    subtitle = "Live CPU, memory, GPU & storage gauges",
                    trailing = if (hasTunnel || vm.status == VMStatus.RUNNING) {
                        {
                            FilledTonalIconButton(
                                onClick = {
                                    scope.launch {
                                        val runtime = repository.ensureFreshRuntime(vm.id)
                                        val apiMetrics = if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                                            ColabApiEngine.getInstance().fetchMetrics(runtime).getOrNull()
                                                ?.takeIf { it.timestamp > 0L }
                                        } else null
                                        val metrics = apiMetrics ?: if (hasTunnel) {
                                            TelemetryEngine.fetchHttpMetrics(host)
                                        } else null
                                        if (metrics != null) {
                                            repository.updateMetrics(vm.id, metrics)
                                            val source = if (apiMetrics != null) "Colab API" else "HTTP agent"
                                            Toast.makeText(context, "Telemetry updated via $source", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Telemetry endpoint unreachable right now", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier.testTag("dashboard_refresh_telemetry_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh telemetry",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else null
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TelemetryGaugeCard(
                        title = "CPU",
                        icon = Icons.Default.Speed,
                        badgeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        badgeIconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        valueText = if (vm.status == VMStatus.RUNNING && vm.metrics.cpuUsagePercent >= 0f) {
                            "${vm.metrics.cpuUsagePercent.toInt()}%"
                        } else {
                            "--"
                        },
                        progress = if (vm.status == VMStatus.RUNNING) vm.metrics.cpuUsagePercent / 100f else 0f,
                        progressColor = MaterialTheme.colorScheme.primary,
                        subtitleText = if (vm.status == VMStatus.RUNNING) "Load: ${vm.metrics.loadAverage}" else "Offline",
                        modifier = Modifier.weight(1f)
                    )

                    TelemetryGaugeCard(
                        title = "Memory",
                        icon = Icons.Default.Memory,
                        badgeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        badgeIconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        valueText = if (vm.status == VMStatus.RUNNING) vm.metrics.formattedRam() else "--",
                        progress = if (vm.status == VMStatus.RUNNING) vm.metrics.ramUsagePercent / 100f else 0f,
                        progressColor = MaterialTheme.colorScheme.secondary,
                        subtitleText = if (vm.status == VMStatus.RUNNING && vm.metrics.ramTotalBytes > 0L) {
                            "${vm.metrics.ramUsagePercent.toInt()}% Allocated"
                        } else {
                            "Offline"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TelemetryGaugeCard(
                        title = "GPU",
                        icon = Icons.Default.DeveloperBoard,
                        badgeContainerColor = statusSuccessColor().copy(alpha = 0.18f),
                        badgeIconTint = statusSuccessColor(),
                        valueText = if (vm.status == VMStatus.RUNNING) (vm.metrics.gpuModel ?: "No GPU") else "--",
                        progress = if (vm.status == VMStatus.RUNNING) vm.metrics.gpuUsagePercent / 100f else 0f,
                        progressColor = statusSuccessColor(),
                        subtitleText = if (vm.status == VMStatus.RUNNING) vm.metrics.formattedGpuVram() else "Offline",
                        extraTag = if (vm.status == VMStatus.RUNNING && vm.metrics.gpuTemperatureCelsius > 0) {
                            "${vm.metrics.gpuTemperatureCelsius}°C"
                        } else null,
                        modifier = Modifier.weight(1f)
                    )

                    TelemetryGaugeCard(
                        title = "Disk (/)",
                        icon = Icons.Default.Storage,
                        badgeContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        badgeIconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                        valueText = if (vm.status == VMStatus.RUNNING) vm.metrics.formattedDisk() else "--",
                        progress = if (vm.status == VMStatus.RUNNING) vm.metrics.diskUsagePercent / 100f else 0f,
                        progressColor = MaterialTheme.colorScheme.tertiary,
                        subtitleText = if (vm.status == VMStatus.RUNNING && vm.metrics.diskTotalBytes > 0L) {
                            "${vm.metrics.diskUsagePercent.toInt()}% Used"
                        } else {
                            "Offline"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text(
                            text = "Runtime Controls",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Managed automatically by the Colab engine — provision, connect, and control your runtime right inside the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val isRunning = vm.status == VMStatus.RUNNING
                            Button(
                                onClick = {
                                    if (isRunning) {
                                        repository.stopVM(vm.id)
                                    } else {
                                        repository.spinUpVM(vm.id)
                                    }
                                },
                                enabled = vm.status != VMStatus.STARTING,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dashboard_runtime_power_button"),
                                shape = ExpressiveShapes.connectedStart,
                                colors = if (isRunning) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (vm.status) {
                                        VMStatus.RUNNING -> "Stop Runtime"
                                        VMStatus.STARTING -> "Starting..."
                                        else -> "Start Runtime"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            FilledTonalButton(
                                onClick = onOpenConsole,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dashboard_open_console_button"),
                                shape = ExpressiveShapes.connectedEnd,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Open Console",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }

                        if (vm.status == VMStatus.RUNNING) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            val res = repository.interruptKernel(vm.id)
                                            val msg = if (res.isSuccess) "Jupyter kernel interrupted" else "Interrupt failed: ${res.exceptionOrNull()?.message}"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                                    modifier = Modifier.testTag("dashboard_interrupt_kernel_button")
                                ) {
                                    Icon(Icons.Default.PauseCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Interrupt Kernel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }

                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            val res = repository.restartKernel(vm.id)
                                            val msg = if (res.isSuccess) "Jupyter kernel restarted" else "Restart failed: ${res.exceptionOrNull()?.message}"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                                    modifier = Modifier.testTag("dashboard_restart_kernel_button")
                                ) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Restart Kernel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }

                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            val res = repository.checkRuntimeHealth(vm.id)
                                            val msg = res.fold(
                                                onSuccess = { st -> "Runtime ${st.state} • ${st.kernels} kernel(s)" },
                                                onFailure = { err -> "Health check failed: ${err.message}" }
                                            )
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                                    modifier = Modifier.testTag("dashboard_health_check_button")
                                ) {
                                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Health Check", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryGaugeCard(
    title: String,
    icon: ImageVector,
    badgeContainerColor: Color,
    badgeIconTint: Color,
    valueText: String,
    progress: Float,
    progressColor: Color,
    subtitleText: String,
    modifier: Modifier = Modifier,
    extraTag: String? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(),
        label = "telemetryProgress"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = ExpressiveShapes.clover(),
                        color = badgeContainerColor,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = badgeIconTint
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                extraTag?.let { tag ->
                    Surface(
                        shape = CircleShape,
                        color = statusWarningColor().copy(alpha = 0.16f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Thermostat,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = statusWarningColor()
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = statusWarningColor(),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = valueText,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MonoFontFamily),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    return if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
}

@Composable
private fun MetadataBadge(
    icon: ImageVector,
    text: String,
    tint: Color
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = tint
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
