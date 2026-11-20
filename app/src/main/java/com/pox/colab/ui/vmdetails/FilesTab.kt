package com.pox.colab.ui.vmdetails

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.pox.colab.engine.FileManagerEngine
import com.pox.colab.model.ColabVM
import com.pox.colab.model.RemoteFile
import com.pox.colab.theme.MonoFontFamily
import com.pox.colab.ui.component.expressive.ExpressiveShapes
import com.pox.colab.ui.component.shimmer.ListItemPlaceholder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesTab(
    vm: ColabVM,
    onConfigureTunnel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileEngine = remember(vm.id) {
        FileManagerEngine(context, vm)
    }

    DisposableEffect(fileEngine) {
        onDispose {
            fileEngine.close()
        }
    }

    var lastEndpointKey by remember(vm.id) {
        mutableStateOf("${vm.tunnelUrl}|${vm.sshConfig.host}:${vm.sshConfig.port}")
    }
    LaunchedEffect(vm.tunnelUrl, vm.sshConfig.host, vm.sshConfig.port) {
        val nextKey = "${vm.tunnelUrl}|${vm.sshConfig.host}:${vm.sshConfig.port}"
        if (nextKey != lastEndpointKey) {
            lastEndpointKey = nextKey
            fileEngine.invalidateCache()
            fileEngine.refreshDirectory(forceRefresh = true)
        }
    }

    val currentPath by fileEngine.currentPath.collectAsStateWithLifecycle()
    val files by fileEngine.files.collectAsStateWithLifecycle()
    val isLoading by fileEngine.isLoading.collectAsStateWithLifecycle()
    val statusNotice by fileEngine.statusNotice.collectAsStateWithLifecycle()

    val (host, _) = remember(vm.id, vm.tunnelUrl, vm.sshConfig.host, vm.sshConfig.port) {
        fileEngine.resolveHostAndPort()
    }
    val hasTunnel = host.isNotBlank()
    val hasDirectRuntime = remember(vm.id, vm.status) { fileEngine.hasDirectRuntime() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFileForPreview by remember { mutableStateOf<RemoteFile?>(null) }
    var previewContent by remember { mutableStateOf("") }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<RemoteFile?>(null) }

    LaunchedEffect(statusNotice) {
        statusNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            fileEngine.clearStatusNotice()
        }
    }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!hasTunnel && !hasDirectRuntime) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(26.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Cable,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Tunnel host required to browse remote SFTP",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfigureTunnel,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Text("Set Host", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = { fileEngine.navigateUp() },
                    enabled = currentPath != "/",
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Navigate to parent directory",
                        modifier = Modifier.size(20.dp)
                    )
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "colab:$currentPath",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFontFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }

                IconButton(onClick = { fileEngine.refreshDirectory(forceRefresh = true) }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh files")
                }

                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "Create directory")
                }

                FilledTonalIconButton(
                    onClick = { showUploadDialog = true },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "Upload file",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (files.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Search files in directory",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        if (isLoading && filteredFiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                repeat(6) { idx ->
                    ListItemPlaceholder(
                        shape = ExpressiveShapes.groupedItem(idx, 6)
                    )
                }
            }
        } else if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(76.dp),
                            shape = ExpressiveShapes.scallop(lobes = 8, depth = 0.09f),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Directory Empty",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "No files in $currentPath. Use the upload or new folder actions above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(filteredFiles, key = { _, file -> file.path }) { index, file ->
                    FileListItem(
                        file = file,
                        shape = ExpressiveShapes.groupedItem(index, filteredFiles.size),
                        onOpen = {
                            if (file.isDirectory) {
                                fileEngine.navigateTo(file.path)
                            } else {
                                scope.launch {
                                    previewContent = fileEngine.readFileContent(file)
                                    selectedFileForPreview = file
                                }
                            }
                        },
                        onDownload = {
                            scope.launch {
                                fileEngine.downloadFileToDevice(file)
                            }
                        },
                        onPreview = {
                            scope.launch {
                                previewContent = fileEngine.readFileContent(file)
                                selectedFileForPreview = file
                            }
                        },
                        onRename = { fileToRename = file },
                        onDelete = { fileEngine.deleteFile(file) }
                    )
                }
            }
        }
    }

    selectedFileForPreview?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedFileForPreview = null },
            properties = DialogProperties(decorFitsSystemWindows = true),
            shape = RoundedCornerShape(32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = file.formattedSize(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            },
            text = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = previewContent,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = MonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            fileEngine.downloadFileToDevice(file)
                            selectedFileForPreview = null
                        }
                    },
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedFileForPreview = null }) {
                    Text("Close")
                }
            }
        )
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            properties = DialogProperties(decorFitsSystemWindows = true),
            shape = RoundedCornerShape(32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("New Directory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Directory Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            fileEngine.createFolder(folderName.trim())
                            showNewFolderDialog = false
                        }
                    },
                    enabled = folderName.isNotBlank(),
                    shape = CircleShape
                ) {
                    Text("Create", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showUploadDialog) {
        var uploadFileName by remember { mutableStateOf("") }
        var uploadFileContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            properties = DialogProperties(decorFitsSystemWindows = true),
            shape = RoundedCornerShape(32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Create / Upload File", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = uploadFileName,
                        onValueChange = { uploadFileName = it },
                        label = { Text("File Name") },
                        placeholder = { Text("e.g. main.py") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = uploadFileContent,
                        onValueChange = { uploadFileContent = it },
                        label = { Text("File Content") },
                        placeholder = { Text("Paste code or text...") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        shape = RoundedCornerShape(18.dp),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (uploadFileName.isNotBlank()) {
                            scope.launch {
                                fileEngine.uploadFile(uploadFileName.trim(), uploadFileContent)
                                showUploadDialog = false
                            }
                        }
                    },
                    enabled = uploadFileName.isNotBlank(),
                    shape = CircleShape
                ) {
                    Text("Upload", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUploadDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    fileToRename?.let { file ->
        var newName by remember { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            properties = DialogProperties(decorFitsSystemWindows = true),
            shape = RoundedCornerShape(32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Rename", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            fileEngine.renameFile(file, newName.trim())
                            fileToRename = null
                        }
                    },
                    enabled = newName.isNotBlank(),
                    shape = CircleShape
                ) {
                    Text("Confirm", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FileListItem(
    file: RemoteFile,
    shape: Shape = RoundedCornerShape(18.dp),
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        ListItem(
            modifier = Modifier.clickable { onOpen() },
            headlineContent = {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    text = buildString {
                        append(if (file.isDirectory) "Directory" else file.formattedSize())
                        if (file.modifiedTime.isNotBlank()) {
                            append(" • ")
                            append(file.modifiedTime)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Surface(
                    shape = ExpressiveShapes.clover(),
                    color = when {
                        file.isDirectory -> MaterialTheme.colorScheme.primaryContainer
                        file.isTextOrCode -> MaterialTheme.colorScheme.secondaryContainer
                        file.name.endsWith(".pt") || file.name.endsWith(".safetensors") -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                file.isDirectory -> Icons.Default.Folder
                                file.name.endsWith(".pt") || file.name.endsWith(".safetensors") -> Icons.Default.Storage
                                file.isTextOrCode -> Icons.Default.Code
                                else -> Icons.Default.Description
                            },
                            contentDescription = null,
                            tint = when {
                                file.isDirectory -> MaterialTheme.colorScheme.onPrimaryContainer
                                file.isTextOrCode -> MaterialTheme.colorScheme.onSecondaryContainer
                                file.name.endsWith(".pt") || file.name.endsWith(".safetensors") -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!file.isDirectory) {
                        IconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download file",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "File actions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (!file.isDirectory) {
                                DropdownMenuItem(
                                    text = { Text("Download") },
                                    onClick = {
                                        menuExpanded = false
                                        onDownload()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Preview") },
                                    onClick = {
                                        menuExpanded = false
                                        onPreview()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}
