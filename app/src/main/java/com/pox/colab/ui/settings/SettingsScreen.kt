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

    val currentAppVersion = "v1.0.0"

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
                                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ColabOrange.copy(alpha = 0.25f), selectedLabelColor = ColabOrange)
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
                                        modifier = Modifier.clickable {
                                            selectedPalette = key
                                            preferences.setSelectedPalette(key)
                                        }
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box(modifier = Modifier.size(12.dp).background(color, CircleShape))
                                            Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // AMOLED Pure Black
                            ListItem(
                                headlineContent = { Text("AMOLED Pure Black (#000000)", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("High contrast pure pitch black background for OLED screens") },
                                leadingContent = { Icon(Icons.Default.BrightnessLow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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
                                        modifier = Modifier.clickable {
                                            globalConsoleBg = bgKey
                                            preferences.setConsoleBackground(bgKey)
                                        }
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            consoleBgs.forEach { (bgKey, _) ->
                                                val isCur = vmBg == bgKey
                                                val clr = resolveConsoleBackground(bgKey)
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(clr)
                                                        .border(if (isCur) 2.dp else 1.dp, if (isCur) MaterialTheme.colorScheme.primary else Color.DarkGray, CircleShape)
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
                                val isUpdateAvailable = isVersionNewer(release.tagName, currentAppVersion)
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (!isUpdateAvailable) Icons.Default.CheckCircle else Icons.Default.Download,
                                                contentDescription = null,
                                                tint = if (!isUpdateAvailable) com.pox.colab.theme.TerminalGreen else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (!isUpdateAvailable) "App is Up to Date ($currentAppVersion)" else "Update Available: ${release.tagName} (Current: $currentAppVersion)",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(release.body.take(200) + if (release.body.length > 200) "..." else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        
                                        if (isUpdateAvailable) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            release.assets.forEach { asset ->
                                                OutlinedButton(
                                                    onClick = {
                                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(asset.downloadUrl))
                                                        context.startActivity(browserIntent)
                                                    },
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

        if (showSignInDialog) {
            SignInAccountDialog(
                onDismiss = { showSignInDialog = false },
                onConfirm = { email, name, token, tier ->
                    repository.addAccount(context, email, name, token, tier)
                    showSignInDialog = false
                    Toast.makeText(context, "Account connected & CLI instance initialized in Alpine", Toast.LENGTH_SHORT).show()
                }
            )
        }
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

private fun isVersionNewer(latest: String, current: String): Boolean {
    val l = latest.trim().removePrefix("v").split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    val c = current.trim().removePrefix("v").split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
    val maxLen = maxOf(l.size, c.size)
    for (i in 0 until maxLen) {
        val lPart = l.getOrElse(i) { 0 }
        val cPart = c.getOrElse(i) { 0 }
        if (lPart > cPart) return true
        if (lPart < cPart) return false
    }
    return false
}

@Composable
fun SignInAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (email: String, name: String, token: String, tier: String) -> Unit
) {
    val context = LocalContext.current
    var authSession by remember { mutableStateOf(com.pox.colab.engine.AlpineAppContainerManager.getInstance(context).createInstanceAndGenerateAuthLink()) }
    var authCode by remember { mutableStateOf("") }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isAuthenticating) onDismiss() },
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
                            text = authSession.authUrl,
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
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authSession.authUrl))
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Could not open browser. Please copy link.", Toast.LENGTH_SHORT).show()
                                    }
                                },
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
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Auth URL", authSession.authUrl))
                                    Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
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
                    isAuthenticating = true
                    authError = null
                    scope.launch {
                        try {
                            val profile = com.pox.colab.engine.ColabAuthEngine.exchangeCodeForProfile(authCode, authSession)
                            onConfirm(profile.email, profile.displayName, profile.tokenJson.ifBlank { profile.accessToken }, profile.tier)
                        } catch (e: Exception) {
                            authError = e.message ?: "Authentication failed"
                        } finally {
                            isAuthenticating = false
                        }
                    }
                },
                enabled = !isAuthenticating && authCode.isNotBlank(),
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
