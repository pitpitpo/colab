package com.pox.colab.ui.vmdetails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.pox.colab.model.VMStatus
import com.pox.colab.model.VMMetrics
import com.pox.colab.theme.TerminalCyan
import com.pox.colab.theme.TerminalForeground
import com.pox.colab.theme.TerminalRed
import com.pox.colab.theme.TerminalYellow
import com.pox.colab.theme.resolveConsoleBackground
import com.pox.colab.theme.statusErrorColor
import com.pox.colab.theme.statusNeutralColor
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.theme.statusWarningColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect

data class ConsoleTabSession(
    val id: String,
    val title: String,
    val manager: ResilientSSHManager
)

/**
 * Retains multi-tab SSH sessions above the [androidx.compose.foundation.pager.HorizontalPager]
 * so that [VMDetailsScreen]'s `beyondBoundsPageCount = 0` policy (no eager off-screen
 * connections) does not tear down a live console every time the user peeks at another tab.
 */
class ConsoleSessionHolder(
    private val vm: ColabVM,
    private val scope: CoroutineScope,
    private val onMetricsUpdated: ((VMMetrics) -> Unit)? = null,
    private val onStatusChanged: ((VMStatus) -> Unit)? = null
) {
    val sessions = mutableStateListOf<ConsoleTabSession>()

    var activeSessionId by mutableStateOf("")
        private set

    private var sessionCounter = 0

    init {
        addSession(connect = false)
    }

    val activeSession: ConsoleTabSession?
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()

    fun addSession(connect: Boolean = true): ConsoleTabSession {
        sessionCounter += 1
        val id = "session_$sessionCounter"
        val session = ConsoleTabSession(
            id = id,
            title = "Session $sessionCounter",
            manager = ResilientSSHManager(
                vm = vm,
                scope = scope,
                onMetricsUpdated = onMetricsUpdated,
                onConnectionStateChanged = { state -> onStatusChanged?.invoke(state.toVmStatus()) },
                tmuxSessionOverride = if (sessionCounter == 1) {
                    vm.sshConfig.tmuxSessionName
                } else {
                    "${vm.sshConfig.tmuxSessionName}_$sessionCounter"
                }
            )
        )
        sessions.add(session)
        activeSessionId = id
        if (connect) session.manager.connect()
        return session
    }

    fun select(id: String) {
        activeSessionId = id
    }

    fun connectIfNeeded() {
        val session = activeSession ?: return
        val state = session.manager.connectionState.value
        if (state == SSHConnectionState.DISCONNECTED || state == SSHConnectionState.ERROR) {
            session.manager.connect()
        }
    }

    fun closeSession(session: ConsoleTabSession) {
        session.manager.disconnect()
        val index = sessions.indexOf(session)
        sessions.remove(session)
        if (activeSessionId == session.id) {
            activeSessionId = sessions.getOrNull(index.coerceAtMost(sessions.lastIndex))?.id ?: ""
        }
    }

    fun close() {
        sessions.forEach { it.manager.disconnect() }
        sessions.clear()
    }
}

private fun SSHConnectionState.toVmStatus(): VMStatus = when (this) {
    SSHConnectionState.CONNECTED -> VMStatus.RUNNING
    SSHConnectionState.CONNECTING -> VMStatus.STARTING
    SSHConnectionState.RECONNECTING -> VMStatus.RECONNECTING
    SSHConnectionState.ERROR -> VMStatus.ERROR
    SSHConnectionState.DISCONNECTED -> VMStatus.STOPPED
}

@Composable
fun SSHTab(
    vm: ColabVM,
    preferences: AppPreferences,
    sessionHolder: ConsoleSessionHolder,
    onConfigureTunnel: () -> Unit
) {
    val sessions = sessionHolder.sessions
    val activeSessionId = sessionHolder.activeSessionId
    val currentSession = sessionHolder.activeSession

    // Retrieve active terminal background color with per-terminal support
    var termBgKey by remember(vm.id, activeSessionId) {
        mutableStateOf(preferences.getConsoleBgForTerminal(vm.id, activeSessionId))
    }
    val consoleBgColor = resolveConsoleBackground(termBgKey)
    var showBgMenu by remember { mutableStateOf(false) }

    // Handle auto-connection for the initial session and endpoint updates
    LaunchedEffect(currentSession?.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        sessionHolder.connectIfNeeded()
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
                            SSHConnectionState.CONNECTED -> statusSuccessColor()
                            SSHConnectionState.CONNECTING, SSHConnectionState.RECONNECTING -> statusWarningColor()
                            SSHConnectionState.DISCONNECTED -> statusNeutralColor()
                            SSHConnectionState.ERROR -> statusErrorColor()
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable(role = Role.Tab) { sessionHolder.select(session.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = if (sessions.size > 1) 4.dp else 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val stateLabel = when (sessionState) {
                                    SSHConnectionState.CONNECTED -> "Online"
                                    SSHConnectionState.CONNECTING -> "Connecting"
                                    SSHConnectionState.RECONNECTING -> "Reconnecting"
                                    SSHConnectionState.DISCONNECTED -> "Offline"
                                    SSHConnectionState.ERROR -> "Error"
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(tabStateColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stateLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tabStateColor
                                    )
                                }

                                if (sessions.size > 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button) {
                                                sessionHolder.closeSession(session)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close ${session.title}",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Add New Terminal Tab (+) Button
                IconButton(
                    onClick = { sessionHolder.addSession() },
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

    // Auto-scroll only while the transcript is already pinned to the newest line. The flag is
    // re-evaluated when a user-initiated scroll settles, so reading scrollback is never hijacked.
    var autoScrollToLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (wasScrolling && !scrolling) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                autoScrollToLatest = lastVisible >= info.totalItemsCount - 1
            }
            wasScrolling = scrolling
        }
    }

    LaunchedEffect(terminalLines.size) {
        if (autoScrollToLatest && !listState.isScrollInProgress && terminalLines.isNotEmpty()) {
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
                        SSHConnectionState.CONNECTED -> statusSuccessColor()
                        SSHConnectionState.CONNECTING, SSHConnectionState.RECONNECTING -> statusWarningColor()
                        SSHConnectionState.DISCONNECTED -> statusNeutralColor()
                        SSHConnectionState.ERROR -> statusErrorColor()
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
                    TextButton(
                        onClick = onConfigureTunnel,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Cable, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Configure Tunnel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
            TerminalAccessoryKey("ESC", "Escape", onClick = { manager.sendCommand("\u001b") })
            TerminalAccessoryKey("TAB", "Tab", onClick = { manager.sendCommand("\t") })

            TerminalAccessoryKey(
                label = "CTRL",
                description = "Control modifier",
                active = isCtrlActive,
                onClick = { isCtrlActive = !isCtrlActive }
            )
            TerminalAccessoryKey(
                label = "ALT",
                description = "Alt modifier",
                active = isAltActive,
                activeContainer = MaterialTheme.colorScheme.tertiary,
                activeContent = MaterialTheme.colorScheme.onTertiary,
                onClick = { isAltActive = !isAltActive }
            )

            TerminalAccessoryKey("↑", "Arrow up", onClick = { manager.sendCommand("\u001b[A") })
            TerminalAccessoryKey("↓", "Arrow down", onClick = { manager.sendCommand("\u001b[B") })
            TerminalAccessoryKey("←", "Arrow left", onClick = { manager.sendCommand("\u001b[D") })
            TerminalAccessoryKey("→", "Arrow right", onClick = { manager.sendCommand("\u001b[C") })

            TerminalAccessoryKey("Ctrl+C", "Send interrupt (Ctrl+C)", onClick = { manager.sendCommand("\u0003") })
            TerminalAccessoryKey("Ctrl+D", "Send end of file (Ctrl+D)", onClick = { manager.sendCommand("\u0004") })
            TerminalAccessoryKey("Ctrl+Z", "Send suspend (Ctrl+Z)", onClick = { manager.sendCommand("\u001a") })
            TerminalAccessoryKey("Clear", "Clear terminal buffer", onClick = { manager.clearOutput() })

            listOf("ls -la", "htop", "tmux ls").forEach { cmd ->
                TerminalAccessoryKey(
                    label = cmd,
                    description = "Run $cmd",
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { manager.sendCommand("$cmd\n") }
                )
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

/**
 * Single accessory key: guaranteed 48dp minimum touch target, exposed to accessibility
 * services as a button with a human readable description.
 */
@Composable
private fun TerminalAccessoryKey(
    label: String,
    description: String,
    active: Boolean = false,
    activeContainer: Color = MaterialTheme.colorScheme.primary,
    activeContent: Color = MaterialTheme.colorScheme.onPrimary,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) activeContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) activeContent else contentColor
            )
        }
    }
}
