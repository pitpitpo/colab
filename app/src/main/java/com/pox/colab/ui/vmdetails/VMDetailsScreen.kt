package com.pox.colab.ui.vmdetails

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository
import com.pox.colab.model.VMStatus
import com.pox.colab.service.SSHBackgroundService
import com.pox.colab.ui.home.StatusIndicatorDot
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VMDetailsScreen(
    vmId: String,
    initialTab: Int = 0,
    repository: VMRepository,
    preferences: AppPreferences,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val vms by repository.vms.collectAsState()
    val vm = vms.find { it.id == vmId }

    var selectedTabIndex by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var showTunnelDialog by remember { mutableStateOf(false) }

    if (vm == null) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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

    val hasEndpoint = remember(vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        (vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost") || !vm.tunnelUrl.isNullOrBlank()
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

    // Hoisted above the pager so the console survives tab switches while the pager itself
    // keeps beyondBoundsPageCount = 0 (no eager connections on off-screen pages).
    val consoleScope = rememberCoroutineScope()
    val sessionHolder = remember(vm.id, vm.sshConfig.host, vm.sshConfig.port, vm.tunnelUrl) {
        ConsoleSessionHolder(
            vm = vm,
            scope = consoleScope,
            onMetricsUpdated = { metrics -> repository.updateMetrics(vm.id, metrics) },
            onStatusChanged = { status ->
                repository.vms.value.find { it.id == vm.id }?.let { current ->
                    repository.updateVM(current.copy(status = status))
                }
            }
        )
    }

    DisposableEffect(sessionHolder) {
        SSHBackgroundService.start(context, vm.name)
        onDispose {
            sessionHolder.close()
            SSHBackgroundService.stop(context)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                            Text(
                                text = if (hasEndpoint) "${vm.sshConfig.host}:${vm.sshConfig.port} • ${vm.sshConfig.resilientProtocol.label}"
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
                    IconButton(onClick = { showTunnelDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Cable,
                            contentDescription = "Configure Tunnel Endpoint",
                            tint = if (hasEndpoint) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            if (vm.status == VMStatus.RUNNING) {
                                repository.stopVM(vm.id)
                            } else {
                                repository.spinUpVM(vm.id)
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
                .navigationBarsPadding()
        ) {
            // Material 3 PrimaryTabRow: 1 Dashboard, 2 Console, 3 File Manager
            val pagerScope = rememberCoroutineScope()
            val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 2), pageCount = { 3 })

            LaunchedEffect(pagerState.currentPage) {
                selectedTabIndex = pagerState.currentPage
            }

            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = {
                        selectedTabIndex = 0
                        pagerScope.launch { pagerState.animateScrollToPage(0) }
                    },
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
                    onClick = {
                        selectedTabIndex = 1
                        pagerScope.launch { pagerState.animateScrollToPage(1) }
                    },
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
                    onClick = {
                        selectedTabIndex = 2
                        pagerScope.launch { pagerState.animateScrollToPage(2) }
                    },
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondBoundsPageCount = 0,
                userScrollEnabled = false
            ) { page ->
                when (page) {
                    0 -> DashboardTab(
                        vm = vm,
                        repository = repository,
                        onConfigureTunnel = { showTunnelDialog = true }
                    )
                    1 -> SSHTab(
                        vm = vm,
                        preferences = preferences,
                        sessionHolder = sessionHolder,
                        onConfigureTunnel = { showTunnelDialog = true }
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
