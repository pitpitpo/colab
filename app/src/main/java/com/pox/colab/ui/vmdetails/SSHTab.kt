package com.pox.colab.ui.vmdetails

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.pox.colab.data.AppPreferences
import com.pox.colab.engine.ResilientSSHManager
import com.pox.colab.engine.SSHConnectionState
import com.pox.colab.model.ColabVM
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.VMMetrics
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.TerminalCyan
import com.pox.colab.theme.TerminalForeground
import com.pox.colab.theme.TerminalGreen
import com.pox.colab.theme.TerminalRed
import com.pox.colab.theme.TerminalYellow
import com.pox.colab.theme.resolveConsoleBackground

data class ConsoleTabSession(
    val id: String,
    val title: String,
    val manager: ResilientSSHManager
)

@Composable
fun SSHTab(
    vm: ColabVM,
    preferences: AppPreferences,
    onConfigureTunnel: () -> Unit,
    onMetricsUpdated: ((VMMetrics) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var sessionCounter by remember { mutableIntStateOf(1) }

    // Multi-tab session state
    val sessions = remember(vm.id) {
        mutableStateListOf<ConsoleTabSession>().apply {
            add(
                ConsoleTabSession(
                    id = "session_1",
                    title = "Session 1",
                    manager = ResilientSSHManager(
                        vm = vm,
                        scope = scope,
                        onMetricsUpdated = onMetricsUpdated,
                        tmuxSessionOverride = vm.sshConfig.tmuxSessionName
                    )
                )
            )
        }
    }

    var activeSessionId by remember { mutableStateOf(sessions.firstOrNull()?.id ?: "") }
    val currentSession = sessions.find { it.id == activeSessionId } ?: sessions.firstOrNull()

    // Retrieve active terminal background color
    val consoleBgColor = resolveConsoleBackground(preferences.getConsoleBgForVM(vm.id))

    // Handle auto-connection for initial session
    LaunchedEffect(vm.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        sessions.forEach { s ->
            val (host, _) = s.manager.resolveHostAndPort()
            if (host.isNotBlank() && s.manager.connectionState.value == SSHConnectionState.DISCONNECTED) {
                s.manager.connect()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(consoleBgColor)
    ) {
        // Multi-Tab Session Strip (Material 3 Expressive pills + Add button)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sessions.forEach { session ->
                    val isSelected = session.id == activeSessionId
                    val state by session.manager.connectionState.collectAsState()

                    val dotColor = when (state) {
                        SSHConnectionState.CONNECTED -> TerminalGreen
                        SSHConnectionState.RECONNECTING -> ColabOrange
                        SSHConnectionState.CONNECTING -> TerminalYellow
                        else -> TerminalRed
                    }

                    Surface(
                        onClick = { activeSessionId = session.id },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = if (sessions.size > 1) 4.dp else 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(dotColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = session.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Close session icon button (only available if multiple tabs exist)
                            if (sessions.size > 1) {
                                Spacer(modifier = Modifier.width(2.dp))
                                IconButton(
                                    onClick = {
                                        session.manager.disconnect()
                                        val idx = sessions.indexOf(session)
                                        sessions.remove(session)
                                        if (activeSessionId == session.id) {
                                            val nextIdx = (idx - 1).coerceAtLeast(0)
                                            activeSessionId = sessions[nextIdx].id
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close ${session.title}",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Add New Terminal Session (+) Button
                Surface(
                    onClick = {
                        sessionCounter++
                        val newIndex = sessionCounter
                        val newSessionId = "session_$newIndex"
                        val newSession = ConsoleTabSession(
                            id = newSessionId,
                            title = "Session $newIndex",
                            manager = ResilientSSHManager(
                                vm = vm,
                                scope = scope,
                                onMetricsUpdated = null,
                                tmuxSessionOverride = "${vm.sshConfig.tmuxSessionName}-$newIndex"
                            )
                        )
                        sessions.add(newSession)
                        activeSessionId = newSessionId
                        val (h, _) = newSession.manager.resolveHostAndPort()
                        if (h.isNotBlank()) {
                            newSession.manager.connect()
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Spawn New Console Session",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Active Session Context & Connection Status Bar
        currentSession?.let { activeSession ->
            val connectionState by activeSession.manager.connectionState.collectAsState()
            val (host, port) = activeSession.manager.resolveHostAndPort()
            val hasTunnel = host.isNotBlank()

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(
                                    color = when (connectionState) {
                                        SSHConnectionState.CONNECTED -> TerminalGreen
                                        SSHConnectionState.RECONNECTING -> ColabOrange
                                        SSHConnectionState.CONNECTING -> TerminalYellow
                                        else -> TerminalRed
                                    },
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "${activeSession.title} • ${vm.sshConfig.resilientProtocol.label} • ${connectionState.name}",
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
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cable,
                                    contentDescription = "Configure Tunnel",
                                    modifier = Modifier.size(16.dp),
                                    tint = ColabOrange
                                )
                            }
                        }

                        IconButton(
                            onClick = { activeSession.manager.forceReconnect() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Force Reconnect",
                                modifier = Modifier.size(16.dp),
                                tint = ColabOrange
                            )
                        }
                        IconButton(
                            onClick = { activeSession.manager.clearScreen() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear Screen",
                                modifier = Modifier.size(16.dp)
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
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp)
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
                            Surface(
                                shape = CircleShape,
                                color = ColabOrange.copy(alpha = 0.15f),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Cable,
                                        contentDescription = null,
                                        tint = ColabOrange,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Tunnel host required to connect terminal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Button(
                            onClick = onConfigureTunnel,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                        ) {
                            Text("Set Host", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Terminal Output Screen
            val terminalLines by activeSession.manager.terminalOutput.collectAsState()
            val listState = rememberLazyListState()

            LaunchedEffect(terminalLines.size) {
                if (terminalLines.isNotEmpty()) {
                    listState.animateScrollToItem(terminalLines.size - 1)
                }
            }

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
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccessoryKeyButton("ESC") { activeSession.manager.sendSpecialKey("ESC") }
                    AccessoryKeyButton("TAB") { activeSession.manager.sendSpecialKey("TAB") }
                    AccessoryKeyButton("CTRL+C", isAction = true) { activeSession.manager.sendCtrlC() }
                    AccessoryKeyButton("colab-cli") { activeSession.manager.sendInput("colab-cli status\n") }
                    AccessoryKeyButton("nvidia-smi") { activeSession.manager.sendInput("nvidia-smi\n") }
                    AccessoryKeyButton("UP") { activeSession.manager.sendSpecialKey("UP") }
                    AccessoryKeyButton("DOWN") { activeSession.manager.sendSpecialKey("DOWN") }
                    AccessoryKeyButton("|") { activeSession.manager.sendInput("|") }
                    AccessoryKeyButton("/") { activeSession.manager.sendInput("/") }
                    AccessoryKeyButton("~") { activeSession.manager.sendInput("~") }
                }
            }

            // Command Input Bar (Minimalist M3 Pill Style)
            var inputCommand by remember(activeSession.id) { mutableStateOf("") }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputCommand,
                        onValueChange = { inputCommand = it },
                        leadingIcon = {
                            Text(
                                text = ">_",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                            )
                        },
                        placeholder = {
                            Text(
                                text = if (hasTunnel) "Type command and press Send / Enter..." else "Configure tunnel above to connect...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = hasTunnel,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputCommand.isNotBlank()) {
                                    activeSession.manager.sendInput("$inputCommand\n")
                                    inputCommand = ""
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        onClick = {
                            if (inputCommand.isNotBlank()) {
                                activeSession.manager.sendInput("$inputCommand\n")
                                inputCommand = ""
                            }
                        },
                        shape = CircleShape,
                        color = if (inputCommand.isNotBlank() && hasTunnel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (inputCommand.isNotBlank() && hasTunnel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                        enabled = inputCommand.isNotBlank() && hasTunnel,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
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
        shape = RoundedCornerShape(10.dp),
        color = if (isAction) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (isAction) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
