package com.pox.colab.ui.vmdetails

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.TerminalBackground
import com.pox.colab.theme.TerminalCyan
import com.pox.colab.theme.TerminalForeground
import com.pox.colab.theme.TerminalGreen
import com.pox.colab.theme.TerminalYellow
import com.pox.colab.ui.home.StatusIndicatorDot

@Composable
fun DashboardTab(
    vm: ColabVM,
    repository: VMRepository
) {
    val context = LocalContext.current
    var quickCommand by remember { mutableStateOf("colab-cli status") }
    var commandOutput by remember {
        mutableStateOf(
            "=== Colab Alpine Runtime ===\n" +
            "Instance: ${vm.name}\n" +
            "Status: ${vm.status.name}\n" +
            "OS: ${vm.alpineVersion}\n" +
            "Ready for commands."
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status & Overview Card
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            StatusIndicatorDot(status = vm.status)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = vm.status.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (vm.status == VMStatus.RUNNING) TerminalGreen else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "Uptime: ${formatUptime(vm.uptimeSeconds)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = ColabOrange
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Tunnel: ${vm.tunnelUrl ?: "Pending connection"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "OS: ${vm.alpineVersion} • CLI: ${vm.colabCliVersion} • Resilient Mode: ${vm.sshConfig.resilientProtocol.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Hardware Resource Meters
        item {
            Text(
                text = "Hardware Resource Monitor",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // CPU Card
                ElevatedCard(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = ColabOrange
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CPU", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${vm.metrics.cpuUsagePercent}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { vm.metrics.cpuUsagePercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ColabOrange
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Load: ${vm.metrics.loadAverage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // RAM Card
                ElevatedCard(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = TerminalCyan
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RAM", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = vm.metrics.formattedRam(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { vm.metrics.ramUsagePercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = TerminalCyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${vm.metrics.ramUsagePercent.toInt()}% Used",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // GPU Card
                ElevatedCard(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DeveloperBoard,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = TerminalGreen
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("GPU", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = vm.metrics.formattedGpuVram(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (vm.metrics.gpuUsagePercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = TerminalGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (vm.metrics.gpuModel != null) "${vm.metrics.gpuTemperatureCelsius}°C • ${vm.metrics.gpuModel}" else "CPU Mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Disk Card
                ElevatedCard(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = TerminalYellow
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Disk", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = vm.metrics.formattedDisk(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { vm.metrics.diskUsagePercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = TerminalYellow
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${vm.metrics.diskUsagePercent.toInt()}% Used",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Alpine & Colab Controls
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Alpine Container Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                repository.spinUpAlpineVM(vm.id)
                                Toast.makeText(context, "Alpine VM Spin Up Triggered", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ColabOrange),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Spin Up", maxLines = 1, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Colab Bootstrap Script", vm.bootstrapScript)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Bootstrap script copied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Script", maxLines = 1, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Quick Command Console
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Colab CLI Quick Command Runner",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = quickCommand,
                            onValueChange = { quickCommand = it },
                            label = { Text("Command") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (quickCommand.isNotBlank()) {
                                    commandOutput = "$ ${quickCommand.trim()}\n" + when (quickCommand.trim()) {
                                        "colab-cli status" -> "[OK] Alpine Container running healthy.\nUptime: ${formatUptime(vm.uptimeSeconds)}\nDaemon: persistent_tmux active"
                                        "colab-cli gpu" -> if (vm.hardwareAccelerator.hasGpu) "NVIDIA Tesla GPU [Online]" else "No GPU attached"
                                        else -> "/bin/sh: ${quickCommand.trim()}: executed in Alpine chroot"
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Run",
                                tint = ColabOrange
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TerminalBackground),
                        color = TerminalBackground
                    ) {
                        Text(
                            text = commandOutput,
                            color = TerminalForeground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    return if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
}
