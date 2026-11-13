package com.pox.colab.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pox.colab.BuildConfig
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository
import com.pox.colab.engine.AlpineAppContainerManager
import com.pox.colab.engine.ColabAuthEngine
import com.pox.colab.engine.ColabAuthSession
import com.pox.colab.theme.resolveConsoleBackground
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.util.SemverUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    val currentAppVersion = "v${BuildConfig.VERSION_NAME}"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
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
                                text = "Alpine Colab CLI PKCE instances, compute quotas & tiers",
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
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No Connected Colab Accounts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Tap 'Add Account' to spawn a dedicated CLI instance inside Alpine.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                    Text(acc.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    if (isActive) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                            shape = RoundedCornerShape(6.dp)
                                                        ) {
                                                            Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                        }
                                                    }
                                                }
                                                Text(acc.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }

                                            // Enable/Disable Switch
                                            Switch(
                                                checked = acc.isEnabled,
                                                onCheckedChange = { isChecked ->
                                                    repository.toggleAccountEnabled(acc.id, isChecked, context)
                                                    Toast.makeText(context, if (isChecked) "Account enabled" else "Account paused", Toast.LENGTH_SHORT).show()
                                                }
                                            )

                                            Spacer(modifier = Modifier.width(4.dp))

                                            IconButton(
                                                onClick = {
                                                    repository.removeAccount(context, acc.id)
                                                    Toast.makeText(context, "Account and Alpine CLI instance removed", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Tier selector: Pro vs Free
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Tier:", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            FilterChip(
                                                selected = acc.tier.equals("Pro", ignoreCase = true),
                                                onClick = { repository.updateAccountTier(acc.id, "Pro", context) },
                                                label = { Text("Pro", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            )
                                            FilterChip(
                                                selected = !acc.tier.equals("Pro", ignoreCase = true),
                                                onClick = { repository.updateAccountTier(acc.id, "Free", context) },
                                                label = { Text("Free", fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    if (index < accounts.lastIndex) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                    Text("Theme Hub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Palettes, AMOLED Pure Black, and per-VM terminal styling", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Color Palette", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            val palettes = listOf(
                                Triple("colab_amber", "Colab Amber", Color(0xFFE8710A)),
                                Triple("matrix_green", "Matrix Green", Color(0xFF3FB950)),
                                Triple("cyber_cyan", "Cyber Cyan", Color(0xFF56D4DD)),
                                Triple("neon_violet", "Neon Violet", Color(0xFFBB86FC)),
                                Triple("monochrome", "Monochrome", Color(0xFFEDEDED))
                            )

                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                palettes.forEach { (key, label, color) ->
                                    val isSelected = selectedPalette == key
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .selectable(
                                                selected = isSelected,
                                                enabled = !dynamicColorEnabled,
                                                role = Role.RadioButton
                                            ) {
                                                selectedPalette = key
                                                preferences.setSelectedPalette(key)
                                            }
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box(modifier = Modifier.size(12.dp).background(color, CircleShape))
                                            Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                            if (dynamicColorEnabled) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Palette selection is overridden by Material You while Dynamic Color is enabled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // AMOLED Pure Black
                            ListItem(
                                headlineContent = { Text("AMOLED Pure Black (#000000)", fontWeight = FontWeight.SemiBold) },
                                supportingContent = {
                                    Text(
                                        if (darkModeEnabled) {
                                            "High contrast pure pitch black background for OLED screens"
                                        } else {
                                            "Enable Dark Theme to activate AMOLED Pure Black"
                                        }
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.BrightnessLow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    Switch(
                                        checked = pureBlackEnabled,
                                        enabled = darkModeEnabled,
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
                                headlineContent = { Text("Dark Theme", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("Toggle dark and light appearance") },
                                leadingContent = { Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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

                            // Dynamic Color
                            ListItem(
                                headlineContent = { Text("Dynamic Color (Material You)", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("Extract wallpaper palette on Android 12+") },
                                leadingContent = { Icon(Icons.Default.ColorLens, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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

                            // Terminal Background selection
                            Text("Terminal Console Background", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            val consoleBgs = listOf(
                                Pair("amoled_black", "AMOLED Black"),
                                Pair("deep_charcoal", "Deep Charcoal"),
                                Pair("navy_terminal", "Navy Terminal"),
                                Pair("matrix_dark", "Matrix Dark"),
                                Pair("monokai_dark", "Monokai Dark")
                            )

                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                consoleBgs.forEach { (bgKey, bgLabel) ->
                                    val isCur = globalConsoleBg == bgKey
                                    val clr = resolveConsoleBackground(bgKey)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isCur) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = if (isCur) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .selectable(selected = isCur, role = Role.RadioButton) {
                                                globalConsoleBg = bgKey
                                                preferences.setConsoleBackground(bgKey)
                                            }
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(clr).border(1.dp, Color.Gray, CircleShape))
                                            Text(bgLabel, fontSize = 12.sp, fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }

                            // Per-VM Background Customization
                            if (vms.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Per-VM Terminal Styling", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(6.dp))

                                vms.forEach { vm ->
                                    var vmBg by remember(vm.id) { mutableStateOf(preferences.getConsoleBgForVM(vm.id)) }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(vm.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            consoleBgs.forEach { (bgKey, bgLabel) ->
                                                val isCur = vmBg == bgKey
                                                val clr = resolveConsoleBackground(bgKey)
                                                Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(CircleShape)
                                                        .semantics {
                                                            contentDescription = "$bgLabel background for ${vm.name}"
                                                        }
                                                        .selectable(
                                                            selected = isCur,
                                                            role = Role.RadioButton
                                                        ) {
                                                            vmBg = bgKey
                                                            preferences.setConsoleBgForVM(vm.id, bgKey)
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .clip(CircleShape)
                                                            .background(clr)
                                                            .border(if (isCur) 2.dp else 1.dp, if (isCur) MaterialTheme.colorScheme.primary else Color.DarkGray, CircleShape)
                                                    )
                                                    if (isCur) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = if (clr.luminance() > 0.4f) Color.Black else Color.White,
                                                            modifier = Modifier.size(14.dp)
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
                }
            }

            // =========================================================================
            // HUB 3: App Info & Updates Manager
            // =========================================================================
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("App Info & Updates Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("GitHub releases telemetry and direct APK downloads", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Colab Client", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("Installed: $currentAppVersion (GitHub pitpitpo/colab)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isCheckingUpdates = true
                                            updateCheckError = null
                                            try {
                                                latestRelease = fetchGitHubRelease()
                                            } catch (e: Exception) {
                                                updateCheckError = e.message ?: "Failed to reach GitHub"
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
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Checking...", fontSize = 11.sp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Check Updates", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }

                            updateCheckError?.let { err ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                                    Text("Error: $err", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                                }
                            }

                            latestRelease?.let { release ->
                                Spacer(modifier = Modifier.height(14.dp))
                                val isUpdateAvailable = SemverUtil.isVersionNewer(release.tagName, currentAppVersion)
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (!isUpdateAvailable) Icons.Default.CheckCircle else Icons.Default.Download,
                                                contentDescription = null,
                                                tint = if (!isUpdateAvailable) statusSuccessColor() else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (!isUpdateAvailable) "App is Up to Date ($currentAppVersion)" else "Update Available: ${release.tagName} (Current: $currentAppVersion)",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                        }
                                        if (release.title.isNotBlank() && release.title != release.tagName) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = release.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (release.publishedAt.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Published ${release.publishedAt.take(10)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(release.body.take(200) + if (release.body.length > 200) "..." else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                        if (isUpdateAvailable) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            if (release.assets.isEmpty()) {
                                                OutlinedButton(
                                                    onClick = { openExternalUrl(context, release.htmlUrl) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Open GitHub Release Page", fontSize = 12.sp)
                                                }
                                            } else {
                                                release.assets.forEach { asset ->
                                                    OutlinedButton(
                                                        onClick = { openExternalUrl(context, asset.downloadUrl) },
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Download ${asset.name} (${asset.sizeBytes / (1024 * 1024)} MB)", fontSize = 12.sp)
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
            }
        }

        if (showSignInDialog) {
            SignInAccountDialog(
                onDismiss = { showSignInDialog = false },
                onConfirm = { email, name, accessToken, tier, tokenJson ->
                    repository.addAccount(context, email, name, accessToken, tier, tokenJson)
                    showSignInDialog = false
                    Toast.makeText(context, "Account connected & CLI instance initialized in Alpine", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

private fun openExternalUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "No app available to open this link", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun fetchGitHubRelease(): GitHubReleaseInfo = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    val request = Request.Builder()
        .url("https://api.github.com/repos/pitpitpo/colab/releases/latest")
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", "Colab-Android-Client")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw java.io.IOException("HTTP ${response.code}: ${response.message}")
        }
        val body = response.body?.string() ?: throw IllegalStateException("Empty response")
        val obj = Gson().fromJson(body, JsonObject::class.java)
        val tag = obj.get("tag_name")?.asString ?: "latest"
        val name = obj.get("name")?.asString ?: tag
        val releaseBody = obj.get("body")?.asString ?: ""
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

        GitHubReleaseInfo(
            tagName = tag,
            title = name,
            body = releaseBody,
            publishedAt = published,
            htmlUrl = htmlUrl,
            assets = assetsList
        )
    }
}

@Composable
fun SignInAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (email: String, name: String, accessToken: String, tier: String, tokenJson: String) -> Unit
) {
    val context = LocalContext.current
    var authSession by remember { mutableStateOf<ColabAuthSession?>(null) }
    var authCode by remember { mutableStateOf("") }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The CLI instance + PKCE session are prepared off the main thread instead of inside
    // composition, so opening the dialog cannot jank or ANR on cold start.
    LaunchedEffect(context) {
        try {
            authSession = withContext(Dispatchers.IO) {
                AlpineAppContainerManager.getInstance(context).createInstanceAndGenerateAuthLink()
            }
        } catch (e: Exception) {
            authError = e.message ?: "Could not prepare the authorization link"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isAuthenticating) onDismiss() },
        properties = DialogProperties(decorFitsSystemWindows = true),
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Add Colab Account",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Authorize via the Alpine Colab CLI instance layer. Open or copy the authorization URL generated by the CLI, then paste the returned authorization code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Generated link container card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "CLI OAuth Authorization Link",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = authSession?.authUrl ?: "Preparing authorization link…",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { authSession?.let { openExternalUrl(context, it.authUrl) } },
                                enabled = authSession != null,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    val session = authSession ?: return@OutlinedButton
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Auth URL", session.authUrl))
                                    Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                enabled = authSession != null,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = authCode,
                    onValueChange = { authCode = it.trim() },
                    label = { Text("Authorization Code") },
                    placeholder = { Text("Paste Google authorization code (4/...)") },
                    leadingIcon = {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (authCode.isNotEmpty()) {
                            IconButton(onClick = { authCode = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    isError = authError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                )

                authError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (authCode.isBlank()) {
                        authError = "Please enter the authorization code."
                        return@Button
                    }
                    val session = authSession ?: return@Button
                    isAuthenticating = true
                    authError = null
                    scope.launch {
                        try {
                            val profile = ColabAuthEngine.exchangeCodeForProfile(authCode, session)
                            onConfirm(profile.email, profile.displayName, profile.accessToken, profile.tier, profile.tokenJson)
                        } catch (e: Exception) {
                            authError = e.message ?: "Authentication failed"
                        } finally {
                            isAuthenticating = false
                        }
                    }
                },
                enabled = !isAuthenticating && authSession != null && authCode.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isAuthenticating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Authenticating...")
                } else {
                    Text("Connect Account", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAuthenticating
            ) {
                Text("Cancel")
            }
        }
    )
}
