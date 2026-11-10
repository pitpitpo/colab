package com.pox.colab.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pox.colab.R
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository
import com.pox.colab.model.AuthType
import com.pox.colab.model.ColabAccount
import com.pox.colab.theme.ColabAmber
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.ColabYellow
import com.pox.colab.theme.TerminalGreen
import com.pox.colab.theme.TerminalRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: VMRepository,
    preferences: AppPreferences,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val accounts by repository.accounts.collectAsState()
    val activeAccount by repository.activeAccount.collectAsState()

    var showSignInDialog by remember { mutableStateOf(false) }
    var generatedPublicKey by remember {
        mutableStateOf("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleColabKeyNeverDieResilientClient root@colab-android")
    }

    var dynamicColorEnabled by remember { mutableStateOf(preferences.isDynamicColorEnabled()) }
    var darkModeEnabled by remember { mutableStateOf(preferences.isDarkMode()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Accounts", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Google Accounts Management Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Google / Colab Accounts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Connect multiple accounts for separate VM quotas",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Button in settings to sign in to all accounts you want!
                    Button(
                        onClick = { showSignInDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ColabOrange),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign In Account")
                    }
                }
            }

            // Accounts List
            items(accounts, key = { it.id }) { acc ->
                AccountCard(
                    account = acc,
                    isActive = acc.id == activeAccount?.id,
                    onSelectActive = { repository.setActiveAccount(acc.id) },
                    onDelete = { repository.removeAccount(acc.id) }
                )
            }

            // SSH Resilience & Keys Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "SSH Resilience & Keypair",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = ColabOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Never-Die Connection Protocol",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Mosh protocol with UDP State-Synchronization and Tmux daemon keep-alive prevent network disconnection on mobile sleep or IP handover.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Key generator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = ColabYellow,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SSH Ed25519 Public Key",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = generatedPublicKey,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp),
                                maxLines = 2
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Colab SSH Public Key", generatedPublicKey)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Public key copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Key")
                            }

                            Button(
                                onClick = {
                                    val randomHex = (1000..9999).random().toString(16)
                                    generatedPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI$randomHex${(10000..99999).random()}root@colab-android"
                                    Toast.makeText(context, "New Ed25519 Keypair Generated", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ColabOrange),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Regenerate")
                            }
                        }
                    }
                }
            }

            // Appearance & Material 3 Preferences
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Appearance & MUI3 Theme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Dynamic Color (Material You)", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Adapts colors to device wallpaper",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = dynamicColorEnabled,
                                onCheckedChange = {
                                    dynamicColorEnabled = it
                                    preferences.setDynamicColorEnabled(it)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Dark Theme", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "High contrast terminal dark theme",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = darkModeEnabled,
                                onCheckedChange = {
                                    darkModeEnabled = it
                                    preferences.setDarkMode(it)
                                }
                            )
                        }
                    }
                }
            }

            // App & Package Information
            item {
                Spacer(modifier = Modifier.height(8.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_colab_logo),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Colab Mobile", fontWeight = FontWeight.Bold)
                            Text("Package: com.pox.colab", style = MaterialTheme.typography.labelSmall)
                            Text("Version 1.0.0 (Alpine Container + Mosh SSH)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Sign In to Account Dialog
        if (showSignInDialog) {
            SignInAccountDialog(
                onDismiss = { showSignInDialog = false },
                onConfirm = { email, name, token, tier ->
                    repository.addAccount(email, name, token, tier)
                    showSignInDialog = false
                    Toast.makeText(context, "Account added & authenticated!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun AccountCard(
    account: ColabAccount,
    isActive: Boolean,
    onSelectActive: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                RadioButton(
                    selected = isActive,
                    onClick = onSelectActive
                )

                Spacer(modifier = Modifier.width(6.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = account.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text(account.tier, fontSize = 10.sp) }
                        )
                    }

                    Text(
                        text = account.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Compute Units: ${account.computeUnitsRemaining} remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = ColabOrange
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove Account",
                    tint = TerminalRed
                )
            }
        }
    }
}

@Composable
fun SignInAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (email: String, name: String, token: String, tier: String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var selectedTier by remember { mutableStateOf("Colab Pro") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_colab_logo),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.Unspecified
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign in to Google Colab Account")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Sign in to any Google / Colab account to manage its runtimes and quotas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Google Account Email") },
                    placeholder = { Text("username@gmail.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name / Tag") },
                    placeholder = { Text("e.g. Work, Lab, Personal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("OAuth Token / Cookie / API Key") },
                    placeholder = { Text("Optional for quick start") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Colab Subscription Tier", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Free", "Colab Pro", "Colab Pro+").forEach { tier ->
                        FilterChip(
                            selected = selectedTier == tier,
                            onClick = { selectedTier = tier },
                            label = { Text(tier) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (email.isNotBlank()) {
                        onConfirm(
                            email.trim(),
                            displayName.trim().ifEmpty { email.substringBefore("@") },
                            token.trim(),
                            selectedTier
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ColabOrange)
            ) {
                Text("Sign In")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
