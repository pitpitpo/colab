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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.data.AppPreferences
import com.pox.colab.engine.ResilientSSHManager
import com.pox.colab.engine.SSHConnectionState
import com.pox.colab.service.SSHBackgroundService
import com.pox.colab.model.ColabVM
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
    onMetricsUpdated: ((VMMetrics) -> Unit)? = null,
    onStatusChanged: ((VMStatus) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessionCounter by remember { mutableIntStateOf(1) }

    // Multi-tab session state
    val sessions = remember(vm.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        mutableStateListOf<ConsoleTabSession>().apply {
            add(
                ConsoleTabSession(
                    id = "session_1",
                    title = "Session 1",
                    manager = ResilientSSHManager(
                        vm = vm,
                        scope = scope,
                        onMetricsUpdated = onMetricsUpdated,
                        onConnectionStateChanged = { state ->
                            when (state) {
                                SSHConnectionState.CONNECTED -> onStatusChanged?.invoke(VMStatus.RUNNING)
                                SSHConnectionState.CONNECTING -> onStatusChanged?.invoke(VMStatus.STARTING)
                                SSHConnectionState.RECONNECTING -> onStatusChanged?.invoke(VMStatus.RECONNECTING)
                                SSHConnectionState.ERROR -> onStatusChanged?.invoke(VMStatus.ERROR)
                                SSHConnectionState.DISCONNECTED -> onStatusChanged?.invoke(VMStatus.STOPPED)
                            }
                        },
                        tmuxSessionOverride = vm.sshConfig.tmuxSessionName
                    )
                )
            )
        }
    }

    var activeSessionId by remember { mutableStateOf(sessions.firstOrNull()?.id ?: "") }
    val currentSession = sessions.find { it.id == activeSessionId } ?: sessions.firstOrNull()

    // Retrieve active terminal background color with per-terminal support
    var termBgKey by remember(vm.id, activeSessionId) {
        mutableStateOf(preferences.getConsoleBgForTerminal(vm.id, activeSessionId))
    }
    val consoleBgColor = resolveConsoleBackground(termBgKey)
    var showBgMenu by remember { mutableStateOf(false) }

    // Handle auto-connection for initial session and endpoint updates
    LaunchedEffect(currentSession?.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        currentSession?.let { s ->
            if (s.manager.connectionState.value == SSHConnectionState.DISCONNECTED) {
                s.manager.connect()
            }
        }
    }

    DisposableEffect(vm.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        SSHBackgroundService.start(context, vm.name)
        onDispose {
            sessions.forEach { it.manager.disconnect() }
            SSHBackgroundService.stop(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(consoleBgColor)
    ) {
        // Multi-Tab Experience Header Row
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scrollable row of session tabs
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessions.forEach { session ->
                        val isSelected = session.id == activeSessionId
                        val sessionState by session.manager.connectionState.collectAsState()

                        val tabStateColor = when (sessionState) {
                            SSHConnectionState.CONNECTED -> TerminalGreen
                            SSHConnectionState.CONNECTING, SSHConnectionState.RECONNECTING -> TerminalYellow
                            SSHConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                            SSHConnectionState.ERROR -> MaterialTheme.colorScheme.error
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                            modifier = Modifier.clickable { activeSessionId = session.id }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(tabStateColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = session.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (sessions.size > 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                session.manager.disconnect()
                                                val idx = sessions.indexOf(session)
                                                sessions.remove(session)
                                                if (activeSessionId == session.id) {
                                                    activeSessionId = sessions.getOrNull(idx.coerceAtMost(sessions.lastIndex))?.id ?: ""
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close tab",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Add New Terminal Tab (+) Button
                IconButton(
                    onClick = {
                        sessionCounter++
                        val newId = "session_$sessionCounter"
                        val newTitle = "Session $sessionCounter"
                        val newSession = ConsoleTabSession(
                            id = newId,
                            title = newTitle,
                            manager = ResilientSSHManager(
                                vm = vm,
                                scope = scope,
                                onMetricsUpdated = onMetricsUpdated,
                                onConnectionStateChanged = { state ->
                                    when (state) {
                                        SSHConnectionState.CONNECTED -> onStatusChanged?.invoke(VMStatus.RUNNING)
                                        SSHConnectionState.CONNECTING -> onStatusChanged?.invoke(VMStatus.STARTING)
                                        SSHConnectionState.RECONNECTING -> onStatusChanged?.invoke(VMStatus.RECONNECTING)
                                        SSHConnectionState.ERROR -> onStatusChanged?.invoke(VMStatus.ERROR)
                                        SSHConnectionState.DISCONNECTED -> onStatusChanged?.invoke(VMStatus.STOPPED)
                                    }
                                },
                                tmuxSessionOverride = "${vm.sshConfig.tmuxSessionName}_$sessionCounter"
                            )
                        )
                        sessions.add(newSession)
                        activeSessionId = newId
                        newSession.manager.connect()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Terminal Tab",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Per-terminal background tone selector
                Box {
                    IconButton(
                        onClick = { showBgMenu = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Terminal Background",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showBgMenu,
                        onDismissRequest = { showBgMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Inherit VM Default", fontSize = 13.sp) },
                            onClick = {
                                val vmDefault = preferences.getConsoleBgForVM(vm.id)
                                preferences.setConsoleBgForTerminal(vm.id, activeSessionId, vmDefault)
                                termBgKey = vmDefault
                                showBgMenu = false
                            }
                        )
                        listOf(
                            "amoled_black" to "AMOLED Pure Black",
                            "deep_charcoal" to "Deep Charcoal",
                            "navy_terminal" to "Navy Terminal",
                            "matrix_dark" to "Matrix Dark",
                            "monokai_dark" to "Monokai Dark"
                        ).forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(resolveConsoleBackground(key))
                                    )
                                },
                                onClick = {
                                    preferences.setConsoleBgForTerminal(vm.id, activeSessionId, key)
                                    termBgKey = key
                                    showBgMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Active Session Shell View
        currentSession?.let { session ->
            ActiveSessionTerminalView(
                session = session,
                onConfigureTunnel = onConfigureTunnel
            )
        }
    }
}

@Composable
private fun ActiveSessionTerminalView(
    session: ConsoleTabSession,
    onConfigureTunnel: () -> Unit
) {
    val manager = session.manager
    val connectionState by manager.connectionState.collectAsState()
    val terminalLines by manager.terminalOutput.collectAsState()
    val statusMessage by manager.statusMessage.collectAsState()

    var inputCommand by remember { mutableStateOf("") }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.scrollToItem(terminalLines.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Status banner
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
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
                    val indicatorColor = when (connectionState) {
                        SSHConnectionState.CONNECTED -> TerminalGreen
                        SSHConnectionState.CONNECTING, SSHConnectionState.RECONNECTING -> TerminalYellow
                        SSHConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                        SSHConnectionState.ERROR -> MaterialTheme.colorScheme.error
                    }
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(indicatorColor))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { manager.clearOutput() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear buffer", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { manager.connect() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Optional Tunnel Configuration Prompt if disconnected / unreachable
        if (connectionState == SSHConnectionState.DISCONNECTED || connectionState == SSHConnectionState.ERROR) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SSH target unreachable or tunnel endpoint unset.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onConfigureTunnel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.Cable, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Configure Tunnel", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Terminal Output Screen
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            items(terminalLines, key = { it.id }) { line ->
                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = when {
                        line.isSystemNotice -> TerminalCyan
                        line.text.startsWith("$") -> TerminalYellow
                        line.text.contains("error", ignoreCase = true) -> TerminalRed
                        else -> TerminalForeground
                    },
                    lineHeight = 16.sp
                )
            }
        }

        // Full Accessory Keys Row (ESC, TAB, CTRL, ALT, Arrow keys ↑↓←→, Macros)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ESC
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { manager.sendCommand("\u001b") }
            ) {
                Text("ESC", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary)
            }

            // TAB
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { manager.sendCommand("\t") }
            ) {
                Text("TAB", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
            }

            // CTRL Modifier Toggle
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isCtrlActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { isCtrlActive = !isCtrlActive }
            ) {
                Text("CTRL", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = if (isCtrlActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }

            // ALT Modifier Toggle
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isAltActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { isAltActive = !isAltActive }
            ) {
                Text("ALT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = if (isAltActive) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface)
            }

            // Arrow Keys: Up, Down, Left, Right
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { manager.sendCommand("\u001b[A") }
            ) {
                Text("↑", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { manager.sendCommand("\u001b[B") }
            ) {
                Text("↓", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { manager.sendCommand("\u001b[D") }
            ) {
                Text("←", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { manager.sendCommand("\u001b[C") }
            ) {
                Text("→", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
            }

            // Control Combos & Macros
            listOf("Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+Z" to "\u001a", "Clear" to "CLEAR").forEach { (label, code) ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable {
                        if (code == "CLEAR") manager.clearOutput()
                        else manager.sendCommand(code)
                    }
                ) {
                    Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }

            listOf("ls -la", "htop", "tmux ls").forEach { cmd ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { manager.sendCommand("$cmd\n") }
                ) {
                    Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Command Input Field with bottom NavigationBar and IME keyboard padding
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
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
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter shell command...", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputCommand.isNotBlank()) {
                                val toSend = when {
                                    isCtrlActive && inputCommand.length == 1 -> {
                                        val c = inputCommand.first().uppercaseChar()
                                        if (c in 'A'..'Z') (c.code - 64).toChar().toString() else inputCommand
                                    }
                                    isAltActive -> "\u001b$inputCommand"
                                    else -> "$inputCommand\n"
                                }
                                isCtrlActive = false
                                isAltActive = false
                                manager.sendCommand(toSend)
                                inputCommand = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        if (inputCommand.isNotBlank()) {
                            val toSend = when {
                                isCtrlActive && inputCommand.length == 1 -> {
                                    val c = inputCommand.first().uppercaseChar()
                                    if (c in 'A'..'Z') (c.code - 64).toChar().toString() else inputCommand
                                }
                                isAltActive -> "\u001b$inputCommand"
                                else -> "$inputCommand\n"
                            }
                            isCtrlActive = false
                            isAltActive = false
                            manager.sendCommand(toSend)
                            inputCommand = ""
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Execute",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
