package com.pox.colab.ui.home

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.R
import com.pox.colab.data.VMRepository
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.ColabOrange
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_colab_logo),
                            contentDescription = "Colab Icon",
                            modifier = Modifier.size(28.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "colab",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Alpine & Resilient SSH",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        AssistChip(
                            onClick = {
                                if (accounts.isNotEmpty()) {
                                    accountMenuExpanded = true
                                } else {
                                    onNavigateToSettings()
                                }
                            },
                            label = {
                                Text(
                                    text = activeAccount?.displayName ?: "No Account",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (activeAccount != null) ColabOrange else Color.Gray)
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
                                            Text(
                                                text = acc.displayName,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = acc.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
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
                onClick = {
                    if (accounts.isEmpty()) {
                        onNavigateToSettings()
                    } else {
                        showAddDialog = true
                    }
                },
                containerColor = ColabOrange,
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add VM")
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Search Bar item spanning full width
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search Colab VMs...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (filteredVms.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_colab_logo),
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = Color.Unspecified
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (accounts.isEmpty()) "No Colab Account Connected" else "No Active VMs",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (accounts.isEmpty())
                                    "Sign in to a Google / Colab account in Settings to start spinning up Alpine VMs."
                                else
                                    "Tap '+ Spin Up VM' below to spin up a minimal Alpine Linux instance with Colab CLI & resilient SSH.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    if (accounts.isEmpty()) onNavigateToSettings() else showAddDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
                            ) {
                                Text(if (accounts.isEmpty()) "Sign in Account" else "Spin Up First VM")
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
fun VMGridCard(
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
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row with Status & Title
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = vm.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = account?.displayName ?: "No Account",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (vm.status == VMStatus.RUNNING) "Stop VM" else "Start VM") },
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

            Spacer(modifier = Modifier.height(8.dp))

            // Specs badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = vm.hardwareAccelerator.label,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )

                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = vm.sshConfig.resilientProtocol.name,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenSSH,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("SSH", fontSize = 11.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenFiles,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Files", fontSize = 11.sp, maxLines = 1)
                }

                Button(
                    onClick = onOpenDashboard,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ColabOrange),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Dash", fontSize = 11.sp, maxLines = 1)
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
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (status == VMStatus.RUNNING || status == VMStatus.RECONNECTING) alpha else 1f))
    )
}
