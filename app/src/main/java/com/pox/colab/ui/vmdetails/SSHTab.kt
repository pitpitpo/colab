package com.pox.colab.ui.vmdetails

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.engine.ResilientSSHManager
import com.pox.colab.engine.SSHConnectionState
import com.pox.colab.model.ColabVM
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.VMMetrics
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.TerminalBackground
import com.pox.colab.theme.TerminalCyan
import com.pox.colab.theme.TerminalForeground
import com.pox.colab.theme.TerminalGreen
import com.pox.colab.theme.TerminalRed
import com.pox.colab.theme.TerminalYellow

@Composable
fun SSHTab(
    vm: ColabVM,
    onConfigureTunnel: () -> Unit,
    onMetricsUpdated: ((VMMetrics) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val sshManager = remember(vm.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        ResilientSSHManager(vm, scope, onMetricsUpdated)
    }

    val connectionState by sshManager.connectionState.collectAsState()
    val statusMessage by sshManager.statusMessage.collectAsState()
    val terminalLines by sshManager.terminalOutput.collectAsState()

    var inputCommand by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val (host, port) = sshManager.resolveHostAndPort()
    val hasTunnel = host.isNotBlank()

    // Auto-connect on enter or endpoint update
    LaunchedEffect(vm.id, host, port) {
        if (hasTunnel) {
            sshManager.connect()
        }
    }

    // Auto scroll to bottom
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
    ) {
        // Resilient SSH Status Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = when (connectionState) {
                            SSHConnectionState.CONNECTED -> TerminalGreen
                            SSHConnectionState.RECONNECTING -> ColabOrange
                            SSHConnectionState.CONNECTING -> TerminalYellow
                            else -> TerminalRed
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "${vm.sshConfig.resilientProtocol.label} • ${connectionState.name}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (connectionState) {
                                SSHConnectionState.CONNECTED -> TerminalGreen
                                SSHConnectionState.RECONNECTING -> ColabOrange
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = if (hasTunnel) "$host:$port" else "No tunnel host configured",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!hasTunnel) {
                        IconButton(
                            onClick = onConfigureTunnel,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cable,
                                contentDescription = "Configure Tunnel",
                                modifier = Modifier.size(18.dp),
                                tint = ColabOrange
                            )
                        }
                    }

                    IconButton(
                        onClick = { sshManager.forceReconnect() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Force Reconnect",
                            modifier = Modifier.size(18.dp),
                            tint = ColabOrange
                        )
                    }
                    IconButton(
                        onClick = { sshManager.clearScreen() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Screen",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Unconfigured tunnel prompt banner
        if (!hasTunnel) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cable,
                            contentDescription = null,
                            tint = ColabOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tunnel host required to connect terminal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Button(
                        onClick = onConfigureTunnel,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                    ) {
                        Text("Set Host", fontSize = 11.sp)
                    }
                }
            }
        }

        // Terminal Output Screen
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(terminalLines, key = { it.id }) { line ->
                    if (line.isSystemNotice) {
                        Text(
                            text = line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TerminalCyan,
                            lineHeight = 16.sp
                        )
                    } else {
                        Text(
                            text = line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TerminalForeground,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Virtual Accessory Keyboard Bar (ESC, TAB, CTRL+C, colab-cli, nvidia-smi, arrows)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccessoryKeyButton("ESC") { sshManager.sendSpecialKey("ESC") }
                AccessoryKeyButton("TAB") { sshManager.sendSpecialKey("TAB") }
                AccessoryKeyButton("CTRL+C", isAction = true) { sshManager.sendCtrlC() }
                AccessoryKeyButton("colab-cli") { sshManager.sendInput("colab-cli status\n") }
                AccessoryKeyButton("nvidia-smi") { sshManager.sendInput("nvidia-smi\n") }
                AccessoryKeyButton("UP") { sshManager.sendSpecialKey("UP") }
                AccessoryKeyButton("DOWN") { sshManager.sendSpecialKey("DOWN") }
                AccessoryKeyButton("|") { sshManager.sendInput("|") }
                AccessoryKeyButton("/") { sshManager.sendInput("/") }
                AccessoryKeyButton("~") { sshManager.sendInput("~") }
            }
        }

        // Command Input Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputCommand,
                    onValueChange = { inputCommand = it },
                    placeholder = {
                        Text(
                            text = if (hasTunnel) "Type command and press Send / Enter..." else "Configure tunnel above to send commands...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = hasTunnel,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputCommand.isNotBlank()) {
                                sshManager.sendInput("$inputCommand\n")
                                inputCommand = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        if (inputCommand.isNotBlank()) {
                            sshManager.sendInput("$inputCommand\n")
                            inputCommand = ""
                        }
                    },
                    enabled = inputCommand.isNotBlank() && hasTunnel
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (inputCommand.isNotBlank() && hasTunnel) ColabOrange else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessoryKeyButton(
    label: String,
    isAction: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (isAction) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.height(30.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (isAction) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
