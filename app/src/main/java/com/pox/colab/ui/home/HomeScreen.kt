package com.pox.colab.ui.home

import android.animation.ValueAnimator
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.R
import com.pox.colab.data.VMRepository
import com.pox.colab.ui.component.shimmer.GridItemPlaceholder
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.theme.vmStatusColor
import com.pox.colab.theme.vmStatusLabel
import com.pox.colab.ui.settings.SignInAccountDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: VMRepository,
    onNavigateToVM: (vmId: String, tabIndex: Int) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val vms by repository.vms.collectAsState()
    val accounts by repository.accounts.collectAsState()
    val activeAccount by repository.activeAccount.collectAsState()
    val isLoading by repository.isLoading.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredVms = remember(vms, searchQuery) {
        if (searchQuery.isBlank()) vms
        else vms.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_colab_logo),
                            contentDescription = "Colab Logo",
                            modifier = Modifier.size(28.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Colab",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Google Colab & Resilient Shell",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Account Pill Button
                    Surface(
                        onClick = {
                            if (accounts.isEmpty()) {
                                showSignInDialog = true
                            } else {
                                accountMenuExpanded = true
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (activeAccount != null) Icons.Default.Person else Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (activeAccount != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeAccount?.displayName ?: "Connect Account",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        DropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismissRequest = { accountMenuExpanded = false }
                        ) {
                            accounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(acc.displayName, fontWeight = FontWeight.SemiBold)
                                            Text(acc.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        repository.setActiveAccount(acc.id)
                                        accountMenuExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Settings & Accounts", color = MaterialTheme.colorScheme.primary) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    accountMenuExpanded = false
                                    onNavigateToSettings()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (accounts.isEmpty()) {
                        Toast.makeText(context, "Google Account required to provision Colab instances. Please sign in.", Toast.LENGTH_LONG).show()
                        showSignInDialog = true
                    } else {
                        showAddDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "New VM", modifier = Modifier.size(28.dp))
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (vms.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search instances...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            if (isLoading) {
                items(2) {
                    GridItemPlaceholder()
                }
            } else if (vms.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    M3EmptyState(
                        hasAccounts = accounts.isNotEmpty(),
                        activeAccount = activeAccount,
                        onPrimaryAction = { showAddDialog = true },
                        onConnectAccount = { showSignInDialog = true }
                    )
                }
            } else if (filteredVms.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No Instances Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("No instances match '$searchQuery'", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("Clear Search")
                            }
                        }
                    }
                }
            } else {
                items(filteredVms, key = { it.id }) { vm ->
                    val account = accounts.find { it.id == vm.accountId }
                    VMGridCard(
                        vm = vm,
                        account = account,
                        onOpen = { onNavigateToVM(vm.id, 0) },
                        onOpenSSH = { onNavigateToVM(vm.id, 1) },
                        onOpenFiles = { onNavigateToVM(vm.id, 2) },
                        onTogglePower = {
                            if (vm.status == VMStatus.RUNNING) repository.stopVM(vm.id)
                            else repository.spinUpVM(vm.id)
                        },
                        onDelete = { repository.deleteVM(vm.id) }
                    )
                }
            }
        }

        if (showAddDialog) {
            AddVMDialog(
                accounts = accounts,
                activeAccountId = activeAccount?.id,
                onDismiss = { showAddDialog = false },
                onConnectAccount = {
                    showAddDialog = false
                    showSignInDialog = true
                },
                onConfirm = { name, accId, accelerator, tunnel, proto, password, tunnelEndpoint ->
                    val newVm = repository.addVM(name, accId, accelerator, tunnel, proto, password, tunnelEndpoint)
                    showAddDialog = false
                    repository.spinUpVM(newVm.id)
                    onNavigateToVM(newVm.id, 0)
                }
            )
        }

        if (showSignInDialog) {
            SignInAccountDialog(
                onDismiss = { showSignInDialog = false },
                onConfirm = { email, name, accessToken, tier, tokenJson ->
                    repository.addAccount(context, email, name, accessToken, tier, tokenJson)
                    showSignInDialog = false
                    Toast.makeText(context, "Account connected: $email", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun M3EmptyState(
    hasAccounts: Boolean,
    activeAccount: ColabAccount?,
    onPrimaryAction: () -> Unit,
    onConnectAccount: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (hasAccounts) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_colab_logo),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = Color.Unspecified
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = if (hasAccounts) "No Virtual Machines" else "Connect Google Account",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hasAccounts) "Ready to launch for ${activeAccount?.displayName ?: "your account"}. Create a Colab instance to provision GPU/TPU runtimes."
                       else "Google Colab allocates GPU/TPU runtimes exclusively to authenticated Google accounts. Connect your account using official PKCE OAuth2 to start managing Colab instances.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = if (hasAccounts) onPrimaryAction else onConnectAccount,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = if (hasAccounts) Icons.Default.Add else Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasAccounts) "New Colab Instance" else "Connect Google Account",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun VMGridCard(
    vm: ColabVM,
    account: ColabAccount?,
    onOpen: () -> Unit,
    onOpenSSH: () -> Unit,
    onOpenFiles: () -> Unit,
    onTogglePower: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    StatusIndicatorDot(status = vm.status, showLabel = true)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(vm.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val hasTunnel = vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost" || !vm.tunnelUrl.isNullOrBlank()
                        Text(
                            text = if (hasTunnel) "${vm.sshConfig.host}:${vm.sshConfig.port}" else account?.let { "${it.displayName} • ${it.tier}" } ?: "Account Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (hasTunnel) FontFamily.Monospace else FontFamily.Default,
                            color = if (account != null || hasTunnel) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (vm.status == VMStatus.RUNNING) "Stop Instance" else "Start Instance") },
                            leadingIcon = { Icon(if (vm.status == VMStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = { menuExpanded = false; onTogglePower() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete Instance", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Semantic Chips
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onOpen,
                    label = { Text(vm.hardwareAccelerator.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.DeveloperBoard, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(10.dp),
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = null,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )

                AssistChip(
                    onClick = onOpenSSH,
                    label = { Text(vm.sshConfig.resilientProtocol.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(14.dp), tint = statusSuccessColor()) },
                    shape = RoundedCornerShape(10.dp),
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = null,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )
            }

            // CPU load bar if running
            if (vm.status == VMStatus.RUNNING && vm.metrics.cpuUsagePercent > 0f) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("CPU ${vm.metrics.cpuUsagePercent.toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = { (vm.metrics.cpuUsagePercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenSSH,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Terminal", maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                FilledTonalButton(
                    onClick = onOpenFiles,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Files", maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun StatusIndicatorDot(
    status: VMStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false
) {
    val color = vmStatusColor(status)
    val label = vmStatusLabel(status)
    val pulses = status == VMStatus.RUNNING || status == VMStatus.RECONNECTING

    // Honour "Remove animations" / animator duration scale 0 for users who disable motion.
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }

    val alpha: Float = if (pulses && animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "vmStatusPulse")
        val animated by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
            label = "pulseAlpha"
        )
        animated
    } else {
        1f
    }

    Row(
        modifier = if (showLabel) {
            modifier
        } else {
            modifier.semantics { contentDescription = "Status: $label" }
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}
