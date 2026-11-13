package com.pox.colab.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.theme.ColabOrange
import com.pox.colab.theme.resolveConsoleBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: VMRepository,
    preferences: AppPreferences,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val accounts by repository.accounts.collectAsState()
    val activeAccount by repository.activeAccount.collectAsState()
    val vms by repository.vms.collectAsState()

    var showSignInDialog by remember { mutableStateOf(false) }
    var generatedPublicKey by remember { mutableStateOf(generateRealPublicKey()) }

    // Theme state
    var dynamicColorEnabled by remember { mutableStateOf(preferences.isDynamicColorEnabled()) }
    var darkModeEnabled by remember { mutableStateOf(preferences.isDarkMode()) }
    var pureBlackEnabled by remember { mutableStateOf(preferences.isPureBlack()) }
    var selectedPalette by remember { mutableStateOf(preferences.getSelectedPalette()) }
    var globalConsoleBg by remember { mutableStateOf(preferences.getConsoleBackground()) }

    // App Update state
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var updateCheckError by remember { mutableStateOf<String?>(null) }
    var hasCheckedUpdates by remember { mutableStateOf(false) }

    val currentAppVersion = "v1.0.0"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate Back")
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // =========================================================================
            // HUB 1: Accounts Hub
            // =========================================================================
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Accounts Hub",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Colab CLI OAuth PKCE accounts, compute quotas & tiers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { showSignInDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (accounts.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No Connected Colab Accounts",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap 'Add Account' to authenticate via Alpine Colab CLI PKCE flow.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column {
                                accounts.forEachIndexed { index, acc ->
                                    val isActive = acc.id == activeAccount?.id

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = isActive,
                                                onClick = { repository.setActiveAccount(acc.id) }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = acc.displayName,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (isActive) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                            shape = RoundedCornerShape(6.dp)
                                                        ) {
                                                            Text(
                                                                text = "ACTIVE",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = acc.email,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            // Enable / Disable Account Switch
                                            Switch(
                                                checked = acc.isEnabled,
                                                onCheckedChange = { isChecked ->
                                                    repository.toggleAccountEnabled(acc.id, isChecked)
                                                    Toast.makeText(
                                                        context,
                                                        if (isChecked) "Account enabled" else "Account paused",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            )

                                            Spacer(modifier = Modifier.width(4.dp))

                                            IconButton(
                                                onClick = {
                                                    repository.removeAlpineCliInstance(context, acc.id)
                                                    repository.removeAccount(acc.id)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Remove account",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Tier Selector: Pro vs Free
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 48.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Tier:",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            FilterChip(
                                                selected = acc.tier.equals("Pro", ignoreCase = true),
                                                onClick = { repository.updateAccountTier(acc.id, "Pro") },
                                                label = { Text("Pro", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = ColabOrange.copy(alpha = 0.25f),
                                                    selectedLabelColor = ColabOrange
                                                )
                                            )

                                            FilterChip(
                                                selected = !acc.tier.equals("Pro", ignoreCase = true),
                                                onClick = { repository.updateAccountTier(acc.id, "Free") },
                                                label = { Text("Free", fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    if (index < accounts.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // HUB 2: Theme Hub
            // =========================================================================
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Theme Hub",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Accent palettes, AMOLED Pure Black, and Terminal styling",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Section: Color Palette Selection
                            Text(
                                text = "Color Palette",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val palettes = listOf(
                                Triple("colab_amber", "Colab Amber", Color(0xFFE8710A)),
                                Triple("matrix_green", "Matrix Green", Color(0xFF3FB950)),
                                Triple("cyber_cyan", "Cyber Cyan", Color(0xFF56D4DD)),
                                Triple("neon_violet", "Neon Violet", Color(0xFFBB86FC)),
                                Triple("monochrome", "Monochrome", Color(0xFFEDEDED))
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                palettes.forEach { (id, label, color) ->
                                    val isSelected = selectedPalette == id
                                    Surface(
                                        onClick = {
                                            selectedPalette = id
                                            preferences.setSelectedPalette(id)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest,
                                        border = if (isSelected) BorderStroke(1.5.dp, color) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                        modifier = Modifier.height(38.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .background(color, CircleShape)
                                            )
                                            Text(
                                                text = label,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // Pure AMOLED Black Mode
                            ListItem(
                                headlineContent = {
                                    Text("AMOLED Pure Black (#000000)", fontWeight = FontWeight.SemiBold)
                                },
                                supportingContent = {
                                    Text("Pitch black surfaces for high contrast and OLED power saving")
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.BrightnessLow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = pureBlackEnabled,
                                        onCheckedChange = {
                                            pureBlackEnabled = it
                                            preferences.setPureBlack(it)
                                        }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            // Dark Mode
                            ListItem(
                                headlineContent = {
                                    Text("Dark Theme", fontWeight = FontWeight.SemiBold)
                                },
                                supportingContent = {
                                    Text("Toggle between dark and light appearance")
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.DarkMode,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = darkModeEnabled,
                                        onCheckedChange = {
                                            darkModeEnabled = it
                                            preferences.setDarkMode(it)
                                        }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            // Dynamic Color (Material You)
                            ListItem(
                                headlineContent = {
                                    Text("Dynamic Color (Material You)", fontWeight = FontWeight.SemiBold)
                                },
                                supportingContent = {
                                    Text("Extract wallpaper colors on Android 12+")
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.ColorLens,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = dynamicColorEnabled,
                                        onCheckedChange = {
                                            dynamicColorEnabled = it
                                            preferences.setDynamicColorEnabled(it)
                                        }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // Global Console Background
                            Text(
                                text = "Global Terminal Background",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Default background tone for terminal sessions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            val consoleBgs = listOf(
                                Pair("pitch_black", "Pitch Black"),
                                Pair("slate", "Slate (#0D1117)"),
                                Pair("matrix", "Matrix Green"),
                                Pair("amber", "Amber Glow")
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                consoleBgs.forEach { (bgKey, bgLabel) ->
                                    val isSelected = globalConsoleBg == bgKey
                                    val bgResolved = resolveConsoleBackground(bgKey)
                                    Surface(
                                        onClick = {
                                            globalConsoleBg = bgKey
                                            preferences.setConsoleBackground(bgKey)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest,
                                        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(bgResolved, CircleShape)
                                                    .border(0.5.dp, Color.Gray, CircleShape)
                                            )
                                            Text(
                                                text = bgLabel,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            // Per-VM Console Background (if VMs exist)
                            if (vms.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Per-VM Terminal Customization",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                vms.forEach { vm ->
                                    var vmBg by remember(vm.id) { mutableStateOf(preferences.getConsoleBgForVM(vm.id)) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = vm.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            consoleBgs.forEach { (bgKey, _) ->
                                                val isCur = vmBg == bgKey
                                                val clr = resolveConsoleBackground(bgKey)
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(clr)
                                                        .border(
                                                            if (isCur) 2.dp else 1.dp,
                                                            if (isCur) MaterialTheme.colorScheme.primary else Color.DarkGray,
                                                            CircleShape
                                                        )
                                                        .clickable {
                                                            vmBg = bgKey
                                                            preferences.setConsoleBgForVM(vm.id, bgKey)
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // HUB 3: App Info & Updates Manager
            // =========================================================================
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "App Info & Updates Manager",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "GitHub releases, version telemetry, and direct APK packages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Colab Android Client",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Installed: $currentAppVersion (GitHub pitpitpo/colab)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isCheckingUpdates = true
                                            updateCheckError = null
                                            hasCheckedUpdates = true
                                            try {
                                                val release = fetchGitHubRelease()
                                                latestRelease = release
                                            } catch (e: Exception) {
                                                updateCheckError = e.message ?: "Failed to reach GitHub API"
                                            } finally {
                                                isCheckingUpdates = false
                                            }
                                        }
                                    },
                                    enabled = !isCheckingUpdates,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    if (isCheckingUpdates) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Checking...", fontSize = 11.sp)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Check Updates", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }

                            // Error state
                            updateCheckError?.let { err ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Update error: $err",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            // Release results
                            latestRelease?.let { release ->
                                Spacer(modifier = Modifier.height(14.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = release.title.ifBlank { release.tagName },
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = ColabOrange.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = release.tagName,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ColabOrange,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (release.publishedAt.isNotBlank()) {
                                            Text(
                                                text = "Published: ${release.publishedAt.take(10)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                                            context.startActivity(intent)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("View on GitHub", fontSize = 11.sp)
                                    }
                                }

                                if (release.body.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = release.body.take(280) + if (release.body.length > 280) "..." else "",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(10.dp),
                                            lineHeight = 16.sp
                                        )
                                    }
                                }

                                // Release Assets & Download links
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Available APK Builds:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                if (release.assets.isEmpty()) {
                                    Text(
                                        text = "No direct APK assets in this release yet. Please check CI/CD actions.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    release.assets.forEach { asset ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Download,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column {
                                                        Text(
                                                            text = asset.name,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                        val mb = String.format("%.1f MB", asset.sizeBytes / (1024.0 * 1024.0))
                                                        Text(
                                                            text = mb,
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }

                                                FilledTonalButton(
                                                    onClick = {
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(asset.downloadUrl))
                                                        context.startActivity(intent)
                                                    },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // SECTION 4: SSH Security & Keys
            // =========================================================================
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "SSH Cryptography & Key Management",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "RSA authentication keys and resilient session synchronization",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Public Key Authentication",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = generatedPublicKey,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(10.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("SSH Public Key", generatedPublicKey)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Public key copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy Key", fontSize = 11.sp)
                                }

                                FilledTonalButton(
                                    onClick = {
                                        generatedPublicKey = generateRealPublicKey()
                                        Toast.makeText(context, "New keypair regenerated", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Regenerate", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSignInDialog) {
            SignInAccountDialog(
                onDismiss = { showSignInDialog = false },
                onConfirm = { email, name, token, tier ->
                    val acc = repository.addAccount(email, name, token, tier)
                    repository.spawnAlpineCliInstance(context, acc)
                    showSignInDialog = false
                    Toast.makeText(context, "Account connected & CLI instance initialized in Alpine", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

private suspend fun fetchGitHubRelease(): GitHubReleaseInfo = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val request = Request.Builder()
        .url("https://api.github.com/repos/pitpitpo/colab/releases/latest")
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", "Colab-Android-Client")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            // Fallback to releases list if latest release is draft or pre-release
            val listReq = Request.Builder()
                .url("https://api.github.com/repos/pitpitpo/colab/releases")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Colab-Android-Client")
                .build()

            client.newCall(listReq).execute().use { listResp ->
                if (!listResp.isSuccessful) {
                    throw IllegalStateException("GitHub API returned HTTP ${response.code}")
                }
                val body = listResp.body?.string() ?: throw IllegalStateException("Empty response")
                val array = Gson().fromJson(body, JsonArray::class.java)
                if (array.size() == 0) throw IllegalStateException("No releases found on GitHub")
                val firstObj = array.get(0).asJsonObject
                parseReleaseObject(firstObj)
            }
        } else {
            val body = response.body?.string() ?: throw IllegalStateException("Empty response")
            val obj = Gson().fromJson(body, JsonObject::class.java)
            parseReleaseObject(obj)
        }
    }
}

private fun parseReleaseObject(obj: JsonObject): GitHubReleaseInfo {
    val tag = obj.get("tag_name")?.asString ?: "latest"
    val name = obj.get("name")?.asString ?: tag
    val body = obj.get("body")?.asString ?: ""
    val published = obj.get("published_at")?.asString ?: ""
    val htmlUrl = obj.get("html_url")?.asString ?: "https://github.com/pitpitpo/colab/releases"

    val assetsList = mutableListOf<ReleaseAsset>()
    val assetsJson = obj.getAsJsonArray("assets")
    if (assetsJson != null) {
        for (i in 0 until assetsJson.size()) {
            val a = assetsJson.get(i).asJsonObject
            val aName = a.get("name")?.asString ?: "app.apk"
            val dlUrl = a.get("browser_download_url")?.asString ?: ""
            val size = a.get("size")?.asLong ?: 0L
            if (aName.endsWith(".apk", ignoreCase = true) || aName.endsWith(".zip", ignoreCase = true)) {
                assetsList.add(ReleaseAsset(aName, dlUrl, size))
            }
        }
    }

    return GitHubReleaseInfo(
        tagName = tag,
        title = name,
        body = body,
        publishedAt = published,
        htmlUrl = htmlUrl,
        assets = assetsList
    )
}

private fun generateRealPublicKey(): String {
    return try {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val pubBytes = kp.public.encoded
        val b64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
        "ssh-rsa $b64 device@colab-client"
    } catch (_: Exception) {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val b64 = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
        "ssh-ed25519 $b64 device@colab-client"
    }
}

@Composable
fun SignInAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (email: String, name: String, token: String, tier: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authSession by remember { mutableStateOf(com.pox.colab.engine.ColabAuthEngine.createAuthSession()) }
    var authCode by remember { mutableStateOf("") }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authStatusStep by remember { mutableStateOf<String?>(null) }
    var detectedProfile by remember { mutableStateOf<com.pox.colab.engine.OAuthUserProfile?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!isAuthenticating) onDismiss()
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xFF14171F),
        title = null,
        text = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF14171F),
                contentColor = Color(0xFFE2E8F0),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Terminal Window Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(Color(0xFFFF5F56), CircleShape))
                            Box(modifier = Modifier.size(10.dp).background(Color(0xFFFFBD2E), CircleShape))
                            Box(modifier = Modifier.size(10.dp).background(Color(0xFF27C93F), CircleShape))
                        }
                        Text(
                            text = "colab auth login",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        IconButton(
                            onClick = {
                                authSession = com.pox.colab.engine.ColabAuthEngine.createAuthSession()
                                authError = null
                                authStatusStep = null
                                Toast.makeText(context, "New OAuth PKCE session initialized", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = "Regenerate session",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFF334155))

                    // Authentic Colab CLI Remote Auth Flow Output
                    Text(
                        text = "$ colab auth login --auth=oauth2",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )

                    Text(
                        text = "To authorize colab-cli, visit this URL in any browser:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )

                    // URL Box with full authentic PKCE URL & Copy button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = authSession.authUrl,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF7DD3FC),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Colab CLI Auth URL", authSession.authUrl)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "URL copied! Paste in browser to approve", Toast.LENGTH_LONG).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, Color(0xFF38BDF8))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy URL",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy URL", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Text(
                        text = "After approving, Google will display an authorization code.\nEnter the authorization code:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )

                    // Input Prompt: > [ Enter code ]
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.dp, if (authError != null) Color(0xFFEF4444) else Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "> ",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF22C55E)
                            )
                            BasicTextField(
                                value = authCode,
                                onValueChange = {
                                    authCode = it.trim()
                                    authError = null
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color.White
                                ),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (authCode.isEmpty()) {
                                        Text(
                                            text = "4/0A... paste authorization code",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clipData = clipboard.primaryClip
                                    if (clipData != null && clipData.itemCount > 0) {
                                        val text = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                                        if (text.isNotBlank()) {
                                            authCode = text
                                            authError = null
                                        }
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Paste",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Progress / Status Messages
                    if (isAuthenticating) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1E293B),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF38BDF8)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = authStatusStep ?: "[colab] Authenticating with Google OAuth2...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }

                    // Detected Profile Result
                    detectedProfile?.let { profile ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF064E3B),
                            border = BorderStroke(1.dp, Color(0xFF059669)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "[colab] Authenticated successfully!",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "• Account: ${profile.email}\n• User: ${profile.displayName}\n• Tier: ${profile.tier}\n• Saved: ~/.config/colab-cli/token.json",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFA7F3D0)
                                )
                            }
                        }
                    }

                    // Error Box
                    authError?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF450A0A),
                            border = BorderStroke(1.dp, Color(0xFFDC2626)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "[colab] Error: $err",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFFCA5A5),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (detectedProfile != null) {
                Button(
                    onClick = {
                        val p = detectedProfile!!
                        onConfirm(p.email, p.displayName, p.accessToken, p.tier)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        if (authCode.isNotBlank()) {
                            scope.launch {
                                isAuthenticating = true
                                authError = null
                                authStatusStep = "[colab] Exchanging PKCE authorization code..."
                                val res = com.pox.colab.engine.ColabAuthEngine.authenticateWithCodeOrToken(authCode, authSession)
                                isAuthenticating = false
                                res.onSuccess { profile ->
                                    detectedProfile = profile
                                    onConfirm(profile.email, profile.displayName, profile.accessToken, profile.tier)
                                }.onFailure { e ->
                                    authError = e.message ?: "Authentication failed"
                                }
                            }
                        }
                    },
                    enabled = authCode.isNotBlank() && !isAuthenticating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF38BDF8),
                        contentColor = Color(0xFF0F172A)
                    )
                ) {
                    Text(
                        text = if (isAuthenticating) "Verifying..." else "Authorize",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAuthenticating
            ) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}
