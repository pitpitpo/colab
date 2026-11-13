package com.pox.colab.ui.vmdetails

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.data.VMRepository
import com.pox.colab.engine.TelemetryEngine
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.theme.statusWarningColor
import com.pox.colab.ui.home.StatusIndicatorDot
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun DashboardTab(
    vm: ColabVM,
    repository: VMRepository,
    onConfigureTunnel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val (host, port) = VMRepository.parseEndpoint(
        if (vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost") {
            "${vm.sshConfig.host}:${vm.sshConfig.port}"
        } else {
            vm.tunnelUrl ?: ""
        }
    )
    val hasTunnel = host.isNotBlank()

    // Real HTTP Telemetry Polling worker via OkHttp
    LaunchedEffect(vm.id, host, port, vm.status) {
        if (hasTunnel) {
            while (isActive) {
                val httpMetrics = TelemetryEngine.fetchHttpMetrics(host)
                if (httpMetrics != null) {
                    repository.updateMetrics(vm.id, httpMetrics)
                }
                delay(6000L)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Unified Instance Status & Connection Card
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Status & Power Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicatorDot(status = vm.status)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = vm.status.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (vm.status == VMStatus.RUNNING) statusSuccessColor() else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = if (vm.status == VMStatus.RUNNING) "Uptime: ${formatUptime(vm.metrics.uptimeSeconds)}" else "Offline",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Tunnel Connection Details
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
                                shape = RoundedCornerShape(10.dp),
                                color = if (hasTunnel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (hasTunnel) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (hasTunnel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (hasTunnel) "Tunnel Connected" else "No Tunnel Configured",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (hasTunnel) "$host:$port" else "Required for SSH & SFTP file browsing",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = if (hasTunnel) FontFamily.Monospace else FontFamily.Default,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onConfigureTunnel,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (hasTunnel) Icons.Default.Edit else Icons.Default.Cable,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (hasTunnel) "Edit" else "Configure", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Non-interactive metadata badges (48dp tall, scrollable so large font
                    // scales never clip; previously clickable chips with empty onClick).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetadataBadge(
                            icon = Icons.Default.Router,
                            text = "Provider: ${vm.tunnelProvider.label}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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

        // Section 2: Live Telemetry Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Resource Telemetry",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (hasTunnel) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val metrics = TelemetryEngine.fetchHttpMetrics(host)
                                if (metrics != null) {
                                    repository.updateMetrics(vm.id, metrics)
                                    Toast.makeText(context, "Telemetry updated via HTTP agent", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "HTTP endpoint unreachable (telemetry streamed via active SSH session)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh telemetry",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Telemetry Row 1: CPU & RAM
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // CPU Gauge
                TelemetryGaugeCard(
                    title = "CPU",
                    icon = Icons.Default.Speed,
                    iconTint = MaterialTheme.colorScheme.primary,
                    valueText = if (vm.status == VMStatus.RUNNING && vm.metrics.cpuUsagePercent >= 0f) "${vm.metrics.cpuUsagePercent}%" else "--",
                    progress = if (vm.status == VMStatus.RUNNING) vm.metrics.cpuUsagePercent / 100f else 0f,
                    progressColor = MaterialTheme.colorScheme.primary,
                    subtitleText = if (vm.status == VMStatus.RUNNING) "Load: ${vm.metrics.loadAverage}" else "Offline",
                    modifier = Modifier.weight(1f)
                )

                // RAM Gauge
                TelemetryGaugeCard(
                    title = "Memory",
                    icon = Icons.Default.Memory,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    valueText = if (vm.status == VMStatus.RUNNING) vm.metrics.formattedRam() else "--",
                    progress = if (vm.status == VMStatus.RUNNING) vm.metrics.ramUsagePercent / 100f else 0f,
                    progressColor = MaterialTheme.colorScheme.secondary,
                    subtitleText = if (vm.status == VMStatus.RUNNING && vm.metrics.ramTotalBytes > 0L) "${vm.metrics.ramUsagePercent.toInt()}% Allocated" else "Offline",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Telemetry Row 2: GPU & Disk
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // GPU Gauge
                TelemetryGaugeCard(
                    title = "GPU",
                    icon = Icons.Default.DeveloperBoard,
                    iconTint = statusSuccessColor(),
                    valueText = if (vm.status == VMStatus.RUNNING) (vm.metrics.gpuModel ?: "No GPU") else "--",
                    progress = if (vm.status == VMStatus.RUNNING) vm.metrics.gpuUsagePercent / 100f else 0f,
                    progressColor = statusSuccessColor(),
                    subtitleText = if (vm.status == VMStatus.RUNNING) vm.metrics.formattedGpuVram() else "Offline",
                    extraTag = if (vm.status == VMStatus.RUNNING && vm.metrics.gpuTemperatureCelsius > 0) "${vm.metrics.gpuTemperatureCelsius}°C" else null,
                    modifier = Modifier.weight(1f)
                )

                // Disk Gauge
                TelemetryGaugeCard(
                    title = "Disk (/)",
                    icon = Icons.Default.Storage,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    valueText = if (vm.status == VMStatus.RUNNING) vm.metrics.formattedDisk() else "--",
                    progress = if (vm.status == VMStatus.RUNNING) vm.metrics.diskUsagePercent / 100f else 0f,
                    progressColor = MaterialTheme.colorScheme.tertiary,
                    subtitleText = if (vm.status == VMStatus.RUNNING && vm.metrics.diskTotalBytes > 0L) "${vm.metrics.diskUsagePercent.toInt()}% Used" else "Offline",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Section 3: Alpine Container Setup & Script
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Alpine Container Bootstrap",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Run this script in a Google Colab notebook cell to spin up Alpine with Colab CLI & resilient SSH tunnel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Colab Bootstrap Script", vm.bootstrapScript)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Bootstrap script copied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Script", maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        FilledTonalButton(
                            onClick = {
                                if (vm.status == VMStatus.RUNNING) {
                                    repository.stopVM(vm.id)
                                } else {
                                    repository.spinUpAlpineVM(vm.id)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = if (vm.status == VMStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (vm.status == VMStatus.RUNNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (vm.status == VMStatus.RUNNING) "Mark Stopped" else "Mark Active",
                                maxLines = 1,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = vm.bootstrapScript.take(280) + "\n...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryGaugeCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    valueText: String,
    progress: Float,
    progressColor: androidx.compose.ui.graphics.Color,
    subtitleText: String,
    modifier: Modifier = Modifier,
    extraTag: String? = null
) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = iconTint.copy(alpha = 0.12f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = iconTint
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                extraTag?.let { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = statusWarningColor()
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(tag, style = MaterialTheme.typography.labelSmall, color = statusWarningColor(), fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = valueText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            Spacer(modifier = Modifier.height(8.dp))

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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
