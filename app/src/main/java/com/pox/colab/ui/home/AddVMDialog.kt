package com.pox.colab.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.TunnelProvider
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

    val generatedSuffix = remember { UUID.randomUUID().toString().replace("-", "").take(12) }
    val defaultName = remember { "colab-alpine-${generatedSuffix.take(6)}" }
    val defaultPassword = remember { "colab-$generatedSuffix-root" }

    var vmName by remember { mutableStateOf("") }
    var rootPassword by remember { mutableStateOf("") }
    var tunnelEndpoint by remember { mutableStateOf("") }

    var selectedAccountId by remember(accounts, activeAccountId) {
        mutableStateOf(activeAccountId ?: accounts.firstOrNull()?.id ?: "")
    }

    var selectedAccelerator by remember { mutableStateOf(HardwareAccelerator.T4) }
    var selectedTunnelProvider by remember { mutableStateOf(TunnelProvider.CLOUDFLARE) }
    var selectedProtocol by remember { mutableStateOf(ResilientProtocol.TMUX_SSH) }

    var accountDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = true),
        title = {
            Column {
                Text(
                    text = "New Alpine Instance",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
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
                // Segmented Button: Easy Mode vs Advanced Mode
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedMode == 0,
                        onClick = { selectedMode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    ) {
                        Text("Easy Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    SegmentedButton(
                        selected = selectedMode == 1,
                        onClick = { selectedMode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    ) {
                        Text("Advanced Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                AnimatedContent(targetState = selectedMode, label = "modeContent") { mode ->
                    if (mode == 0) {
                        // Easy Mode Card
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("One-Click Auto Generation", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Automatically provisions an optimal Alpine container environment with NVIDIA T4 GPU, resilient tmux shell, and pre-generated secure root credentials.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Instance: $defaultName", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text("Root Password: $defaultPassword", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Text("Hardware: NVIDIA T4 Tensor Core", fontSize = 12.sp)
                                Text("Tunnel: Cloudflare Quick Tunnel", fontSize = 12.sp)
                                Text("Shell: tmux Anti-Disconnect SSH", fontSize = 12.sp)
                                Text(
                                    text = "Account: ${accounts.find { it.id == selectedAccountId }?.displayName ?: "Standalone (no linked account)"}",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        // Advanced Mode form: auto-fills defaults if left empty
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedTextField(
                                value = vmName,
                                onValueChange = { vmName = it },
                                label = { Text("VM Instance Name (Optional)") },
                                placeholder = { Text(defaultName) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            OutlinedTextField(
                                value = rootPassword,
                                onValueChange = { rootPassword = it },
                                label = { Text("Root Password (Optional)") },
                                placeholder = { Text(defaultPassword) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            // Account selector in Advanced Mode
                            if (accounts.isNotEmpty()) {
                                val currentAcc = accounts.find { it.id == selectedAccountId } ?: accounts.first()
                                Column {
                                    Text("Colab Account", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    ExposedDropdownMenuBox(
                                        expanded = accountDropdownExpanded,
                                        onExpandedChange = { accountDropdownExpanded = it }
                                    ) {
                                        OutlinedTextField(
                                            value = "${currentAcc.displayName} (${currentAcc.email})",
                                            onValueChange = {},
                                            readOnly = true,
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        ExposedDropdownMenu(
                                            expanded = accountDropdownExpanded,
                                            onDismissRequest = { accountDropdownExpanded = false }
                                        ) {
                                            accounts.forEach { acc ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Column {
                                                            Text(acc.displayName, fontWeight = FontWeight.SemiBold)
                                                            Text(acc.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                            // Hardware Accelerator selector chips
                            Text("Hardware Accelerator", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(HardwareAccelerator.NONE, HardwareAccelerator.T4, HardwareAccelerator.V100, HardwareAccelerator.A100).forEach { acc ->
                                    FilterChip(
                                        selected = selectedAccelerator == acc,
                                        onClick = { selectedAccelerator = acc },
                                        label = { Text(acc.name, fontSize = 11.sp) }
                                    )
                                }
                            }

                            // Resilience Protocol selector chips
                            Text("Shell Resilience Protocol", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(ResilientProtocol.TMUX_SSH, ResilientProtocol.MOSH_UDP).forEach { proto ->
                                    FilterChip(
                                        selected = selectedProtocol == proto,
                                        onClick = { selectedProtocol = proto },
                                        label = { Text(if (proto == ResilientProtocol.TMUX_SSH) "tmux SSH" else "Mosh UDP", fontSize = 11.sp) }
                                    )
                                }
                            }

                            // Tunnel Provider selector chips
                            Text("Tunnel Provider", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(TunnelProvider.CLOUDFLARE, TunnelProvider.BORE, TunnelProvider.NGROK).forEach { tun ->
                                    FilterChip(
                                        selected = selectedTunnelProvider == tun,
                                        onClick = { selectedTunnelProvider = tun },
                                        label = { Text(tun.name, fontSize = 11.sp) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = tunnelEndpoint,
                                onValueChange = { tunnelEndpoint = it },
                                label = { Text("Preconfigured Tunnel Endpoint (Optional)") },
                                placeholder = { Text("e.g. bore.pub:42195") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
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
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (selectedMode == 0) "Generate VM" else "Create VM", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
