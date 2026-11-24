package com.pox.colab.ui.vmdetails

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.data.AppPreferences
import com.pox.colab.engine.AnsiParser
import com.pox.colab.engine.ResilientSSHManager
import com.pox.colab.engine.SSHConnectionState
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMMetrics
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.MonoFontFamily
import com.pox.colab.theme.TerminalThemes
import com.pox.colab.theme.statusErrorColor
import com.pox.colab.theme.statusNeutralColor
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.theme.statusWarningColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val SSH_QUICK_COMMANDS = listOf("ls -la", "nvidia-smi", "htop", "ps aux", "clear", "tmux ls")

enum class TerminalInputMode {
    LIVE_SHELL,
    COMMAND_BAR
}

data class ConsoleTabSession(
    val id: String,
    val title: String,
    val manager: ResilientSSHManager
)

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
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val sessions = sessionHolder.sessions
    val activeSessionId = sessionHolder.activeSessionId
    val currentSession = sessionHolder.activeSession

    var themeKey by remember(vm.id, activeSessionId) {
        mutableStateOf(preferences.getConsoleBgForTerminal(vm.id, activeSessionId))
    }
    val currentTheme = remember(themeKey) { TerminalThemes.resolve(themeKey) }

    var fontSizeSp by remember { mutableFloatStateOf(12f) }
    var inputMode by remember { mutableStateOf(TerminalInputMode.LIVE_SHELL) }
    var showThemeMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentSession?.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        sessionHolder.connectIfNeeded()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.background)
    ) {
        // Multi-Tab & Session Header
        Surface(
            color = currentTheme.panelBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessions.forEach { session ->
                        val isSelected = session.id == activeSessionId
                        val sessionState by session.manager.connectionState.collectAsStateWithLifecycle()

                        val tabStateColor = when (sessionState) {
                            SSHConnectionState.CONNECTED -> statusSuccessColor()
                            SSHConnectionState.CONNECTING, SSHConnectionState.RECONNECTING -> statusWarningColor()
                            SSHConnectionState.DISCONNECTED -> statusNeutralColor()
                            SSHConnectionState.ERROR -> statusErrorColor()
                        }

                        val tabBg by animateColorAsState(
                            targetValue = if (isSelected) {
                                currentTheme.accentColor.copy(alpha = 0.22f)
                            } else {
                                currentTheme.panelBg.copy(alpha = 0.6f)
                            },
                            label = "tabBg"
                        )

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = tabBg,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickable(role = Role.Tab) { sessionHolder.select(session.id) }
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
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) currentTheme.foreground else currentTheme.foreground.copy(alpha = 0.7f)
                                )
                                if (sessions.size > 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { sessionHolder.closeSession(session) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close ${session.title}",
                                            tint = currentTheme.foreground.copy(alpha = 0.6f),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    FilledTonalIconButton(
                        onClick = { sessionHolder.addSession() },
                        shape = CircleShape,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = currentTheme.panelBg,
                            contentColor = currentTheme.foreground
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Session", modifier = Modifier.size(16.dp))
                    }
                }

                // Terminal Action Toolbar in Top Right
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Mode Toggle: Live Shell vs Command Bar
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = currentTheme.accentColor.copy(alpha = 0.16f),
                        modifier = Modifier.clickable {
                            inputMode = if (inputMode == TerminalInputMode.LIVE_SHELL) {
                                TerminalInputMode.COMMAND_BAR
                            } else {
                                TerminalInputMode.LIVE_SHELL
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (inputMode == TerminalInputMode.LIVE_SHELL) Icons.Default.Terminal else Icons.Default.Add,
                                contentDescription = null,
                                tint = currentTheme.accentColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (inputMode == TerminalInputMode.LIVE_SHELL) "Live" else "Bar",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                fontWeight = FontWeight.Bold,
                                color = currentTheme.accentColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Font Zoom Controls
                    IconButton(
                        onClick = { if (fontSizeSp > 9f) fontSizeSp -= 1f },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Text("A-", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily), color = currentTheme.foreground)
                    }
                    IconButton(
                        onClick = { if (fontSizeSp < 20f) fontSizeSp += 1f },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Text("A+", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily), color = currentTheme.foreground)
                    }

                    // Theme Palette Menu
                    Box {
                        IconButton(
                            onClick = { showThemeMenu = true },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Terminal Theme",
                                tint = currentTheme.accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false }
                        ) {
                            TerminalThemes.ALL_THEMES.forEach { theme ->
                                DropdownMenuItem(
                                    text = { Text(theme.name, style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .background(theme.background)
                                        )
                                    },
                                    onClick = {
                                        preferences.setConsoleBgForTerminal(vm.id, activeSessionId, theme.key)
                                        themeKey = theme.key
                                        showThemeMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        currentSession?.let { session ->
            RealTerminalCanvasView(
                session = session,
                theme = currentTheme,
                fontSizeSp = fontSizeSp,
                inputMode = inputMode,
                onConfigureTunnel = onConfigureTunnel
            )
        }
    }
}

@Composable
private fun RealTerminalCanvasView(
    session: ConsoleTabSession,
    theme: com.pox.colab.theme.TerminalTheme,
    fontSizeSp: Float,
    inputMode: TerminalInputMode,
    onConfigureTunnel: () -> Unit
) {
    val manager = session.manager
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val connectionState by manager.connectionState.collectAsStateWithLifecycle()
    val terminalLines by manager.terminalOutput.collectAsStateWithLifecycle()
    val statusMessage by manager.statusMessage.collectAsStateWithLifecycle()

    var inputCommand by remember { mutableStateOf("") }
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    var autoScrollLocked by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val liveFocusRequester = remember { FocusRequester() }
    var rawInputValue by remember { mutableStateOf(TextFieldValue("")) }

    // Blinking cursor transition
    val infiniteTransition = rememberInfiniteTransition(label = "terminalCursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    // Detect user manual scroll to toggle auto-scroll pin
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (wasScrolling && !scrolling) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                autoScrollLocked = lastVisible >= info.totalItemsCount - 1
            }
            wasScrolling = scrolling
        }
    }

    val lastLine = terminalLines.lastOrNull()
    LaunchedEffect(lastLine?.id, lastLine?.text?.length, autoScrollLocked) {
        if (autoScrollLocked && !listState.isScrollInProgress && terminalLines.isNotEmpty()) {
            listState.scrollToItem(terminalLines.lastIndex)
        }
    }

    fun sendKeystroke(key: String) {
        val payload = when {
            isCtrlActive && key.length == 1 -> {
                val c = key.first().uppercaseChar()
                if (c in 'A'..'Z') (c.code - 64).toChar().toString() else key
            }
            isAltActive -> "\u001b$key"
            else -> key
        }
        isCtrlActive = false
        isAltActive = false
        manager.sendInput(payload)
    }

    fun submitCommand() {
        if (inputCommand.isBlank()) return
        val cmd = inputCommand.trim()
        commandHistory.add(cmd)
        historyIndex = -1
        manager.sendCommand("$cmd\n")
        inputCommand = ""
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // PTY Status Strip & Fast Actions Bar
        Surface(
            color = theme.panelBg.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val indicatorColor = when (connectionState) {
                        SSHConnectionState.CONNECTED -> statusSuccessColor()
                        SSHConnectionState.CONNECTING, SSHConnectionState.RECONNECTING -> statusWarningColor()
                        SSHConnectionState.DISCONNECTED -> statusNeutralColor()
                        SSHConnectionState.ERROR -> statusErrorColor()
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (connectionState == SSHConnectionState.CONNECTED) {
                            "Online • xterm-256color • UTF-8"
                        } else {
                            statusMessage
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = theme.foreground.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Auto-scroll pin toggle
                    IconButton(
                        onClick = {
                            autoScrollLocked = !autoScrollLocked
                            if (autoScrollLocked && terminalLines.isNotEmpty()) {
                                listState.requestScrollToItem(terminalLines.lastIndex)
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Auto Scroll",
                            tint = if (autoScrollLocked) theme.accentColor else theme.foreground.copy(alpha = 0.4f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Copy buffer
                    IconButton(
                        onClick = {
                            val text = manager.getPlainTextBuffer()
                            if (text.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(text))
                                Toast.makeText(context, "Terminal buffer copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Buffer",
                            tint = theme.foreground.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Clear buffer
                    IconButton(
                        onClick = { manager.clearOutput() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear",
                            tint = theme.foreground.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Reconnect
                    IconButton(
                        onClick = { manager.connect() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reconnect",
                            tint = theme.accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        if (connectionState == SSHConnectionState.DISCONNECTED || connectionState == SSHConnectionState.ERROR) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SSH target unreachable or tunnel unset.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onConfigureTunnel,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Cable, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Configure", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Real Terminal Screen Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable {
                    if (inputMode == TerminalInputMode.LIVE_SHELL) {
                        liveFocusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                items(terminalLines, key = { it.id }) { line ->
                    val isLast = line.id == lastLine?.id
                    val annotated = remember(line.text, theme.foreground, theme.background) {
                        if (line.isSystemNotice) {
                            AnnotatedString(line.text, SpanStyle(color = theme.accentColor, fontWeight = FontWeight.SemiBold))
                        } else {
                            AnsiParser.parseToAnnotatedString(
                                rawText = line.text,
                                defaultFg = theme.foreground,
                                defaultBg = theme.background
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = annotated,
                            fontFamily = MonoFontFamily,
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp * 1.34f).sp
                        )
                        // Live blinking terminal cursor on active prompt
                        if (isLast && connectionState == SSHConnectionState.CONNECTED) {
                            Text(
                                text = "█",
                                fontFamily = MonoFontFamily,
                                fontSize = fontSizeSp.sp,
                                color = theme.cursorColor,
                                modifier = Modifier.alpha(cursorAlpha)
                            )
                        }
                    }
                }
            }

            // Invisible Live Soft Keyboard Input Connector for Live Shell Mode
            if (inputMode == TerminalInputMode.LIVE_SHELL) {
                BasicTextField(
                    value = rawInputValue,
                    onValueChange = { newVal ->
                        val oldText = rawInputValue.text
                        val newText = newVal.text
                        if (newText.length > oldText.length) {
                            val typed = newText.substring(oldText.length)
                            sendKeystroke(typed)
                        } else if (newText.length < oldText.length) {
                            // Backspace sent to remote shell
                            manager.sendInput("\u007f")
                        }
                        // Reset text so it never accumulates locally
                        rawInputValue = TextFieldValue("")
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(liveFocusRequester),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Default
                    ),
                    keyboardActions = KeyboardActions(
                        onDefault = { manager.sendInput("\r") }
                    ),
                    textStyle = TextStyle(color = Color.Transparent),
                    cursorBrush = SolidColor(Color.Transparent)
                )
            }
        }

        // Authentic Terminal Accessory Bar (Row 1: Navigation & Modifiers)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(theme.panelBg)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TerminalKey("ESC", "Escape", theme = theme) { sendKeystroke("\u001b") }
            TerminalKey("TAB", "Tab", theme = theme) { sendKeystroke("\t") }

            TerminalKey(
                label = "CTRL",
                description = "Control Modifier",
                active = isCtrlActive,
                activeColor = theme.accentColor,
                theme = theme
            ) { isCtrlActive = !isCtrlActive }

            TerminalKey(
                label = "ALT",
                description = "Alt Modifier",
                active = isAltActive,
                activeColor = MaterialTheme.colorScheme.tertiary,
                theme = theme
            ) { isAltActive = !isAltActive }

            TerminalKey("↑", "History Up", theme = theme) { manager.sendSpecialKey("UP") }
            TerminalKey("↓", "History Down", theme = theme) { manager.sendSpecialKey("DOWN") }
            TerminalKey("←", "Cursor Left", theme = theme) { manager.sendSpecialKey("LEFT") }
            TerminalKey("→", "Cursor Right", theme = theme) { manager.sendSpecialKey("RIGHT") }
            TerminalKey("HOME", "Home", theme = theme) { manager.sendInput("\u001b[H") }
            TerminalKey("END", "End", theme = theme) { manager.sendInput("\u001b[F") }
            TerminalKey("PGUP", "Page Up", theme = theme) { manager.sendInput("\u001b[5~") }
            TerminalKey("PGDN", "Page Down", theme = theme) { manager.sendInput("\u001b[6~") }
        }

        // Authentic Terminal Accessory Bar (Row 2: Unix Signals, Operators & Symbols)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(theme.panelBg)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TerminalKey("Ctrl+C", "Interrupt SIGINT", activeColor = statusErrorColor(), theme = theme) {
                manager.sendCtrlC()
            }
            TerminalKey("Ctrl+D", "EOF", theme = theme) { manager.sendInput("\u0004") }
            TerminalKey("Ctrl+Z", "Suspend SIGTSTP", theme = theme) { manager.sendInput("\u001a") }
            TerminalKey("Ctrl+L", "Clear Screen", theme = theme) { manager.sendInput("\u000c") }

            listOf("|", "~", "/", "\\", "-", "_", "$", "&", ">", "<", ";", ":", "\"", "'").forEach { sym ->
                TerminalKey(sym, "Symbol $sym", theme = theme) { manager.sendInput(sym) }
            }

            SSH_QUICK_COMMANDS.forEach { cmd ->
                TerminalKey(cmd, "Run $cmd", contentColor = theme.accentColor, theme = theme) {
                    manager.sendCommand("$cmd\n")
                }
            }
        }

        // Bottom Input Region
        if (inputMode == TerminalInputMode.COMMAND_BAR) {
            Surface(
                color = theme.panelBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // History Up Button
                    IconButton(
                        onClick = {
                            if (commandHistory.isNotEmpty()) {
                                historyIndex = if (historyIndex == -1) {
                                    commandHistory.lastIndex
                                } else {
                                    (historyIndex - 1).coerceAtLeast(0)
                                }
                                inputCommand = commandHistory[historyIndex]
                            }
                        },
                        enabled = commandHistory.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "History Previous",
                            tint = theme.foreground.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // History Down Button
                    IconButton(
                        onClick = {
                            if (commandHistory.isNotEmpty() && historyIndex != -1) {
                                if (historyIndex < commandHistory.lastIndex) {
                                    historyIndex++
                                    inputCommand = commandHistory[historyIndex]
                                } else {
                                    historyIndex = -1
                                    inputCommand = ""
                                }
                            }
                        },
                        enabled = historyIndex != -1,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "History Next",
                            tint = theme.foreground.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    OutlinedTextField(
                        value = inputCommand,
                        onValueChange = { inputCommand = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = "Enter shell command...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily)
                            )
                        },
                        trailingIcon = {
                            if (inputCommand.isNotEmpty()) {
                                IconButton(
                                    onClick = { inputCommand = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = theme.foreground.copy(alpha = 0.6f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = MonoFontFamily,
                            color = theme.foreground
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { submitCommand() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = theme.background,
                            focusedContainerColor = theme.background,
                            unfocusedBorderColor = theme.foreground.copy(alpha = 0.15f),
                            focusedBorderColor = theme.accentColor
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Paste button
                    IconButton(
                        onClick = {
                            clipboardManager.getText()?.text?.let { text ->
                                inputCommand += text
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            tint = theme.foreground.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Send Button
                    FilledIconButton(
                        onClick = { submitCommand() },
                        enabled = inputCommand.isNotBlank(),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = theme.accentColor,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Execute",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            // Live Shell Mode footer helper showing quick instructions
            Surface(
                color = theme.panelBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Interactive PTY Active • Tap screen for keyboard",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = theme.foreground.copy(alpha = 0.6f)
                    )
                    IconButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrEmpty()) {
                                manager.sendInput(clipText)
                                Toast.makeText(context, "Pasted into shell", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste to shell",
                            tint = theme.accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    description: String,
    active: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color? = null,
    theme: com.pox.colab.theme.TerminalTheme,
    onClick: () -> Unit
) {
    val corner by animateDpAsState(
        targetValue = if (active) 8.dp else 14.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "terminalKeyCorner"
    )

    val bgColor = when {
        active -> activeColor
        else -> theme.panelBg.copy(alpha = 0.85f)
    }

    val fgColor = when {
        active -> Color.Black
        contentColor != null -> contentColor
        else -> theme.foreground
    }

    Surface(
        shape = RoundedCornerShape(corner),
        color = bgColor,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minWidth = 38.dp, minHeight = 32.dp)
            .semantics { this.contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = fgColor
            )
        }
    }
}
