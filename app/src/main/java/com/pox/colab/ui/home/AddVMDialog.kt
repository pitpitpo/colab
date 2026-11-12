package com.pox.colab.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.TunnelProvider
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.TerminalGreen
import java.util.UUID

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
    // Mode selection: 0 = Easy Mode (instant auto-generate), 1 = Advanced Mode (customizable, auto-fills if empty)
    var selectedMode by remember { mutableIntStateOf(0) }

    // Auto-generated defaults
    val generatedSuffix = remember { UUID.randomUUID().toString().take(4) }
    val defaultName = remember { "colab-alpine-$generatedSuffix" }
    val defaultPassword = remember { "colab-$generatedSuffix-root" }

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
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Icon(
                        imageVector = if (selectedMode == 0) Icons.Default.AutoAwesome else Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Column {
                    Text(
                        text = "New VM Instance",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedMode == 0) "Easy Mode (Auto-Generated)" else "Advanced Configuration",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Segmented Button Row: Easy Mode vs Advanced Mode
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = selectedMode == 0,
                        onClick = { selectedMode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text("Easy Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    SegmentedButton(
                        selected = selectedMode == 1,
                        onClick = { selectedMode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text("Advanced", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                AnimatedContent(
                    targetState = selectedMode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ModeTransition"
                ) { mode ->
                    if (mode == 0) {
                        // EASY MODE: 1-click generation, auto-fills everything
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Autonomous Setup",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "One-tap provisioning generates a complete Alpine Linux container with official Colab CLI, T4 GPU accelerator, resilient Tmux session, and secure auto-generated credentials.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Auto-generated parameters preview
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Instance Name:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(defaultName, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Accelerator:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("NVIDIA T4 GPU", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TerminalGreen)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Tunnel Provider:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Cloudflare Quick Tunnel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Resilience:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Tmux State Sync (Never-Die)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        // ADVANCED MODE: user fills in data, and if empty it automatically fills itself
                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Customize parameters below. Any field left blank will automatically self-populate with secure defaults.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Name
                            OutlinedTextField(
                                value = vmName,
                                onValueChange = { vmName = it },
                                label = { Text("VM Name (optional)") },
                                placeholder = { Text(defaultName) },
                                supportingText = { Text("Leave blank to auto-generate: $defaultName") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Root Password
                            OutlinedTextField(
                                value = rootPassword,
                                onValueChange = { rootPassword = it },
                                label = { Text("SSH Root Password (optional)") },
                                placeholder = { Text("Auto-generated if blank") },
                                supportingText = { Text("Leave blank to generate random password") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )

                            // Tunnel Host / Port
                            OutlinedTextField(
                                value = tunnelEndpoint,
                                onValueChange = { tunnelEndpoint = it },
                                label = { Text("Tunnel Endpoint (Optional)") },
                                placeholder = { Text("e.g. bore.pub:38491 or trycloudflare.com") },
                                supportingText = { Text("Can be entered now or configured in VM details.") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Cable, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                            }

                            // Accelerator Selection
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Hardware Accelerator", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                HardwareAccelerator.values().forEach { accel ->
                                    FilterChip(
                                        selected = selectedAccelerator == accel,
                                        onClick = { selectedAccelerator = accel },
                                        label = { Text(accel.label, fontSize = 12.sp) },
                                        modifier = Modifier.fillMaxWidth()
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
                                        modifier = Modifier.fillMaxWidth()
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
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (selectedMode == 0 || vmName.isBlank()) defaultName else vmName.trim()
                    val finalPassword = if (selectedMode == 0 || rootPassword.isBlank()) defaultPassword else rootPassword.trim()
                    val effectiveAccountId = selectedAccountId.ifBlank {
                        accounts.firstOrNull()?.id ?: "local-session"
                    }
                    val finalAccelerator = if (selectedMode == 0) HardwareAccelerator.T4_GPU else selectedAccelerator
                    val finalTunnel = if (selectedMode == 0) TunnelProvider.CLOUDFLARE else selectedTunnel
                    val finalProtocol = if (selectedMode == 0) ResilientProtocol.TMUX_SSH else selectedProtocol

                    onConfirm(
                        finalName,
                        effectiveAccountId,
                        finalAccelerator,
                        finalTunnel,
                        finalProtocol,
                        finalPassword,
                        tunnelEndpoint.trim()
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (selectedMode == 0) Icons.Default.AutoAwesome else Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (selectedMode == 0) "Instant Generate" else "Create Instance", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
