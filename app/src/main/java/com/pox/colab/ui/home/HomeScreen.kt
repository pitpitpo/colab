package com.pox.colab.ui.home

import android.animation.ValueAnimator
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pox.colab.R
import com.pox.colab.data.VMRepository
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.VMStatus
import com.pox.colab.theme.MonoFontFamily
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.theme.vmStatusColor
import com.pox.colab.theme.vmStatusLabel
import com.pox.colab.ui.component.expressive.ExpressiveShapes
import com.pox.colab.ui.component.expressive.ExpressiveWavyProgressBar
import com.pox.colab.ui.component.shimmer.GridItemPlaceholder
import com.pox.colab.ui.settings.SignInAccountDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: VMRepository,
    onNavigateToVM: (vmId: String, tabIndex: Int) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val vms by repository.vms.collectAsStateWithLifecycle()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val activeAccount by repository.activeAccount.collectAsStateWithLifecycle()
    val isLoading by repository.isLoading.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val accountsById = remember(accounts) { accounts.associateBy { it.id } }
    val filteredVms = remember(vms, searchQuery) {
        if (searchQuery.isBlank()) vms
        else vms.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = ExpressiveShapes.clover(),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_colab_logo),
                                    contentDescription = "Colab Logo",
                                    modifier = Modifier.size(22.dp),
                                    tint = Color.Unspecified
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Colab",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    Box(modifier = Modifier.padding(end = 12.dp)) {
                        Surface(
                            onClick = {
                                if (accounts.isEmpty()) {
                                    showSignInDialog = true
                                } else {
                                    accountMenuExpanded = true
                                }
                            },
                            shape = CircleShape,
                            color = if (activeAccount != null) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("home_account_pill")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Icon(
                                    imageVector = if (activeAccount != null) Icons.Default.Person else Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (activeAccount != null) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = activeAccount?.displayName ?: "Sign In",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (activeAccount != null) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp)
                                )
                            }
                        }

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
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = acc.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = if (acc.id == activeAccount?.id) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    },
                                    onClick = {
                                        repository.setActiveAccount(acc.id)
                                        accountMenuExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Manage Accounts",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    accountMenuExpanded = false
                                    onNavigateToSettings()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (accounts.isEmpty()) {
                        Toast.makeText(
                            context,
                            "Google Account required to provision Colab instances. Please sign in.",
                            Toast.LENGTH_LONG
                        ).show()
                        showSignInDialog = true
                    } else {
                        showAddDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(22.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 84.dp)
                    .testTag("new_vm_fab"),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New VM",
                        modifier = Modifier.size(24.dp)
                    )
                },
                text = {
                    Text(
                        text = "New VM",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (vms.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Search instances",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_search_input"),
                        singleLine = true,
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedBorderColor = Color.Transparent,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = ExpressiveShapes.clover(),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(30.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Instances Found",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "No instances match \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            FilledTonalButton(
                                onClick = { searchQuery = "" },
                                shape = CircleShape
                            ) {
                                Text("Clear Search", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                items(filteredVms, key = { it.id }) { vm ->
                    val account = accountsById[vm.accountId]
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
    val primaryGlow = MaterialTheme.colorScheme.primaryContainer
    val tertiaryGlow = MaterialTheme.colorScheme.tertiaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(primaryGlow.copy(alpha = 0.28f), Color.Transparent),
                                center = Offset(size.width * 0.2f, size.height * 0.12f),
                                radius = size.width * 0.65f
                            )
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(tertiaryGlow.copy(alpha = 0.22f), Color.Transparent),
                                center = Offset(size.width * 0.85f, size.height * 0.88f),
                                radius = size.width * 0.55f
                            )
                        )
                    }
                    .padding(horizontal = 28.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = ExpressiveShapes.scallop(lobes = 10, depth = 0.085f),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (hasAccounts) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_colab_logo),
                                contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = Color.Unspecified
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))

                Text(
                    text = if (hasAccounts) "Launch Your First VM" else "Connect Google Account",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (hasAccounts) {
                        "Ready for ${activeAccount?.displayName ?: "your account"}. Spin up a GPU/TPU Colab node with resilient tmux SSH and SFTP file access."
                    } else {
                        "Google Colab allocates GPU/TPU runtimes exclusively to authenticated Google accounts. Connect via PKCE OAuth2 to start provisioning instances."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = if (hasAccounts) onPrimaryAction else onConnectAccount,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                    modifier = Modifier.testTag("empty_state_primary_button")
                ) {
                    Icon(
                        imageVector = if (hasAccounts) Icons.Default.Add else Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (hasAccounts) "New Colab Instance" else "Connect Google Account",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
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
    val statusColor = vmStatusColor(vm.status)
    val runningAccent = MaterialTheme.colorScheme.primaryContainer

    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vm_card_${vm.id}"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (vm.status == VMStatus.RUNNING) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(runningAccent.copy(alpha = 0.22f), Color.Transparent),
                                center = Offset(size.width * 0.9f, 0f),
                                radius = size.width * 0.55f
                            )
                        )
                    }
                }
                .padding(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.16f)
                    ) {
                        StatusIndicatorDot(
                            status = vm.status,
                            showLabel = true,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = vm.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val hasTunnel = remember(vm.tunnelUrl, vm.sshConfig.host) {
                        VMRepository.isValidTunnelEndpoint(vm.tunnelUrl) ||
                            (vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost" && VMRepository.isValidTunnelEndpoint(vm.sshConfig.host))
                    }
                    Text(
                        text = if (hasTunnel) {
                            "${vm.sshConfig.host}:${vm.sshConfig.port}"
                        } else {
                            account?.let { "${it.displayName} • ${it.tier}" } ?: "Account Disconnected"
                        },
                        style = if (hasTunnel) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (account != null || hasTunnel) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Instance options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (vm.status == VMStatus.RUNNING) "Stop Instance" else "Start Instance") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (vm.status == VMStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onTogglePower()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete Instance", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeveloperBoard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = vm.hardwareAccelerator.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = statusSuccessColor()
                        )
                        Text(
                            text = vm.sshConfig.resilientProtocol.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }

            if (vm.status == VMStatus.RUNNING && vm.metrics.cpuUsagePercent > 0f) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "CPU ${vm.metrics.cpuUsagePercent.toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ExpressiveWavyProgressBar(
                        progress = (vm.metrics.cpuUsagePercent / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenSSH,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vm_card_terminal_button"),
                    shape = ExpressiveShapes.connectedStart,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Terminal",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                FilledTonalButton(
                    onClick = onOpenFiles,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vm_card_files_button"),
                    shape = ExpressiveShapes.connectedEnd,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Files",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
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
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
