package com.pox.colab.ui.vmdetails

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.TerminalBackground
import com.pox.colab.theme.TerminalCyan
import com.pox.colab.theme.TerminalForeground
import com.pox.colab.theme.TerminalGreen
import com.pox.colab.theme.TerminalRed
import com.pox.colab.theme.TerminalYellow

@Composable
fun SSHTab(
    vm: ColabVM
) {
    val scope = rememberCoroutineScope()
    val sshManager = remember(vm.id) {
        ResilientSSHManager(vm, scope)
    }

    val connectionState by sshManager.connectionState.collectAsState()
    val statusMessage by sshManager.statusMessage.collectAsState()
    val terminalLines by sshManager.terminalOutput.collectAsState()

    var inputCommand by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-connect on enter
    LaunchedEffect(vm.id) {
        sshManager.connect()
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
                            text = "${vm.sshConfig.resilientProtocol.name} • ${connectionState.name}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (connectionState) {
                                SSHConnectionState.CONNECTED -> TerminalGreen
                                SSHConnectionState.RECONNECTING -> ColabOrange
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = "Never-die Tmux session: '${vm.sshConfig.tmuxSessionName}'",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    val color = when {
                        line.isSystemNotice -> TerminalCyan
                        line.text.startsWith("alpine:~#") || line.text.startsWith("$") -> TerminalGreen
                        line.text.contains("error", ignoreCase = true) || line.text.contains("fatal", ignoreCase = true) -> TerminalRed
                        line.text.contains("warning", ignoreCase = true) -> TerminalYellow
                        else -> TerminalForeground
                    }
                    Text(
                        text = line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = color,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Virtual Accessory Keyboard Bar (ESC, TAB, CTRL, ALT, ^C, ^Z, Arrows)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AccessoryKey(label = "ESC") { sshManager.sendSpecialKey("ESC") }
            AccessoryKey(label = "TAB") { sshManager.sendSpecialKey("TAB") }
            AccessoryKey(label = "CTRL+C", isDanger = true) { sshManager.sendCtrlC() }
            AccessoryKey(label = "colab-cli") {
                inputCommand = "colab-cli status"
            }
            AccessoryKey(label = "nvidia-smi") {
                inputCommand = "nvidia-smi"
            }
            AccessoryKey(label = "UP") { sshManager.sendSpecialKey("UP") }
            AccessoryKey(label = "DOWN") { sshManager.sendSpecialKey("DOWN") }
            AccessoryKey(label = "|") { inputCommand += "|" }
            AccessoryKey(label = "/") { inputCommand += "/" }
            AccessoryKey(label = "~") { inputCommand += "~" }
        }

        // Command Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                placeholder = { Text("Enter shell command...", color = Color.Gray) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TerminalForeground
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputCommand.isNotBlank()) {
                            sshManager.sendInput(inputCommand + "\n")
                            inputCommand = ""
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ColabOrange,
                    unfocusedBorderColor = Color(0xFF30363D),
                    focusedContainerColor = TerminalBackground,
                    unfocusedContainerColor = TerminalBackground
                ),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = {
                    if (inputCommand.isNotBlank()) {
                        sshManager.sendInput(inputCommand + "\n")
                        inputCommand = ""
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ColabOrange)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun AccessoryKey(
    label: String,
    isDanger: Boolean = false,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isDanger) TerminalRed.copy(alpha = 0.25f) else Color(0xFF21262D),
            contentColor = if (isDanger) TerminalRed else TerminalForeground
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
