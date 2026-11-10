package com.pox.colab.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.TunnelProvider
import com.pox.colab.theme.ColabOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVMDialog(
    accounts: List<ColabAccount>,
    activeAccountId: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        accountId: String,
        accelerator: HardwareAccelerator,
        tunnelProvider: TunnelProvider,
        protocol: ResilientProtocol
    ) -> Unit
) {
    var vmName by remember { mutableStateOf("alpine-vm-" + (10..99).random()) }
    var selectedAccountId by remember { mutableStateOf(activeAccountId ?: accounts.firstOrNull()?.id ?: "") }
    var selectedAccelerator by remember { mutableStateOf(HardwareAccelerator.T4_GPU) }
    var selectedTunnel by remember { mutableStateOf(TunnelProvider.CLOUDFLARE) }
    var selectedProtocol by remember { mutableStateOf(ResilientProtocol.MOSH) }

    var accountDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = ColabOrange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Spin Up Alpine Colab VM",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Configures a minimal Alpine Linux container inside Google Colab runtime with resilient SSH/Mosh and file manager.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // VM Name
                OutlinedTextField(
                    value = vmName,
                    onValueChange = { vmName = it },
                    label = { Text("VM Instance Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Account Selection
                if (accounts.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = accountDropdownExpanded,
                        onExpandedChange = { accountDropdownExpanded = !accountDropdownExpanded }
                    ) {
                        val currentAccount = accounts.find { it.id == selectedAccountId } ?: accounts.first()
                        OutlinedTextField(
                            value = "${currentAccount.displayName} (${currentAccount.email})",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Colab Account") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = accountDropdownExpanded,
                            onDismissRequest = { accountDropdownExpanded = false }
                        ) {
                            accounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text("${acc.displayName} (${acc.email}) - ${acc.tier}") },
                                    onClick = {
                                        selectedAccountId = acc.id
                                        accountDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hardware Accelerator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DeveloperBoard,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Hardware Accelerator", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HardwareAccelerator.values().forEach { acc ->
                        FilterChip(
                            selected = selectedAccelerator == acc,
                            onClick = { selectedAccelerator = acc },
                            label = { Text(acc.label) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tunnel Provider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Tunnel Provider", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TunnelProvider.values().forEach { provider ->
                    FilterChip(
                        selected = selectedTunnel == provider,
                        onClick = { selectedTunnel = provider },
                        label = { Text(provider.label) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Resilient SSH Protocol
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                        tint = ColabOrange
                    )
                    Text("Resilient SSH (Never Dies)", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(8.dp))
                ResilientProtocol.values().forEach { proto ->
                    FilterChip(
                        selected = selectedProtocol == proto,
                        onClick = { selectedProtocol = proto },
                        label = {
                            Text(
                                when (proto) {
                                    ResilientProtocol.MOSH -> "Mosh (Mobile Shell - Survives Roaming & Sleep)"
                                    ResilientProtocol.TMUX_SSH -> "Tmux + AutoSSH (Auto-reattach session)"
                                    ResilientProtocol.AUTOSSH -> "AutoSSH Heartbeat Monitor"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (vmName.isNotBlank() && selectedAccountId.isNotBlank()) {
                        onConfirm(
                            vmName.trim(),
                            selectedAccountId,
                            selectedAccelerator,
                            selectedTunnel,
                            selectedProtocol
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
            ) {
                Text("Spin Up VM")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
