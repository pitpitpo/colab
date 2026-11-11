package com.pox.colab.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        protocol: ResilientProtocol,
        password: String,
        tunnelEndpoint: String
    ) -> Unit
) {
    var vmName by remember { mutableStateOf("") }
    var rootPassword by remember { mutableStateOf("") }
    var tunnelEndpoint by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf(activeAccountId ?: accounts.firstOrNull()?.id ?: "") }
    var selectedAccelerator by remember { mutableStateOf(HardwareAccelerator.T4_GPU) }
    var selectedTunnel by remember { mutableStateOf(TunnelProvider.CLOUDFLARE) }
    var selectedProtocol by remember { mutableStateOf(ResilientProtocol.TMUX_SSH) }

    var accountDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ColabOrange.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = ColabOrange,
                        modifier = Modifier.padding(6.dp)
                    )
                }
                Text(
                    text = "New Alpine Instance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Name
                OutlinedTextField(
                    value = vmName,
                    onValueChange = { vmName = it },
                    label = { Text("VM Name") },
                    placeholder = { Text("e.g. pytorch-training-01") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Root Password
                OutlinedTextField(
                    value = rootPassword,
                    onValueChange = { rootPassword = it },
                    label = { Text("SSH Root Password") },
                    placeholder = { Text("Enter custom root password") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Key, contentDescription = null, tint = ColabOrange)
                    }
                )

                // Tunnel Host / Port
                OutlinedTextField(
                    value = tunnelEndpoint,
                    onValueChange = { tunnelEndpoint = it },
                    label = { Text("Tunnel Endpoint (Optional)") },
                    placeholder = { Text("e.g. bore.pub:38491 or trycloudflare.com") },
                    supportingText = { Text("Can be entered now or configured anytime in VM details.") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Cable, contentDescription = null, tint = ColabOrange)
                    }
                )

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
                            label = { Text("Associated Colab Account") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp),
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
                                    text = {
                                        Column {
                                            Text(acc.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(acc.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = ColabOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Local / Default Session",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Google Account can be authenticated anytime in Settings",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Accelerator Selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Hardware Accelerator", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    HardwareAccelerator.values().forEach { accel ->
                        FilterChip(
                            selected = selectedAccelerator == accel,
                            onClick = { selectedAccelerator = accel },
                            label = { Text(accel.label, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ColabOrange.copy(alpha = 0.15f),
                                selectedLabelColor = ColabOrange
                            )
                        )
                    }
                }

                // Tunnel Provider Selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tunnel Provider", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    TunnelProvider.values().forEach { tunnel ->
                        FilterChip(
                            selected = selectedTunnel == tunnel,
                            onClick = { selectedTunnel = tunnel },
                            label = { Text(tunnel.label, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ColabOrange.copy(alpha = 0.15f),
                                selectedLabelColor = ColabOrange
                            )
                        )
                    }
                }

                // Resilient SSH Protocol
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connection Protocol", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    ResilientProtocol.values().forEach { proto ->
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = { selectedProtocol = proto },
                            label = {
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(proto.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(proto.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ColabOrange.copy(alpha = 0.15f),
                                selectedLabelColor = ColabOrange
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (vmName.isNotBlank() && rootPassword.isNotBlank()) {
                        val effectiveAccountId = selectedAccountId.ifBlank {
                            accounts.firstOrNull()?.id ?: "local-session"
                        }
                        onConfirm(
                            vmName.trim(),
                            effectiveAccountId,
                            selectedAccelerator,
                            selectedTunnel,
                            selectedProtocol,
                            rootPassword.trim(),
                            tunnelEndpoint.trim()
                        )
                    }
                },
                enabled = vmName.isNotBlank() && rootPassword.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
            ) {
                Text("Generate Instance")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
