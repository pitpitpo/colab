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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onNavigateBack: () -> Unit
) {
    val vms by repository.vms.collectAsState()
    val vm = vms.find { it.id == vmId }

    var selectedTabIndex by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    if (vm == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("VM Not Found", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                Text("This Colab VM does not exist or was removed.")
            }
        }
        return
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = vm.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${vm.hardwareAccelerator.label} • ${vm.sshConfig.resilientProtocol.name}",
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = if (vm.status == VMStatus.RUNNING) ColabOrange else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = "Dashboard") },
                    label = { Text("Dashboard", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ColabOrange,
                        selectedTextColor = ColabOrange,
                        indicatorColor = ColabOrange.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "SSH") },
                    label = { Text("SSH", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ColabOrange,
                        selectedTextColor = ColabOrange,
                        indicatorColor = ColabOrange.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                    label = { Text("Files", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ColabOrange,
                        selectedTextColor = ColabOrange,
                        indicatorColor = ColabOrange.copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTabIndex) {
                0 -> DashboardTab(vm = vm, repository = repository)
                1 -> SSHTab(vm = vm)
                2 -> FilesTab(vm = vm)
            }
        }
    }
}
