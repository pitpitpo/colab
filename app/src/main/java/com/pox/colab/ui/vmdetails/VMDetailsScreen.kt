package com.pox.colab.ui.vmdetails

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository
import com.pox.colab.model.VMStatus
import com.pox.colab.service.SSHBackgroundService
import com.pox.colab.theme.MonoFontFamily
import com.pox.colab.ui.component.expressive.ExpressiveFloatingTabBar
import com.pox.colab.ui.component.expressive.ExpressiveTabItem
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
    val vms by repository.vms.collectAsStateWithLifecycle()
    val vm = remember(vms, vmId) { vms.find { it.id == vmId } }

    var selectedTabIndex by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var showTunnelDialog by remember { mutableStateOf(false) }

    if (vm == null) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "VM Instance",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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
        VMRepository.isValidTunnelEndpoint(vm.tunnelUrl) ||
            (vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost" && VMRepository.isValidTunnelEndpoint(vm.sshConfig.host))
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

    val consoleScope = rememberCoroutineScope()
    val sessionHolder = remember(vm.id) {
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        SSHBackgroundService.start(context, vm.name)
    }

    LaunchedEffect(vm.id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (hasEndpoint) {
                                    "${vm.sshConfig.host}:${vm.sshConfig.port}"
                                } else {
                                    "Auto Tunnel • ${vm.hardwareAccelerator.label}"
                                },
                                style = if (hasEndpoint) {
                                    MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily)
                                } else {
                                    MaterialTheme.typography.labelSmall
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onNavigateBack,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate Back")
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { showTunnelDialog = true },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (hasEndpoint) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (hasEndpoint) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cable,
                            contentDescription = "Configure Tunnel Endpoint",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    FilledTonalIconButton(
                        onClick = {
                            if (vm.status == VMStatus.RUNNING) {
                                repository.stopVM(vm.id)
                            } else {
                                repository.spinUpVM(vm.id)
                            }
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (vm.status == VMStatus.RUNNING) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            contentColor = if (vm.status == VMStatus.RUNNING) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (vm.status == VMStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (vm.status == VMStatus.RUNNING) "Stop instance" else "Start instance",
                            modifier = Modifier.size(20.dp)
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
            val pagerScope = rememberCoroutineScope()
            val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 2), pageCount = { 3 })

            LaunchedEffect(pagerState.currentPage) {
                selectedTabIndex = pagerState.currentPage
            }

            val tabs = remember {
                listOf(
                    ExpressiveTabItem("Dashboard", Icons.Filled.Speed, Icons.Outlined.Speed),
                    ExpressiveTabItem("Console", Icons.Filled.Terminal, Icons.Outlined.Terminal),
                    ExpressiveTabItem("Files", Icons.Filled.Folder, Icons.Outlined.Folder)
                )
            }

            Surface(color = MaterialTheme.colorScheme.surface) {
                ExpressiveFloatingTabBar(
                    tabs = tabs,
                    selectedIndex = selectedTabIndex,
                    onSelect = { index ->
                        selectedTabIndex = index
                        pagerScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                        onConfigureTunnel = { showTunnelDialog = true },
                        onOpenConsole = {
                            selectedTabIndex = 1
                            pagerScope.launch { pagerState.animateScrollToPage(1) }
                        }
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
