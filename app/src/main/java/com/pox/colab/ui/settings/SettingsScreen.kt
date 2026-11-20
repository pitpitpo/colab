package com.pox.colab.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.pox.colab.BuildConfig
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository
import com.pox.colab.engine.ColabCcuInfo
import com.pox.colab.engine.HttpClientProvider
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.RuntimeEngineMode
import com.pox.colab.theme.MonoFontFamily
import com.pox.colab.theme.resolveConsoleBackground
import com.pox.colab.theme.statusSuccessColor
import com.pox.colab.ui.component.expressive.ExpressiveMorphingChip
import com.pox.colab.ui.component.expressive.ExpressiveSectionHeader
import com.pox.colab.ui.component.expressive.ExpressiveShapes
import com.pox.colab.ui.component.expressive.ExpressiveWavyProgressBar
import com.pox.colab.util.SemverUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

private val SETTINGS_PALETTE_OPTIONS = listOf(
    Triple("colab_amber", "Colab Amber", Color(0xFFE8710A)),
    Triple("matrix_green", "Matrix Green", Color(0xFF3FB950)),
    Triple("cyber_cyan", "Cyber Cyan", Color(0xFF56D4DD)),
    Triple("neon_violet", "Neon Violet", Color(0xFFBB86FC)),
    Triple("monochrome", "Monochrome", Color(0xFFEDEDED))
)

private val SETTINGS_CONSOLE_BG_OPTIONS = listOf(
    Pair("amoled_black", "AMOLED Black"),
    Pair("deep_charcoal", "Deep Charcoal"),
    Pair("navy_terminal", "Navy Terminal"),
    Pair("matrix_dark", "Matrix Dark"),
    Pair("monokai_dark", "Monokai Dark")
)

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
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val activeAccount by repository.activeAccount.collectAsStateWithLifecycle()
    val vms by repository.vms.collectAsStateWithLifecycle()

    var showSignInDialog by remember { mutableStateOf(false) }
    var accountToDelete by remember { mutableStateOf<ColabAccount?>(null) }
    var accountCcuMap by remember { mutableStateOf<Map<String, ColabCcuInfo>>(emptyMap()) }
    var checkingCcuAccountId by remember { mutableStateOf<String?>(null) }

    var runtimeEngineMode by remember { mutableStateOf(preferences.getRuntimeEngineMode()) }

    var dynamicColorEnabled by remember { mutableStateOf(preferences.isDynamicColorEnabled()) }
    var darkModeEnabled by remember { mutableStateOf(preferences.isDarkMode()) }
    var pureBlackEnabled by remember { mutableStateOf(preferences.isPureBlack()) }
    var selectedPalette by remember { mutableStateOf(preferences.getSelectedPalette()) }
    var globalConsoleBg by remember { mutableStateOf(preferences.getConsoleBackground()) }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var updateCheckError by remember { mutableStateOf<String?>(null) }

    val currentAppVersion = "v${BuildConfig.VERSION_NAME}"
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ExpressiveSectionHeader(
                            title = "Accounts Hub",
                            subtitle = "Colab OAuth accounts, quotas & tiers",
                            trailing = {
                                Button(
                                    onClick = { showSignInDialog = true },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    modifier = Modifier.testTag("settings_add_account_button")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Account", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (accounts.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Column(
                                    modifier = Modifier.padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Surface(
                                        modifier = Modifier.size(64.dp),
                                        shape = ExpressiveShapes.scallop(lobes = 8, depth = 0.09f),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.AccountCircle,
                                                contentDescription = null,
                                                modifier = Modifier.size(32.dp),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "No Connected Colab Accounts",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Tap Add Account to connect your Google Colab account.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                accounts.forEachIndexed { index, acc ->
                                    val isActive = acc.id == activeAccount?.id
                                    val cardColor by animateColorAsState(
                                        targetValue = if (isActive) {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainer
                                        },
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "accountCardBg"
                                    )

                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = ExpressiveShapes.groupedItem(index, accounts.size),
                                        color = cardColor
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp)
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
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            text = acc.displayName,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (isActive) {
                                                            Surface(
                                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                                shape = CircleShape
                                                            ) {
                                                                Text(
                                                                    text = "ACTIVE",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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

                                                Switch(
                                                    checked = acc.isEnabled,
                                                    onCheckedChange = { isChecked ->
                                                        repository.toggleAccountEnabled(acc.id, isChecked, context)
                                                        Toast.makeText(
                                                            context,
                                                            if (isChecked) "Account enabled" else "Account paused",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                )

                                                IconButton(
                                                    onClick = { accountToDelete = acc }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete account",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 48.dp)
                                                    .horizontalScroll(rememberScrollState()),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "Tier",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                val isPro = acc.tier.equals("Pro", ignoreCase = true)
                                                ExpressiveMorphingChip(
                                                    selected = isPro,
                                                    label = "Pro",
                                                    onClick = { repository.updateAccountTier(acc.id, "Pro", context) },
                                                    leading = if (isPro) {
                                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                    } else null
                                                )
                                                ExpressiveMorphingChip(
                                                    selected = !isPro,
                                                    label = "Free",
                                                    onClick = { repository.updateAccountTier(acc.id, "Free", context) },
                                                    leading = if (!isPro) {
                                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                    } else null
                                                )
                                                val isCheckingCcu = checkingCcuAccountId == acc.id
                                                ExpressiveMorphingChip(
                                                    selected = accountCcuMap.containsKey(acc.id),
                                                    enabled = !isCheckingCcu,
                                                    label = if (isCheckingCcu) "Checking..." else "Quota",
                                                    onClick = {
                                                        scope.launch {
                                                            checkingCcuAccountId = acc.id
                                                            val res = repository.getAccountCcuInfo(acc.id)
                                                            checkingCcuAccountId = null
                                                            res.fold(
                                                                onSuccess = { info ->
                                                                    accountCcuMap = accountCcuMap + (acc.id to info)
                                                                },
                                                                onFailure = { err ->
                                                                    Toast.makeText(
                                                                        context,
                                                                        "Quota check failed: ${err.message}",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                }
                                                            )
                                                        }
                                                    },
                                                    leading = {
                                                        Icon(
                                                            imageVector = Icons.Default.Bolt,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    },
                                                    modifier = Modifier.testTag("settings_check_ccu_${acc.id}")
                                                )
                                            }

                                            accountCcuMap[acc.id]?.let { ccu ->
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(16.dp),
                                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(start = 48.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        Text(
                                                            text = String.format(
                                                                java.util.Locale.US,
                                                                "%.1f CCU • %.2f/hr • %d active",
                                                                ccu.currentBalance,
                                                                ccu.consumptionRateHourly,
                                                                ccu.assignmentsCount
                                                            ),
                                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        if (ccu.eligibleGpus.isNotEmpty() || ccu.eligibleTpus.isNotEmpty()) {
                                                            val hwSummary = (ccu.eligibleGpus + ccu.eligibleTpus).joinToString(" • ")
                                                            Text(
                                                                text = "Eligible: $hwSummary",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
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

                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ExpressiveSectionHeader(
                            title = "Runtime Provisioning Engine",
                            subtitle = "Select how Google Colab runtimes are allocated"
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RuntimeOptionCard(
                                title = "Native Colab API",
                                badge = if (runtimeEngineMode == RuntimeEngineMode.NATIVE_API) "ACTIVE" else "ZERO BLOAT",
                                description = "Direct REST client communicating with Google Colab backend runtime endpoints.",
                                tags = listOf("Direct REST API", "Zero Local Storage", "Instant Boot"),
                                selected = runtimeEngineMode == RuntimeEngineMode.NATIVE_API,
                                shape = ExpressiveShapes.groupedItem(index = 0, count = 2),
                                onSelect = {
                                    runtimeEngineMode = RuntimeEngineMode.NATIVE_API
                                    preferences.setRuntimeEngineMode(RuntimeEngineMode.NATIVE_API)
                                    Toast.makeText(context, "Runtime Engine: Native Colab API", Toast.LENGTH_SHORT).show()
                                }
                            )

                            RuntimeOptionCard(
                                title = "Embedded Alpine Container",
                                badge = if (runtimeEngineMode == RuntimeEngineMode.ALPINE_PROOT) "ACTIVE" else "PROOT",
                                description = "Isolated PRoot Alpine userland hosting local colab-cli instances per account.",
                                tags = listOf("PRoot Emulation", "colab-cli", "Isolated Dirs"),
                                selected = runtimeEngineMode == RuntimeEngineMode.ALPINE_PROOT,
                                shape = ExpressiveShapes.groupedItem(index = 1, count = 2),
                                onSelect = {
                                    runtimeEngineMode = RuntimeEngineMode.ALPINE_PROOT
                                    preferences.setRuntimeEngineMode(RuntimeEngineMode.ALPINE_PROOT)
                                    Toast.makeText(context, "Runtime Engine: Embedded Alpine Container", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ExpressiveSectionHeader(
                            title = "Theme Hub",
                            subtitle = "Palettes, AMOLED Pure Black & per-VM terminal styling"
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val themeGroupCount = 5
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = ExpressiveShapes.groupedItem(index = 0, count = themeGroupCount),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "Color Palette",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SETTINGS_PALETTE_OPTIONS.forEach { (key, label, color) ->
                                            val isSelected = selectedPalette == key
                                            ExpressiveMorphingChip(
                                                selected = isSelected,
                                                enabled = !dynamicColorEnabled,
                                                label = label,
                                                onClick = {
                                                    selectedPalette = key
                                                    preferences.setSelectedPalette(key)
                                                },
                                                leading = {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(14.dp)
                                                            .clip(CircleShape)
                                                            .background(color)
                                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                                    )
                                                },
                                                trailing = if (isSelected) {
                                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                } else null
                                            )
                                        }
                                    }

                                    if (dynamicColorEnabled) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Palette selection is overridden by Material You while Dynamic Color is enabled.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            ThemeToggleItem(
                                icon = Icons.Default.BrightnessLow,
                                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "AMOLED Pure Black",
                                subtitle = if (darkModeEnabled) {
                                    "Pure #000000 canvas for OLED displays"
                                } else {
                                    "Enable Dark Theme to activate AMOLED Black"
                                },
                                checked = pureBlackEnabled,
                                enabled = darkModeEnabled,
                                shape = ExpressiveShapes.groupedItem(index = 1, count = themeGroupCount),
                                onCheckedChange = {
                                    pureBlackEnabled = it
                                    preferences.setPureBlack(it)
                                }
                            )

                            ThemeToggleItem(
                                icon = Icons.Default.DarkMode,
                                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Dark Theme",
                                subtitle = "Toggle dark and light appearance",
                                checked = darkModeEnabled,
                                enabled = true,
                                shape = ExpressiveShapes.groupedItem(index = 2, count = themeGroupCount),
                                onCheckedChange = {
                                    darkModeEnabled = it
                                    preferences.setDarkMode(it)
                                }
                            )

                            ThemeToggleItem(
                                icon = Icons.Default.ColorLens,
                                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = "Dynamic Color (Material You)",
                                subtitle = "Extract wallpaper palette on Android 12+",
                                checked = dynamicColorEnabled,
                                enabled = true,
                                shape = ExpressiveShapes.groupedItem(index = 3, count = themeGroupCount),
                                onCheckedChange = {
                                    dynamicColorEnabled = it
                                    preferences.setDynamicColorEnabled(it)
                                }
                            )

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = ExpressiveShapes.groupedItem(index = 4, count = themeGroupCount),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "Terminal Console Background",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SETTINGS_CONSOLE_BG_OPTIONS.forEach { (bgKey, bgLabel) ->
                                            val isCur = globalConsoleBg == bgKey
                                            val clr = resolveConsoleBackground(bgKey)
                                            ExpressiveMorphingChip(
                                                selected = isCur,
                                                label = bgLabel,
                                                onClick = {
                                                    globalConsoleBg = bgKey
                                                    preferences.setConsoleBackground(bgKey)
                                                },
                                                leading = {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(14.dp)
                                                            .clip(CircleShape)
                                                            .background(clr)
                                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                                    )
                                                },
                                                trailing = if (isCur) {
                                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                } else null
                                            )
                                        }
                                    }

                                    if (vms.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "Per-VM Terminal Styling",
                                            style = MaterialTheme.typography.labelLarge,
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
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    SETTINGS_CONSOLE_BG_OPTIONS.forEach { (bgKey, bgLabel) ->
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
                                                                    .size(26.dp)
                                                                    .clip(if (isCur) ExpressiveShapes.clover() else CircleShape)
                                                                    .background(clr)
                                                                    .border(
                                                                        if (isCur) 2.dp else 1.dp,
                                                                        if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                                        if (isCur) ExpressiveShapes.clover() else CircleShape
                                                                    )
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
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ExpressiveSectionHeader(
                            title = "App Info & Updates",
                            subtitle = "GitHub releases and direct APK downloads"
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = ExpressiveShapes.clover(),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(46.dp)
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
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column {
                                            Text(
                                                text = "Colab Client",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "$currentAppVersion • pitpitpo/colab",
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    FilledTonalButton(
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
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isCheckingUpdates) "Checking" else "Updates",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (isCheckingUpdates) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    ExpressiveWavyProgressBar(progress = null)
                                }

                                updateCheckError?.let { err ->
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Error: $err",
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(14.dp)
                                        )
                                    }
                                }

                                latestRelease?.let { release ->
                                    Spacer(modifier = Modifier.height(14.dp))
                                    val isUpdateAvailable = SemverUtil.isVersionNewer(release.tagName, currentAppVersion)
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (!isUpdateAvailable) Icons.Default.CheckCircle else Icons.Default.Download,
                                                    contentDescription = null,
                                                    tint = if (!isUpdateAvailable) statusSuccessColor() else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (!isUpdateAvailable) {
                                                        "Up to Date ($currentAppVersion)"
                                                    } else {
                                                        "Update Available: ${release.tagName}"
                                                    },
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold
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
                                            Text(
                                                text = release.body.take(200) + if (release.body.length > 200) "..." else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            if (isUpdateAvailable) {
                                                Spacer(modifier = Modifier.height(12.dp))
                                                if (release.assets.isEmpty()) {
                                                    Button(
                                                        onClick = { openExternalUrl(context, release.htmlUrl) },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        shape = CircleShape
                                                    ) {
                                                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text("Open GitHub Release", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                                    }
                                                } else {
                                                    release.assets.forEach { asset ->
                                                        Button(
                                                            onClick = { openExternalUrl(context, asset.downloadUrl) },
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 4.dp),
                                                            shape = CircleShape
                                                        ) {
                                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(
                                                                text = "Download ${asset.name} (${asset.sizeBytes / (1024 * 1024)} MB)",
                                                                style = MaterialTheme.typography.labelLarge,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
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

        accountToDelete?.let { acc ->
            val linkedVmCount = vms.count { it.accountId == acc.id }
            val vmSummary = when (linkedVmCount) {
                0 -> "No saved VM instances are linked to this account."
                1 -> "1 linked VM instance will also be removed and any active Colab runtime will be unassigned."
                else -> "$linkedVmCount linked VM instances will also be removed and any active Colab runtimes will be unassigned."
            }
            AlertDialog(
                onDismissRequest = { accountToDelete = null },
                shape = RoundedCornerShape(28.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text(
                        text = "Remove account?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Disconnect ${acc.email} and clear its stored OAuth credentials and CLI config. $vmSummary",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            repository.removeAccount(context, acc.id)
                            accountCcuMap = accountCcuMap - acc.id
                            accountToDelete = null
                            Toast.makeText(context, "Account removed", Toast.LENGTH_SHORT).show()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.testTag("confirm_delete_account_button")
                    ) {
                        Text("Remove", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { accountToDelete = null },
                        shape = CircleShape
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun RuntimeOptionCard(
    title: String,
    badge: String,
    description: String,
    tags: List<String>,
    selected: Boolean,
    shape: Shape,
    onSelect: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "runtimeOptionBg"
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = CircleShape
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { tag ->
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = CircleShape
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeToggleItem(
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    shape: Shape,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            supportingContent = {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Surface(
                    shape = ExpressiveShapes.clover(),
                    color = iconContainerColor,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            trailingContent = {
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

internal fun openExternalUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "No app available to open this link", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun fetchGitHubRelease(): GitHubReleaseInfo = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://api.github.com/repos/pitpitpo/colab/releases/latest")
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", "Colab-Android-Client")
        .build()

    HttpClientProvider.fastClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw java.io.IOException("HTTP ${response.code}: ${response.message}")
        }
        val body = response.body?.string() ?: throw IllegalStateException("Empty response")
        val obj = HttpClientProvider.gson.fromJson(body, JsonObject::class.java)
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
