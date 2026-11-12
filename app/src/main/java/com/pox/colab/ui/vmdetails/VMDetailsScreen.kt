package com.pox.colab.ui.vmdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.ColabOrange
import com.pox.colab.ui.home.StatusIndicatorDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMDetailsScreen(
    vmId: String,
    initialTab: Int = 0,
    repository: VMRepository,
    preferences: AppPreferences,
    onNavigateBack: () -> Unit
) {
    val vms by repository.vms.collectAsState()
    val vm = vms.find { it.id == vmId }

    var selectedTabIndex by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var showTunnelDialog by remember { mutableStateOf(false) }

    if (vm == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("VM Instance", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "This VM instance was not found or has been removed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    if (showTunnelDialog) {
        ConfigureTunnelDialog(
            vm = vm,
            onDismiss = { showTunnelDialog = false },
            onSave = { newEndpoint ->
                repository.updateTunnelEndpoint(vm.id, newEndpoint)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        StatusIndicatorDot(status = vm.status)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = vm.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val hasHost = vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost" || !vm.tunnelUrl.isNullOrBlank()
                            Text(
                                text = if (hasHost) "${vm.sshConfig.host}:${vm.sshConfig.port} • ${vm.sshConfig.resilientProtocol.label}"
                                       else "Unconfigured Tunnel • ${vm.hardwareAccelerator.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate Back")
                    }
                },
                actions = {
                    // Quick Tunnel Config
                    IconButton(onClick = { showTunnelDialog = true }) {
                        val hasHost = vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost" || !vm.tunnelUrl.isNullOrBlank()
                        Icon(
                            imageVector = Icons.Default.Cable,
                            contentDescription = "Configure Tunnel Endpoint",
                            tint = if (hasHost) ColabOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Power toggle
                    IconButton(
                        onClick = {
                            if (vm.status == VMStatus.RUNNING) {
                                repository.stopVM(vm.id)
                            } else {
                                repository.spinUpAlpineVM(vm.id)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (vm.status == VMStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (vm.status == VMStatus.RUNNING) "Stop instance" else "Start instance",
                            tint = if (vm.status == VMStatus.RUNNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Material 3 PrimaryTabRow for peer views inside the single VM destination
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Dashboard", maxLines = 1, fontSize = 13.sp) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Dashboard",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Console", maxLines = 1, fontSize = 13.sp) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Console",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("File Manager", maxLines = 1, fontSize = 13.sp) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "File Manager",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> DashboardTab(
                        vm = vm,
                        repository = repository,
                        onConfigureTunnel = { showTunnelDialog = true }
                    )
                    1 -> SSHTab(
                        vm = vm,
                        preferences = preferences,
                        onConfigureTunnel = { showTunnelDialog = true },
                        onMetricsUpdated = { metrics ->
                            repository.updateMetrics(vm.id, metrics)
                        }
                    )
                    2 -> FilesTab(
                        vm = vm,
                        onConfigureTunnel = { showTunnelDialog = true }
                    )
                }
            }
        }
    }
}
