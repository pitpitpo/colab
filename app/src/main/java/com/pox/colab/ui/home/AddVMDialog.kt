package com.pox.colab.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.TunnelProvider
import com.pox.colab.theme.MonoFontFamily
import com.pox.colab.ui.component.expressive.ExpressiveMorphingChip
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVMDialog(
    accounts: List<ColabAccount>,
    activeAccountId: String?,
    onDismiss: () -> Unit,
    onConnectAccount: () -> Unit = {},
    onConfirm: (
        name: String,
        accountId: String,
        accelerator: HardwareAccelerator,
        tunnelProvider: TunnelProvider,
        protocol: ResilientProtocol,
        password: String,
        tunnelEndpoint: String
    ) -> Unit
) {
    var selectedMode by remember { mutableIntStateOf(0) }

    val generatedSuffix = remember { UUID.randomUUID().toString().replace("-", "").take(12) }
    val defaultName = remember { "colab-vm-${generatedSuffix.take(6)}" }
    val defaultPassword = remember { "colab-$generatedSuffix-root" }

    var vmName by remember { mutableStateOf("") }
    var rootPassword by remember { mutableStateOf("") }
    var tunnelEndpoint by remember { mutableStateOf("") }

    var selectedAccountId by remember(accounts, activeAccountId) {
        mutableStateOf(activeAccountId ?: accounts.firstOrNull()?.id ?: "")
    }

    var selectedAccelerator by remember { mutableStateOf(HardwareAccelerator.T4) }
    var selectedTunnelProvider by remember { mutableStateOf(TunnelProvider.BORE) }
    var selectedProtocol by remember { mutableStateOf(ResilientProtocol.TMUX_SSH) }

    var accountDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = true),
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Column {
                Text(
                    text = "New Colab Instance",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Google Colab VM with Resilient Terminal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (accounts.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Google Account Required",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Google Colab allocates GPU/CPU runtimes exclusively to authenticated Google accounts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    onConnectAccount()
                                },
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connect Google Account", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedMode == 0,
                        onClick = { selectedMode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    ) {
                        Text("Easy", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    SegmentedButton(
                        selected = selectedMode == 1,
                        onClick = { selectedMode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    ) {
                        Text("Advanced", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                AnimatedContent(targetState = selectedMode, label = "modeContent") { mode ->
                    if (mode == 0) {
                        val currentAcc = accounts.find { it.id == selectedAccountId } ?: accounts.firstOrNull()
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "One-Click Auto Generation",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Pre-configured GPU runtime with resilient tmux shell and generated root credentials.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                EasySpecRow("Instance", defaultName, monospace = true)
                                EasySpecRow("Root Pass", defaultPassword, monospace = true, highlight = true)
                                EasySpecRow("Hardware", "NVIDIA T4 Tensor Core")
                                EasySpecRow("Tunnel", "Auto-Configured Broadcast")
                                EasySpecRow("Shell", "tmux Anti-Disconnect SSH")
                                EasySpecRow(
                                    label = "Account",
                                    value = currentAcc?.let { "${it.displayName} • ${it.tier}" } ?: "Not Connected",
                                    isError = currentAcc == null
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedTextField(
                                value = vmName,
                                onValueChange = { vmName = it },
                                label = { Text("VM Instance Name (Optional)") },
                                placeholder = { Text(defaultName) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )

                            OutlinedTextField(
                                value = rootPassword,
                                onValueChange = { rootPassword = it },
                                label = { Text("Root Password (Optional)") },
                                placeholder = { Text(defaultPassword) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )

                            if (accounts.isNotEmpty()) {
                                val currentAcc = accounts.find { it.id == selectedAccountId } ?: accounts.first()
                                Column {
                                    Text(
                                        text = "Colab Account",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    ExposedDropdownMenuBox(
                                        expanded = accountDropdownExpanded,
                                        onExpandedChange = { accountDropdownExpanded = it }
                                    ) {
                                        OutlinedTextField(
                                            value = "${currentAcc.displayName} (${currentAcc.email})",
                                            onValueChange = {},
                                            readOnly = true,
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded)
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(),
                                            shape = RoundedCornerShape(18.dp)
                                        )
                                        ExposedDropdownMenu(
                                            expanded = accountDropdownExpanded,
                                            onDismissRequest = { accountDropdownExpanded = false }
                                        ) {
                                            accounts.forEach { acc ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Column {
                                                            Text(acc.displayName, style = MaterialTheme.typography.titleSmall)
                                                            Text(
                                                                acc.email,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        selectedAccountId = acc.id
                                                        accountDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Hardware Accelerator", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Requests this accelerator from Colab (NONE allocates CPU; GPU availability depends on account quota).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        HardwareAccelerator.NONE,
                                        HardwareAccelerator.T4,
                                        HardwareAccelerator.V100,
                                        HardwareAccelerator.A100
                                    ).forEach { acc ->
                                        val selected = selectedAccelerator == acc
                                        ExpressiveMorphingChip(
                                            selected = selected,
                                            label = acc.name,
                                            onClick = { selectedAccelerator = acc },
                                            leading = if (selected) {
                                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Shell Resilience Protocol", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                ExpressiveMorphingChip(
                                    selected = selectedProtocol == ResilientProtocol.TMUX_SSH,
                                    label = "tmux Anti-Disconnect SSH",
                                    onClick = { selectedProtocol = ResilientProtocol.TMUX_SSH },
                                    leading = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Tunnel Provider", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(TunnelProvider.CLOUDFLARE, TunnelProvider.BORE).forEach { tun ->
                                        val selected = selectedTunnelProvider == tun
                                        ExpressiveMorphingChip(
                                            selected = selected,
                                            label = tun.label,
                                            onClick = { selectedTunnelProvider = tun },
                                            leading = if (selected) {
                                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = tunnelEndpoint,
                                onValueChange = { tunnelEndpoint = it },
                                label = { Text("Manual Tunnel Override (Optional)") },
                                placeholder = { Text("Leave blank for auto-discovery") },
                                supportingText = {
                                    Text("Only set this when connecting to a static IP or custom tunnel host.")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = vmName.trim().ifEmpty { defaultName }
                    val finalPassword = rootPassword.trim().ifEmpty { defaultPassword }
                    val finalAccountId = selectedAccountId.ifEmpty { accounts.firstOrNull()?.id ?: "" }
                    if (finalAccountId.isBlank()) {
                        return@Button
                    }

                    onConfirm(
                        finalName,
                        finalAccountId,
                        if (selectedMode == 0) HardwareAccelerator.T4 else selectedAccelerator,
                        if (selectedMode == 0) TunnelProvider.CLOUDFLARE else selectedTunnelProvider,
                        if (selectedMode == 0) ResilientProtocol.TMUX_SSH else selectedProtocol,
                        finalPassword,
                        if (selectedMode == 0) "" else tunnelEndpoint.trim()
                    )
                },
                enabled = accounts.isNotEmpty(),
                shape = CircleShape,
                modifier = Modifier.testTag("add_vm_confirm_button")
            ) {
                Text(
                    text = if (selectedMode == 0) "Generate VM" else "Create VM",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EasySpecRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    highlight: Boolean = false,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily)
            } else {
                MaterialTheme.typography.bodySmall
            },
            fontWeight = if (highlight || isError) FontWeight.Bold else FontWeight.Medium,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                highlight -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
