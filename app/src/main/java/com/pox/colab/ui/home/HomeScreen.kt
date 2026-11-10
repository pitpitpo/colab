package com.pox.colab.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pox.colab.R
import com.pox.colab.data.VMRepository
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.ColabYellow
import com.pox.colab.theme.TerminalGreen
import com.pox.colab.theme.TerminalRed
import com.pox.colab.theme.TerminalYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: VMRepository,
    onNavigateToVM: (vmId: String, tabIndex: Int) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val vms by repository.vms.collectAsState()
    val accounts by repository.accounts.collectAsState()
    val activeAccount by repository.activeAccount.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredVms = remember(vms, searchQuery) {
        if (searchQuery.isBlank()) vms
        else vms.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_colab_logo),
                            contentDescription = "Colab Icon",
                            modifier = Modifier.size(32.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "colab",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Alpine & Resilient SSH",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Active Account Switcher Chip
                    Box {
                        AssistChip(
                            onClick = { accountMenuExpanded = true },
                            label = {
                                Text(
                                    text = activeAccount?.displayName ?: "No Account",
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(ColabOrange)
                                )
                            }
                        )

                        DropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismissRequest = { accountMenuExpanded = false }
                        ) {
                            accounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(acc.displayName, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                acc.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        repository.setActiveAccount(acc.id)
                                        accountMenuExpanded = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("+ Manage in Settings", color = ColabOrange) },
                                onClick = {
                                    accountMenuExpanded = false
                                    onNavigateToSettings()
                                }
                            )
                        }
                    }

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = ColabOrange,
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Colab VM")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search Colab VMs...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (filteredVms.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_colab_logo),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Colab VMs Found",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the + button below to spin up a minimal Alpine Linux instance with Colab CLI & resilient SSH.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                        ) {
                            Text("Spin Up First VM")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredVms, key = { it.id }) { vm ->
                        val account = accounts.find { it.id == vm.accountId }
                        VMCard(
                            vm = vm,
                            account = account,
                            onOpenDashboard = { onNavigateToVM(vm.id, 0) },
                            onOpenSSH = { onNavigateToVM(vm.id, 1) },
                            onOpenFiles = { onNavigateToVM(vm.id, 2) },
                            onTogglePower = {
                                if (vm.status == VMStatus.RUNNING) {
                                    repository.stopVM(vm.id)
                                } else {
                                    repository.spinUpAlpineVM(vm.id)
                                }
                            },
                            onDelete = { repository.deleteVM(vm.id) }
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AddVMDialog(
                accounts = accounts,
                activeAccountId = activeAccount?.id,
                onDismiss = { showAddDialog = false },
                onConfirm = { name, accId, accelerator, tunnel, proto ->
                    repository.addVM(name, accId, accelerator, tunnel, proto)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun VMCard(
    vm: ColabVM,
    account: ColabAccount?,
    onOpenDashboard: () -> Unit,
    onOpenSSH: () -> Unit,
    onOpenFiles: () -> Unit,
    onTogglePower: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDashboard() },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIndicatorDot(status = vm.status)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = vm.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${account?.displayName ?: "Unknown Account"} • ${vm.alpineVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Power Toggle") },
                            onClick = {
                                menuExpanded = false
                                onTogglePower()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete VM", color = TerminalRed) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Specs Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(vm.hardwareAccelerator.label) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DeveloperBoard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = ColabOrange
                        )
                    }
                )

                SuggestionChip(
                    onClick = {},
                    label = { Text(vm.sshConfig.resilientProtocol.name) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = TerminalGreen
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Metrics Bar
            if (vm.status == VMStatus.RUNNING) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "RAM: ${vm.metrics.formattedRam()}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "Disk: ${vm.metrics.formattedDisk()}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    LinearProgressIndicator(
                        progress = { vm.metrics.ramUsagePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = ColabOrange,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Actions Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenSSH,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SSH")
                }

                OutlinedButton(
                    onClick = onOpenFiles,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Files")
                }

                Button(
                    onClick = onOpenDashboard,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ColabOrange),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dash")
                }
            }
        }
    }
}

@Composable
fun StatusIndicatorDot(status: VMStatus) {
    val color = when (status) {
        VMStatus.RUNNING -> TerminalGreen
        VMStatus.STARTING -> TerminalYellow
        VMStatus.RECONNECTING -> ColabOrange
        VMStatus.STOPPED -> MaterialTheme.colorScheme.outline
        VMStatus.ERROR -> TerminalRed
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (status == VMStatus.RUNNING || status == VMStatus.RECONNECTING) alpha else 1f))
    )
}
